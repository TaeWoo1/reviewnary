package com.sellerops.agent.reply;

import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>A seller asks the reply agent to write one review's reply</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md}).
 * The seller's login reaches this route and only this one; the run itself uses its own token, and the runtime never
 * sees the seller's. Same gates as the seller's own press: this org's review, not answered, no approval standing.
 */
@RestController
@RequestMapping("/api/agent/reply")
public class ReplyStartController {

    private final ReplyRunStarter starter;
    private final ReviewRepository reviews;
    private final ReviewReplyService replies;
    private final com.sellerops.auth.runtoken.ReplyAgentRunRepository runs;

    public ReplyStartController(ReplyRunStarter starter, ReviewRepository reviews, ReviewReplyService replies,
                                com.sellerops.auth.runtoken.ReplyAgentRunRepository runs) {
        this.starter = starter;
        this.reviews = reviews;
        this.replies = replies;
        this.runs = runs;
    }

    /**
     * One review the agent handed to a person: why (a closed word — CATEGORY_S · CATEGORY_L · CATEGORY_X · STILL_FAILS),
     * its category, when, and enough to recognise it in a list — the redacted preview, never the full text.
     */
    /**
     * One review the agent handed to a person: why (reason), what the customer raised as the judge read it (points —
     * model words over the redacted review, never the review itself), and whether a draft from before is waiting.
     */
    public record HandoffView(UUID reviewId, String category, String reason, java.time.Instant at, Integer rating,
                              String preview, boolean earlierDraft, java.util.List<String> points,
                              java.util.List<String> unmet) {
    }

    /**
     * <b>What the reply agent handed to a person and nobody has answered yet</b> (review 2026-10-28: 「넘긴 리뷰가 화면에
     * 안 보인다」). Latest run per review; a review the channel shows answered, or one with a draft since, is no longer
     * waiting and leaves the list.
     */
    @org.springframework.web.bind.annotation.GetMapping("/handoffs")
    public java.util.List<HandoffView> handoffs(@AuthenticationPrincipal AuthPrincipal principal) {
        java.util.Map<UUID, HandoffView> latest = new java.util.LinkedHashMap<>();
        java.util.Set<UUID> pendingIds = new java.util.HashSet<>();
        java.util.List<com.sellerops.auth.runtoken.ReplyAgentRun> waiting = new java.util.ArrayList<>(
                runs.findTop100ByOrgIdAndOutcomeOrderByFinishedAtDesc(principal.orgId(), "HANDOFF"));
        // A draft held for review (검토 대기) waits on the day's ceiling, not on a person — but it is still work the
        // seller should see, until a later run checks it (owner, 2026-10-29).
        for (com.sellerops.auth.runtoken.ReplyAgentRun held
                : runs.findTop100ByOrgIdAndOutcomeOrderByFinishedAtDesc(principal.orgId(), "PENDING")) {
            boolean stillLatest = runs.findFirstByOrgIdAndSubjectIdOrderByCreatedAtDesc(principal.orgId(),
                    held.getSubjectId()).map(r -> r.getId().equals(held.getId())).orElse(false);
            if (stillLatest) {
                pendingIds.add(held.getId());
                waiting.add(held);
            }
        }
        for (com.sellerops.auth.runtoken.ReplyAgentRun run : waiting) {
            if (latest.containsKey(run.getSubjectId()) || !"REVIEW".equals(run.getSubjectKind())) {
                continue;
            }
            reviews.findByIdAndOrgId(run.getSubjectId(), principal.orgId())
                    .filter(r -> replies.stillWaitingAfterHandoff(principal.orgId(), r, run.getFinishedAt()))
                    .ifPresent(r -> latest.put(r.getId(), new HandoffView(r.getId(), run.getCategory(),
                            pendingIds.contains(run.getId()) ? "REVIEW_PENDING" : run.getHandoffReason(),
                            run.getFinishedAt(), r.getRating(),
                            com.sellerops.common.VocPreviewSanitizer.sanitize(r.getBody()).text(),
                            replies.hasDraft(r.getId()),
                            run.getPoints() == null || run.getPoints().isBlank() ? java.util.List.of()
                                    : java.util.List.of(run.getPoints().split("\n")),
                            // A run that still failed says what it could not fix, not only that it failed (2026-10-31).
                            "STILL_FAILS".equals(run.getHandoffReason()) && run.getFailedRules() != null
                                    ? ReplyUnmetRules.sentencesFor(java.util.List.of(run.getFailedRules().split(",")))
                                    : java.util.List.of())));
        }
        return java.util.List.copyOf(latest.values());
    }

    /**
     * <b>What the agent's work came to</b> (second outside review, 2026-10-28: cost is not the measure alone — how much is
     * held, and how much the seller approves as written). Counts over the last {@code days}: runs by how they ended and
     * handoffs by reason; then, once per review — its latest saved run, so one approval is never counted for two runs
     * (third review) — whether the seller approved the agent's own text, a version the seller edited, or a version the
     * AI wrote again afterwards, or nothing yet; with the median minutes from that draft to the approval.
     */
    public record OutcomeView(int days, int runs, java.util.Map<String, Integer> byOutcome,
                              java.util.Map<String, Integer> handoffReasons, int saved, int approvedAsWritten,
                              int approvedAfterEdit, int approvedAfterRewrite, int waiting,
                              Long medianMinutesToApproval) {
    }

