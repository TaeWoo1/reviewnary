package com.sellerops.review.publish;

import com.sellerops.attention.reply.OperatorOutcome;
import com.sellerops.attention.reply.ReviewReplyOutcome;
import com.sellerops.attention.reply.ReviewReplyOutcomeRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <b>What happened to an approved review reply — in the two vocabularies that own it.</b>
 *
 * <p>A reader outside this lane can watch a seller approve a reply and still learn nothing about whether anything
 * reached the channel, because the answer lives in two append-only tables that do not know about each other:
 * {@code review_reply_execution} (V84 — what reviewnary DID and what it could CONFIRM, both lanes) and
 * {@code review_reply_outcome} (V20 — what the OPERATOR reported doing at the guided barrier, verification
 * permanently {@code UNVERIFIED}). Every consumer before this read one of the two and silently treated it as the
 * whole truth: the Decision Workspace's log and the knowledge spine read only the operator's self-report, so a
 * Cafe24 reply reviewnary posted itself and verified by hash did not appear in either.
 *
 * <p>This hands the two out as one read-only statement so another package can <em>quote</em> them instead of
 * minting a second vocabulary for «sent» — the same seam {@code AnswerDeliveryTruthReader} is for the inquiry
 * lane, and for the same reason: a case may say the seller acted without ever claiming delivery it did not
 * observe.
 *
 * <p><b>No third row.</b> The merge happens at read time and is never stored. Both sources stay append-only and
 * untouched, and {@code review.replyState} — a marketplace observation written only by ingestion — is neither read
 * as an execution nor written by one.
 *
 * <p><b>Nothing here approves, mints, dispatches, re-sends or calls a channel.</b> It is indexed reads of rows
 * other code wrote, scoped to the caller's organisation, and it holds no setter for any of them.
 */
@Component
public class ReviewDeliveryTruthReader {

    /**
     * The two records about one approved version, side by side.
     *
     * <p>Every token that leaves is one the lane already chose — {@link ReviewExecutionLane},
     * {@link ReviewExecutionStatus}, {@link ReviewExecutionVerification}, {@link OperatorOutcome} — carried as
     * names rather than enums so a consumer in another package can record them verbatim without importing this
     * lane's vocabulary and then being tempted to branch on it.
     *
     * @param reviewId        the review both halves describe
     * @param approvedVersion the draft version they describe, or null when neither half named one
     * @param lane            {@link ReviewExecutionLane}; null when only the operator reported
     * @param status          {@link ReviewExecutionStatus}; null when only the operator reported
     * @param verification    {@link ReviewExecutionVerification}; null when nothing was confirmed either way
     * @param reason          why a {@code REFUSED} execution was refused; null otherwise
     * @param operatorOutcome {@link OperatorOutcome}; null when the operator reported nothing
     * @param observedAt      when the latest of the two was observed, or null when neither recorded a time
     */
    public record ReviewDeliveryTruth(UUID reviewId, Integer approvedVersion, String lane, String status,
                                      String verification, String reason, String operatorOutcome,
                                      Instant observedAt) {

        /**
         * Whether the record shows the seller's approved reply actually left for the channel.
         *
         * <p>Asked here rather than by the caller because the answer is a statement about THIS lane's vocabulary,
         * and three of its values look like progress without being it:
         *
         * <ul>
         *   <li>{@code REFUSED} — a gate stopped it or the channel rejected the body. Nothing left.</li>
         *   <li>{@code COMPOSER_FILLED} — the approved body is sitting in the seller-center composer. The seller
         *   has not pressed submit, and the whole point of the guided lane is that only they can.</li>
         *   <li>{@code SUBMISSION_ABORTED} — the operator reports they decided not to post. A deliberate, benign
         *   end, and the one outcome that must leave the review fully in the worklist.</li>
         * </ul>
         *
         * <p>True therefore means: the API lane POSTed (or may have — {@code DELIVERY_UNKNOWN} had no second
         * attempt and is not a licence to send again), the collector saw the seller submit, or the operator
         * reported submitting. It is deliberately NOT a claim that the channel holds the approved text —
         * {@link #verifiedDelivery()} is the only thing that claims that.
         */
        public boolean sellerActed() {
            return sentByApi() || submissionObserved()
                    || OperatorOutcome.OPERATOR_REPORTED_SUBMITTED.name().equals(operatorOutcome);
        }

        /**
         * Whether the channel was proven to hold the approved text, hash for hash.
         *
         * <p>Reachable on the API lane alone, and {@link ReviewExecutionVerification#memoryEligible()} is where
         * that is decided — this does not restate the predicate, it asks it. A NAVER reply cannot get here: the
         * export carries no reply body, so the guided ceiling is «a reply exists and nothing says it is ours».
         * That ceiling is exactly why Answer Memory may not learn from the guided lane, however confident the
         * seller's own report was: a memory is a record of what the company SAID, and only a hash match proves
         * what that was.
         */
        public boolean verifiedDelivery() {
            ReviewExecutionVerification parsed = parse(verification);
            return parsed != null && parsed.memoryEligible();
        }

        /** The API lane handed the body to the channel, or may have. */
        private boolean sentByApi() {
            return ReviewExecutionLane.API.name().equals(lane)
                    && (ReviewExecutionStatus.POSTED.name().equals(status)
                        || ReviewExecutionStatus.DELIVERY_UNKNOWN.name().equals(status));
        }

        /** The collector saw the seller submit, or a later channel read showed a reply where it watched. */
        private boolean submissionObserved() {
            ReviewExecutionVerification parsed = parse(verification);
            return parsed == ReviewExecutionVerification.SELLER_SUBMISSION_OBSERVED
                    || parsed == ReviewExecutionVerification.SUBMISSION_OBSERVED_CONTENT_UNVERIFIED;
        }

        private static ReviewExecutionVerification parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (ReviewExecutionVerification value : ReviewExecutionVerification.values()) {
                if (value.name().equals(raw)) {
                    return value;
                }
            }
            return null;
        }

