package com.sellerops.review.judgement;

import com.sellerops.agent.reply.ReplyAgentService;
import com.sellerops.agent.reply.ReplyStandard;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.review.Review;
import com.sellerops.review.draft.ReviewDraftComposer;
import com.sellerops.review.triage.ReviewSafetySignal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>Every review is judged once, whether or not anyone drafts for it</b> (owner-endorsed outside review, 2026-10-28:
 * 「분석할 대상과 초안을 작성할 대상을 분리」 — {@code docs/standing/reply_agent_v1.md} §3-4).
 *
 * <p>A new review is read by the reply agent's judge as soon as it arrives — on a channel that takes no reply, in a
 * band the seller drafts only on request, at any rating — and the reading is stored as the review's one
 * {@link ReviewJudgement}. What to do with it (draft ahead, hand to a person, nothing) is decided after, by
 * {@link #decide} and the seller's own rule. A reading is reused while the review's words and the judge are unchanged,
 * so the reply agent that runs later does not pay for it again.
 *
 * <p>The decision is the program's, from the reading and the facts it holds: any category S · L · X, the word net's
 * signal (auxiliary — a person looks even when the reading disagrees, or when there is no reading), 「cannot tell」 with
 * its reason, more points than a reply covers, a question about a product the review is not tied to, or a question with
 * no registered fact at all.
 */
@Service
public class ReviewJudgeService {

    private static final Logger log = LoggerFactory.getLogger(ReviewJudgeService.class);

    /** Actions that mean the seller owes this review something. */
    static final List<String> NEEDS_SELLER = List.of("PUBLIC_REPLY", "CHECK_ORDER", "ASK_MORE_INFO", "PERSON_REVIEW");

    private final ReplyAgentService agent;
    private final ReviewDraftComposer composer;
    private final ReviewJudgementRepository judgements;

    public ReviewJudgeService(ReplyAgentService agent, ReviewDraftComposer composer,
                              ReviewJudgementRepository judgements) {
        this.agent = agent;
        this.composer = composer;
        this.judgements = judgements;
    }

    /** What the program decided from a reading: the category, whether the seller owes something, and a handoff. */
    public record Decision(String category, boolean needsResponse, boolean handoff, String reason, List<String> actions) {
    }

    /** One review's reading and decision, as stored. */
    public record Judged(Optional<ReplyAgentService.Classified> reading, Decision decision) {
    }

    private com.sellerops.agent.quota.AgentQuotaService quota;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setQuota(com.sellerops.agent.quota.AgentQuotaService quota) {
        this.quota = quota;
    }

    public boolean enabledFor(UUID orgId) {
        return agent.isEnabledFor(orgId);
    }

    /** Judge one review now, or reuse the stored reading when its words and the judge are unchanged. */
    public Judged judge(UUID orgId, Review review, String trigger, String runId) {
        // The search helpers' calls are charged to what started the judgement (2026-10-31).
        ReviewDraftComposer.AgentInputs in = com.sellerops.agent.quota.AgentUsageScope.as(
                ReplyAgentService.actorOf(trigger), () -> composer.agentInputs(orgId, review));
        return judge(orgId, review, in, trigger, runId);
    }

    public Judged judge(UUID orgId, Review review, ReviewDraftComposer.AgentInputs in, String trigger, String runId) {
        String inputHash = hashOf(in.redactedBody());
        ReviewSafetySignal.Kind signal = ReviewSafetySignal.of(in.redactedBody()).orElse(null);
        ReviewJudgement row = judgements == null ? null
                : judgements.findByOrgIdAndReviewId(orgId, review.getId()).orElse(null);
        Optional<ReplyAgentService.Classified> reading;
        if (fresh(row, inputHash)) {
            reading = Optional.of(readingOf(row));
        } else {
            reading = agent.judge(orgId, runId, trigger, in.redactedBody());
        }
        Decision decision = decide(reading.orElse(null), in, signal);
        save(orgId, review.getId(), row, reading.orElse(null), inputHash, signal, decision);
        return new Judged(reading, decision);
    }

    /**
     * Judge each of these reviews that has no current reading — the sync's new ones, before anything is drafted. A
     * failure on one review is logged and the rest go on.
     *
     * @return how many were judged (a reused reading counts as judged)
     */
    public int judgeAll(UUID orgId, List<Review> reviews, String trigger) {
        if (!enabledFor(orgId)) {
            return 0;
        }
        int judged = 0;
        for (Review review : reviews) {
            if (quota != null && !quota.hasRoom(orgId, ReplyAgentService.actorOf(trigger))) {
                // The day's AI ceiling — for automatic reading, where the seller's reserve begins: the rest is read when
                // budget returns, not recorded as failed readings.
                log.info("review judge: 상한 도달로 멈춤 org={} 남은={}", orgId, reviews.size() - judged);
                break;
            }
            try {
                if (judge(orgId, review, trigger, null).reading().isPresent()) {
                    judged++;
                }
            } catch (RuntimeException e) {
                log.info("review judge: 실패 org={} 사유={}", orgId, e.getClass().getSimpleName());
            }
        }
        return judged;
    }

    /** The stored decision of a review, when its reading is current — what drafting ahead reads first. */
    public Optional<Decision> current(UUID orgId, Review review) {
        if (judgements == null) {
            return Optional.empty();
        }
        // The same freshness rule {@link #judge} reuses a reading by — the review's words as they are now and the judge
        // as it is now — and the stored 「needs a response」 rather than a second computation of it (owner, 2026-10-29).
        String inputHash = hashOf(VocPreviewSanitizer.redactFullBody(review.getBody()).text());
        return judgements.findByOrgIdAndReviewId(orgId, review.getId())
                .filter(row -> fresh(row, inputHash))
                .map(row -> new Decision(row.getCategory(), row.isNeedsResponse(), row.getHandoffReason() != null,
                        row.getHandoffReason(), split(row.getActions())));
    }

    /** A stored reading is current while the model answered, the words hash the same, and the judge is the same. */
    private boolean fresh(ReviewJudgement row, String inputHash) {
        return row != null && row.isAnswered() && inputHash.equals(row.getInputHash())
                && agent.version().equals(row.getModelVersion());
    }

    /** The hash of the redacted body, as {@code ReviewDraftComposer#agentInputs} hands it (blank for none). */
    static String hashOf(String redactedBody) {
        return sha256(redactedBody == null ? "" : redactedBody);
    }

    /** A later step found the review is a person's (the drafter: no fact, or facts that conflict) — the row says so. */
    public void noteHandoff(UUID orgId, UUID reviewId, String reason) {
        if (judgements == null) {
            return;
        }
        judgements.findByOrgIdAndReviewId(orgId, reviewId).ifPresent(row -> {
            row.setHandoffReason(reason);
            // A person's now is work owed, whatever the reading proposed.
            row.setNeedsResponse(true);
            judgements.save(row);
        });
    }

    public Decision decide(ReplyAgentService.Classified read, ReviewDraftComposer.AgentInputs in,
                           ReviewSafetySignal.Kind signal) {
        if (read == null) {
            // No reading: a review the word net flags still goes to a person rather than wait unseen.
            return signal == null ? new Decision(null, false, false, null, List.of())
                    : new Decision(null, true, true, "SAFETY_SIGNAL", List.of());
        }
        List<String> actions = read.actions() == null ? List.of() : read.actions();
        if (read.unsure()) {
            return new Decision(null, true, true,
                    "PRODUCT_UNCLEAR".equals(read.unsureReason()) ? "UNSURE_PRODUCT" : "UNSURE_AMBIGUOUS", actions);
        }
        ReplyStandard standard = agent.standard();
        List<String> carried = new ArrayList<>();
        carried.add(read.category());
        carried.addAll(read.also());
        // The seller owes something when any category it carries needs a response, or the judge proposes anything but
        // 「nothing」 — the two read the same words and the safer of them stands.
        boolean needs = carried.stream().map(c -> standard.category(c).orElse(null))
                .anyMatch(c -> c != null && c.needsResponse())
                || actions.stream().anyMatch(NEEDS_SELLER::contains);
        for (ReplyStandard.Category c : standard.categories()) {
            if (c.handoff() && carried.contains(c.code())) {
                return new Decision(read.category(), needs, true, "CATEGORY_" + c.code(), actions);
            }
        }
        if (signal != null) {
            return new Decision(read.category(), true, true, "SAFETY_SIGNAL", actions);
        }
        if (actions.contains("PERSON_REVIEW")) {
            return new Decision(read.category(), true, true, "PERSON_REVIEW", actions);
        }
        if (read.points().size() > ReplyAgentService.MAX_POINTS) {
            return new Decision(read.category(), true, true, "TOO_MANY_POINTS", actions);
        }
        if (carried.contains("Q") && in.productId() == null) {
            return new Decision(read.category(), true, true, "UNSURE_PRODUCT", actions);
        }
        // 「No fact registered」 only when nothing at all is offered: the word search finding nothing is not the store having
        // nothing — the bundle carries the product's material and the policies, and the drafter says whether any of it
        // answers (2026-11-01, fifth round: two questions with their answers in the bundle were handed over as 「no facts」).
        if (carried.contains("Q") && (in.currentTexts() == null || in.currentTexts().isEmpty())
                && in.bundle().isEmpty()) {
            return new Decision(read.category(), true, true, "QUESTION_NO_FACTS", actions);
        }
        return new Decision(read.category(), needs, false, null, actions);
    }

    private void save(UUID orgId, UUID reviewId, ReviewJudgement row, ReplyAgentService.Classified read,
                      String inputHash, ReviewSafetySignal.Kind signal, Decision decision) {
        if (judgements == null) {
            return;
        }
        ReviewJudgement r = row == null ? new ReviewJudgement() : row;
        r.setOrgId(orgId);
        r.setReviewId(reviewId);
        r.setJudgedAt(Instant.now());
        r.setSource("REPLY_AGENT");
        r.setModelVersion(agent.version());
        r.setInputHash(inputHash);
        r.setAnswered(read != null);
        r.setCategory(read == null ? null : read.category());
        r.setAlsoCategories(read == null || read.also().isEmpty() ? null : String.join(",", read.also()));
        r.setPoints(read == null || read.points().isEmpty() ? null : String.join("\n", read.points()));
        r.setActions(read == null || read.actions() == null || read.actions().isEmpty() ? null
                : String.join(",", read.actions()));
        r.setUnsureReason(read == null ? "MODEL_FAILED" : read.unsureReason());
        r.setSafetySignal(signal == null ? null : signal.name());
        r.setHandoffReason(decision.handoff() ? decision.reason() : null);
        r.setNeedsResponse(decision.needsResponse());
        judgements.save(r);
    }

    private static ReplyAgentService.Classified readingOf(ReviewJudgement row) {
        return new ReplyAgentService.Classified(row.getCategory(), split(row.getAlsoCategories()),
                row.getPoints() == null || row.getPoints().isBlank() ? List.of() : Arrays.asList(row.getPoints().split("\n")),
                row.getCategory() == null, split(row.getActions()),
                row.getCategory() == null ? row.getUnsureReason() : null);
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