    @org.springframework.web.bind.annotation.GetMapping("/outcomes")
    public OutcomeView outcomes(@AuthenticationPrincipal AuthPrincipal principal,
                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        int window = Math.max(1, Math.min(days, 90));
        java.util.List<com.sellerops.auth.runtoken.ReplyAgentRun> finished = runs.findByOrgIdAndFinishedAtAfter(
                principal.orgId(), java.time.Instant.now().minus(java.time.Duration.ofDays(window)));
        java.util.Map<String, Integer> byOutcome = new java.util.TreeMap<>();
        java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
        java.util.Map<UUID, com.sellerops.auth.runtoken.ReplyAgentRun> latestSaved = new java.util.LinkedHashMap<>();
        for (com.sellerops.auth.runtoken.ReplyAgentRun run : finished) {
            String outcome = run.getOutcome() == null ? "FAILED" : run.getOutcome();
            byOutcome.merge(outcome, 1, Integer::sum);
            if ("HANDOFF".equals(outcome) && run.getHandoffReason() != null) {
                reasons.merge(run.getHandoffReason(), 1, Integer::sum);
            }
            if ("SAVED".equals(outcome) && run.getDraftVersion() != null) {
                latestSaved.merge(run.getSubjectId(), run,
                        (a, b) -> a.getDraftVersion() >= b.getDraftVersion() ? a : b);
            }
        }
        int asWritten = 0, afterEdit = 0, afterRewrite = 0, waiting = 0;
        java.util.List<Long> minutes = new java.util.ArrayList<>();
        for (com.sellerops.auth.runtoken.ReplyAgentRun run : latestSaved.values()) {
            var approved = replies.approvedVersion(principal.orgId(), run.getSubjectId());
            if (approved.isEmpty() || approved.get().getKey() < run.getDraftVersion()) {
                waiting++;
                continue;
            }
            int version = approved.get().getKey();
            if (version == run.getDraftVersion()) {
                asWritten++;
            } else if ("SELLER".equals(replies.draftAuthorKind(run.getSubjectId(), version).orElse(null))) {
                afterEdit++;
            } else {
                afterRewrite++;
            }
            replies.draftWrittenAt(run.getSubjectId(), run.getDraftVersion()).ifPresent(at -> minutes.add(
                    java.time.Duration.between(at, approved.get().getValue()).toMinutes()));
        }
        java.util.Collections.sort(minutes);
        Long median = minutes.isEmpty() ? null : minutes.get(minutes.size() / 2);
        return new OutcomeView(window, finished.size(), byOutcome, reasons, latestSaved.size(), asWritten, afterEdit,
                afterRewrite, waiting, median);
    }

    public record StartRequest(UUID reviewId) {
    }

    /**
     * How the run ended — SAVED · HANDOFF · FAILED — with its id, category and saved version; never the text.
     * {@code openQuestions}: how many of the customer's questions a saved draft leaves to the seller (eighth review) — a
     * saved draft with any is a partial answer, not a complete one.
     */
    public record StartView(UUID runId, String outcome, String category, String reason, Integer version,
                            int openQuestions) {
        public StartView(UUID runId, String outcome, String category, String reason, Integer version) {
            this(runId, outcome, category, reason, version, 0);
        }
    }

    private int openQuestions(UUID runId) {
        return runs.findById(runId).map(com.sellerops.auth.runtoken.ReplyAgentRun::getOpenQuestions)
                .filter(q -> !q.isBlank()).map(q -> q.split("\n").length).orElse(0);
    }

    @PostMapping("/start")
    public StartView start(@AuthenticationPrincipal AuthPrincipal principal, @RequestBody StartRequest body) {
        if (body == null || body.reviewId() == null) {
            throw ApiException.badRequest("리뷰를 골라 주세요.");
        }
        Review review = reviews.findByIdAndOrgId(body.reviewId(), principal.orgId())
                .orElseThrow(() -> ApiException.notFound("리뷰를 찾을 수 없어요."));
        if (!starter.available(principal.orgId())) {
            throw ApiException.conflict("답글 에이전트를 지금 쓸 수 없어요.");
        }
        if (!replies.maySellerWrite(principal.orgId(), review)) {
            throw ApiException.conflict("이 리뷰에는 지금 새 답글을 준비하지 않아요.");
        }
        return starter.startReviewRun(principal.orgId(), review.getId(), "SELLER", principal.userId())
                .map(s -> s.report()
                        .map(r -> new StartView(s.runId(), r.outcome(), r.category(), r.reason(), r.version(),
                                "SAVED".equals(r.outcome()) ? openQuestions(s.runId()) : 0))
                        .orElse(new StartView(s.runId(), "FAILED", null, "RUNTIME_UNAVAILABLE", null)))
                .orElse(new StartView(null, "FAILED", null, "RUNTIME_UNAVAILABLE", null));
    }
}
