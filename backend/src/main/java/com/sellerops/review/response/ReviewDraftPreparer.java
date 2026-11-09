package com.sellerops.review.response;

import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.review.Review;
import com.sellerops.review.triage.pilot.AiTriagePilotService;
import com.sellerops.review.triage.ReviewSafetySignal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>Writes a review's reply draft before the seller opens it, when the seller's 리뷰 응대 기준 says to</b>
 * (product-owner, 2026-10-28) — the review counterpart of {@code CaseDraftPreparer}, called after each sync
 * ({@code AheadWorkListener}) and from the automatic check.
 *
 * <p>Every gate on the review itself is {@link ReviewReplyService#prepareAhead}'s, the same service the seller's press
 * runs. This class adds the ones that are about the run, not the review:
 * <ul>
 *   <li><b>the seller's rule</b> — a band set to 「누를 때 쓰기」 is never drafted ahead, and one set to 「문제 있을 때만」
 *       (the ★4–5 default) only for a review the AI judge says needs a response;</li>
 *   <li><b>one judge for every rating</b> (owner, 2026-10-28: 「1~5점 전부 일관된 기준으로 llm이 심사해야해」) — whether a
 *       review needs a response is the review triage classifier's own verdict
 *       ({@link AiTriagePilotService#needsResponse}), read the same way at ★1 and at ★5. No word list decides it. A
 *       review the judge could not read — switched off, refused, failed — is {@link Rank#UNJUDGED}: a 「문제 있을
 *       때만」 band waits for the seller rather than guess (owner, the same day);</li>
 *   <li><b>the reach</b> — only reviews written within {@link #REACH}; an older review is drafted when the seller asks,
 *       as an older inquiry is;</li>
 *   <li><b>half the day's AI budget</b> — drafting ahead stops while at least half of today's calls are left for the
 *       seller's own presses. A review draft can cost two calls, so it is the next two that must fit;</li>
 *   <li><b>what needs a response first, and thank-yous only a quarter</b> (owner, 2026-10-28: 「바쁜 날엔 칭찬 리뷰가 그
 *       절반을 먼저 다 쓸 수 있어요」). A run drafts in {@link Rank} order — the judge said a response is needed, then
 *       not yet judged, then no response needed — lower ratings and safety words first within a rank, the longest
 *       waiting after that; and a ★4–5 the judge says needs no response stops at a quarter of the day. Ordering alone would not hold
 *       across a day: a quiet morning of thank-yous would spend the half before the afternoon's complaints arrived.</li>
 * </ul>
 * And the capability itself: with drafting off for the org nothing is written, so a deployment without a model pays
 * nothing for this class.
 */
@Component
public class ReviewDraftPreparer {

    /** The automatic check's re-read window — {@code OperationsCaseProcessor.ACQUISITION_REACH}. */
    public static final Duration REACH = Duration.ofDays(15);

    /** The most model calls one review draft can spend ({@code ReviewDraftComposer}: a draft, and one retry when a banned phrase came back). */
    static final int CALLS_PER_DRAFT = 2;

    /** The most a reply-agent run can spend: judge, draft, critique, one revision and its critique (reply_agent_v1 §1). */
    static final int CALLS_PER_AGENT_RUN = 5;

    /** The reply agent, when this deployment runs it (2026-10-28); null or unavailable ⇒ the single-call drafter, except on an install that requires the agent. */
    private com.sellerops.agent.reply.ReplyRunStarter agentRuns;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAgentRuns(com.sellerops.agent.reply.ReplyRunStarter agentRuns) {
        this.agentRuns = agentRuns;
    }

    private static final Logger log = LoggerFactory.getLogger(ReviewDraftPreparer.class);

    private final ReviewResponseRuleService rules;
    private final ReviewReplyService replies;
    private final AgentDraftService model;
    private final AgentQuotaService quota;
    private final AiTriagePilotService judge;

    public ReviewDraftPreparer(ReviewResponseRuleService rules, ReviewReplyService replies, AgentDraftService model,
                               AgentQuotaService quota, AiTriagePilotService judge) {
        this.rules = rules;
        this.replies = replies;
        this.model = model;
        this.quota = quota;
        this.judge = judge;
    }

    /** Why nothing was written, as a closed word; null when a draft was. */
    public enum Skipped {
        RULE_ON_REQUEST, RULE_ISSUES_ONLY, UNJUDGED, OUT_OF_REACH, BUDGET_HALF_SPENT, CAPABILITY_OFF, REVIEW_GATE, FAILED,
        /** The reply agent handed the review to a person (a category that needs one, or a draft that still failed). */
        HANDOFF
    }

    /** The order a run drafts in, most urgent first — the AI judge's verdict, and nothing read from the words. */
    public enum Rank {
        /** The judge said this review needs a response. */
        RESPONSE_NEEDED,
        /** The judge could not say — it is off for the org, refused the channel, or failed. */
        UNJUDGED,
        /** The judge said no response is owed: a thank-you, at most. */
        NO_RESPONSE
    }

    /** One review's rank from the judge's answer. */
    static Rank rankOf(Optional<Boolean> verdict) {
        return verdict.isEmpty() ? Rank.UNJUDGED : verdict.get() ? Rank.RESPONSE_NEEDED : Rank.NO_RESPONSE;
    }

    /** Of today's calls, the share a review may spend ahead: half, and a quarter for a ★4–5 that needs no response. */
    static int budgetShareDivisor(Rank rank, Integer rating) {
        return rank == Rank.NO_RESPONSE && ReviewResponseRules.band(rating) == ReviewResponseRules.Band.HIGH ? 4 : 2;
    }

    /**
     * <b>Draft a run's reviews ahead, most urgent first</b> — called once per sync or run, after every review of it is
     * known, so the order is the judge's and not the order the channels were read in. Each review is judged once,
     * only when its band could be drafted ahead and it is within reach. Stops when asked to, and when a review that is
     * not a thank-you no longer fits the half: nothing ranked behind it could.
     *
     * @return how many drafts were written
     */
    public int prepareAll(UUID orgId, Collection<Review> reviews, Instant now, BooleanSupplier stop) {
        return prepareAll(orgId, reviews, now, stop, "SCHEDULED");
    }

    /** The same, naming what started it — {@code INGEST} after a sync, {@code SCHEDULED} from the automatic check. */
    public int prepareAll(UUID orgId, Collection<Review> reviews, Instant now, BooleanSupplier stop, String trigger) {
        Map<UUID, Review> unique = new LinkedHashMap<>();
        reviews.forEach(r -> unique.putIfAbsent(r.getId(), r));
        ReviewResponseRules.Modes modes = rules.modesFor(orgId);
        List<Review> ordered = new ArrayList<>();
        Map<UUID, Rank> ranks = new LinkedHashMap<>();
        for (Review review : unique.values()) {
            if (stop != null && stop.getAsBoolean()) {
                return 0;
            }
            if (modes.forRating(review.getRating()) == ReviewResponseMode.ON_REQUEST || !withinReach(review, now)) {
                continue;
            }
            ordered.add(review);
            ranks.put(review.getId(), rankOf(verdict(orgId, review)));
        }
        ordered.sort(Comparator.<Review, Rank>comparing(r -> ranks.get(r.getId()))
                .thenComparing(r -> !ReviewSafetySignal.in(VocPreviewSanitizer.redactFullBody(r.getBody()).text()))
                .thenComparing(Review::getRating, Comparator.nullsLast(Comparator.naturalOrder()))
                // Oldest first within a rank and rating (the deferred item, 2026-10-28): inside the 15-day reach the
                // customer who has waited longest is answered first, and a fresh review is not ahead of them.
                .thenComparing(Review::getReceivedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        int written = 0;
        for (Review review : ordered) {
            if (stop != null && stop.getAsBoolean()) {
                break;
            }
            Rank rank = ranks.get(review.getId());
            Skipped skipped = prepare(orgId, review, now, rank, modes, trigger);
            if (skipped == null) {
                written++;
            } else if (skipped == Skipped.BUDGET_HALF_SPENT && rank != Rank.NO_RESPONSE) {
                break;
            }
        }
        return written;
    }

    /** @return null when a draft was written, otherwise why not */
    public Skipped prepare(UUID orgId, Review review, Instant now) {
        ReviewResponseRules.Modes modes = rules.modesFor(orgId);
        if (modes.forRating(review.getRating()) == ReviewResponseMode.ON_REQUEST) {
            return Skipped.RULE_ON_REQUEST;
        }
        if (!withinReach(review, now)) {
            return Skipped.OUT_OF_REACH;
        }
        return prepare(orgId, review, now, rankOf(verdict(orgId, review)), modes, "SCHEDULED");
    }

    private Skipped prepare(UUID orgId, Review review, Instant now, Rank rank, ReviewResponseRules.Modes modes,
                            String trigger) {
        ReviewResponseMode mode = modes.forRating(review.getRating());
        if (mode == ReviewResponseMode.ON_REQUEST) {
            return Skipped.RULE_ON_REQUEST;
        }
        if (mode == ReviewResponseMode.ISSUES_ONLY && rank != Rank.RESPONSE_NEEDED) {
            return rank == Rank.UNJUDGED ? Skipped.UNJUDGED : Skipped.RULE_ISSUES_ONLY;
        }
        if (!withinReach(review, now)) {
            return Skipped.OUT_OF_REACH;
        }
        // An install drafts only through the agent: when it cannot run here, nothing is drafted ahead (no single call).
        boolean viaAgent = agentRuns != null && (agentRuns.available(orgId) || agentRuns.required());
        if (viaAgent && !agentRuns.available(orgId)) {
            return Skipped.CAPABILITY_OFF;
        }
        if (!viaAgent && !model.isEnabledFor(orgId)) {
            return Skipped.CAPABILITY_OFF;
        }
        // The day's actual AI ceiling already counts every call — the seller's, automatic runs', checks and retries
        // (owner, 2026-10-29) — so the half is of that one number.
        if (!withinBudget(quota.status(orgId), 0, budgetShareDivisor(rank, review.getRating()),
                viaAgent ? CALLS_PER_AGENT_RUN : CALLS_PER_DRAFT)) {
            return Skipped.BUDGET_HALF_SPENT;
        }
        try {
            if (viaAgent) {
                // The reply agent (2026-10-28): judge, draft, critique, revise once, save — or hand to a person.
                if (!replies.mayWriteAhead(orgId, review)) {
                    return Skipped.REVIEW_GATE;
                }
                if (agentRuns.handedOff(orgId, review.getId())) {
                    return Skipped.HANDOFF;
                }
                return agentRuns.startReview(orgId, review.getId(), trigger, null)
                        .map(report -> "SAVED".equals(report.outcome()) ? null
                                : "HANDOFF".equals(report.outcome()) ? Skipped.HANDOFF : Skipped.REVIEW_GATE)
                        .orElse(Skipped.FAILED);
            }
            return replies.prepareAhead(orgId, review).isPresent() ? null : Skipped.REVIEW_GATE;
        } catch (RuntimeException e) {
            log.info("responsibility: 리뷰 초안 미리 쓰기 실패 org={} 사유={}", orgId, e.getClass().getSimpleName());
            return Skipped.FAILED;
        }
    }

    /** The judge's answer, or empty when it could not give one — never a guess, and never a reason to fail the run. */
    private Optional<Boolean> verdict(UUID orgId, Review review) {
        // The review's one judgement first (§3-4): what the reply agent's judge read when the review arrived, under the
        // program's decision. The review triage AI answers only for a review that judgement has not reached yet.
        if (judgements != null) {
            try {
                Optional<Boolean> shared = judgements.current(orgId, review)
                        .map(com.sellerops.review.judgement.ReviewJudgeService.Decision::needsResponse);
                if (shared.isPresent()) {
                    return shared;
                }
            } catch (RuntimeException e) {
                log.info("responsibility: 공통 판단 읽기 실패 org={} 사유={}", orgId, e.getClass().getSimpleName());
            }
        }
        if (judge == null) {
            return Optional.empty();
        }
        try {
            return judge.needsResponse(orgId, review);
        } catch (RuntimeException e) {
            log.info("responsibility: 리뷰 응대 판단 실패 org={} 사유={}", orgId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private com.sellerops.review.judgement.ReviewJudgeService judgements;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJudgements(com.sellerops.review.judgement.ReviewJudgeService judgements) {
        this.judgements = judgements;
    }

    private static boolean withinReach(Review review, Instant now) {
        return review.getReceivedAt() != null && !review.getReceivedAt().isBefore(now.minus(REACH));
    }

    /** The next draft fits inside half of today's calls; a budget that is off or unlimited never stops it. */
    static boolean withinHalfBudget(AgentQuotaService.AgentQuotaStatus status) {
        return withinBudget(status, 2);
    }

    /** The next draft fits inside {@code 1/divisor} of today's calls; a budget that is off or unlimited never stops it. */
    public static boolean withinBudget(AgentQuotaService.AgentQuotaStatus status, int divisor) {
        return withinBudget(status, 0, divisor, CALLS_PER_DRAFT);
    }

    /** The same, counting the calls reply-agent runs already spent today and what the next one may spend. */
    static boolean withinBudget(AgentQuotaService.AgentQuotaStatus status, long runCalls, int divisor, int next) {
        if (status == null || !status.enabled() || status.llmCallsLimit() <= 0) {
            return true;
        }
        return status.llmCallsUsed() + runCalls + next <= status.llmCallsLimit() / divisor;
    }
}