        /**
         * The truth as one token sequence, for an append-only trail that wants to quote it.
         *
         * <p>Shaped like {@code AnswerDeliveryTruthReader}'s {@code delivery()} clause and for the same reason: a
         * consumer records what this lane observed verbatim instead of translating it into a word of its own
         * vocabulary. Empty-string segments are left out so a guided observation does not carry an {@code
         * api=null} it never had.
         */
        public String quoted() {
            StringBuilder out = new StringBuilder();
            append(out, "lane", lane);
            append(out, "status", status);
            append(out, "verification", verification);
            append(out, "reason", reason);
            append(out, "operatorOutcome", operatorOutcome);
            return out.toString();
        }

        private static void append(StringBuilder out, String key, String value) {
            if (value != null) {
                out.append(';').append(key).append('=').append(value);
            }
        }
    }

    private final ReviewReplyExecutionRepository executions;
    private final ReviewReplyOutcomeRepository outcomes;

    public ReviewDeliveryTruthReader(ReviewReplyExecutionRepository executions,
                                     ReviewReplyOutcomeRepository outcomes) {
        this.executions = executions;
        this.outcomes = outcomes;
    }

    /**
     * Where one review's reply stands, across both halves.
     *
     * <p>Empty when neither half ever recorded anything — the seller approved and copied by hand, or never got
     * that far, and «no row» is the honest answer rather than a delivery state nobody wrote. The two halves are
     * read independently and merged: an API execution with no operator report is the common shape on Cafe24, an
     * operator report with no execution is every guided submission recorded before V84, and a guided run produces
     * both.
     *
     * <p><b>Latest-wins per half, not per field.</b> Each table is append-only and its newest row for the review
     * is where that half stands ({@code ReviewReplyExecution}'s class note says so in as many words). Merging
     * field-by-field across generations would assemble a statement neither row ever made.
     */
    public Optional<ReviewDeliveryTruth> observe(UUID orgId, UUID reviewId) {
        Optional<ReviewReplyExecution> execution = executions
                .findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId)
                .filter(row -> orgId.equals(row.getOrgId()));
        Optional<ReviewReplyOutcome> outcome = outcomes
                .findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId)
                .filter(row -> orgId.equals(row.getOrgId()));
        if (execution.isEmpty() && outcome.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merge(reviewId, execution.orElse(null), outcome.orElse(null)));
    }

    /**
     * The same statement for one specific approved version.
     *
     * <p>The version matters wherever the question is about TEXT rather than about the review: an operator who
     * edits and re-approves after posting has a new version that was never sent, and Answer Memory must learn the
     * sentence that was actually delivered rather than the latest one on the row. {@link #observe} answers «has
     * this review been dealt with»; this answers «was THIS text delivered».
     */
    public Optional<ReviewDeliveryTruth> observeVersion(UUID orgId, UUID reviewId, Integer approvedVersion) {
        if (approvedVersion == null) {
            return Optional.empty();
        }
        Optional<ReviewReplyExecution> execution = executions
                .findTopByOrgIdAndReviewIdAndApprovedVersionOrderByCreatedAtDesc(orgId, reviewId, approvedVersion)
                .filter(row -> orgId.equals(row.getOrgId()));
        Optional<ReviewReplyOutcome> outcome = outcomes
                .findTopByOrgIdAndReviewIdAndRecordedVersionOrderByCreatedAtDesc(orgId, reviewId, approvedVersion)
                .filter(row -> orgId.equals(row.getOrgId()));
        if (execution.isEmpty() && outcome.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merge(reviewId, execution.orElse(null), outcome.orElse(null)));
    }

    /**
     * Every execution record for one review, newest first — the half the Decision Workspace's log was missing.
     *
     * <p>Execution rows only: the log already reads the operator's reports from {@code review_reply_outcome}
     * directly and showing each twice would make one act look like two. Unversioned for the same reason that
     * trail is — the log asks what has happened over the life of the review, including against a version the
     * seller has since replaced.
     */
    public List<ReviewDeliveryTruth> executionTrail(UUID orgId, UUID reviewId) {
        return executions.findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId).stream()
                .filter(row -> orgId.equals(row.getOrgId()))
                .map(row -> merge(reviewId, row, null))
                .toList();
    }

    /**
     * Which {@code reviewId:version} pairs among these reviews the record says were delivered — one org-scoped
     * batch per page, never a per-row lookup.
     *
     * <p>This is the shape the knowledge spine needs and the shape it previously had to build itself from the
     * operator-report table alone ({@code ReviewReplyAdapter.reportedVersions}), which is why an approved reply
     * reviewnary posted and verified read as merely «승인한 답글». Both halves are unioned here: an operator who
     * reported submitting and an execution that left for the channel are two records of the same kind of fact, and
     * a consumer asking «was this sent» should not have to know which lane recorded it.
     *
     * <p>Delivered means {@link ReviewDeliveryTruth#sellerActed()}, not {@code verifiedDelivery()} — this answers
     * the provenance sentence a seller reads, and the seller's own report is good enough for «등록했다고 기록».
     * What may become Answer Memory is a stricter question and is asked with the stricter predicate.
     */
    public Set<String> deliveredVersions(UUID orgId, Collection<UUID> reviewIds) {
        if (reviewIds == null || reviewIds.isEmpty()) {
            return Set.of();
        }
        Set<String> delivered = new HashSet<>();
        for (Object[] row : outcomes.findReportedSubmissionVersions(orgId, reviewIds)) {
            delivered.add(row[0] + ":" + row[1]);
        }
        for (ReviewReplyExecution row : executions.findAllByOrgIdAndReviewIdIn(orgId, reviewIds)) {
            ReviewDeliveryTruth truth = merge(row.getReviewId(), row, null);
            if (truth.sellerActed()) {
                delivered.add(row.getReviewId() + ":" + row.getApprovedVersion());
            }
        }
        return delivered;
    }

    /**
     * The merge, and the only place it happens.
     *
     * <p>The version is taken from the execution when there is one and from the operator's report otherwise: the
     * execution is reviewnary's own record and binds the approved head it was authorized against, while a report
     * names the version the operator was looking at. They agree whenever both exist, because the guided barrier
     * re-confirms the approved head before minting the ref either of them can be recorded under.
     */
    private static ReviewDeliveryTruth merge(UUID reviewId, ReviewReplyExecution execution,
                                             ReviewReplyOutcome outcome) {
        Integer version = execution != null ? execution.getApprovedVersion()
                : outcome != null ? outcome.getRecordedVersion() : null;
        Instant observedAt = latest(
                execution == null ? null
                        : execution.getObservedAt() != null ? execution.getObservedAt() : execution.getCreatedAt(),
                outcome == null ? null : outcome.getCreatedAt());
        return new ReviewDeliveryTruth(reviewId, version,
                execution == null || execution.getLane() == null ? null : execution.getLane().name(),
                execution == null || execution.getStatus() == null ? null : execution.getStatus().name(),
                execution == null || execution.getVerification() == null ? null
                        : execution.getVerification().name(),
                execution == null || execution.getReason() == null ? null : execution.getReason().name(),
                outcome == null || outcome.getOperatorOutcome() == null ? null
                        : outcome.getOperatorOutcome().name(),
                observedAt);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
