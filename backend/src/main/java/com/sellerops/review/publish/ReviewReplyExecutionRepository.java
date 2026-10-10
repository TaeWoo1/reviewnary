package com.sellerops.review.publish;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewReplyExecutionRepository extends JpaRepository<ReviewReplyExecution, UUID> {

    /** The idempotency lookup, backed by {@code uq_review_reply_execution_org_command}. */
    Optional<ReviewReplyExecution> findByOrgIdAndCommandId(UUID orgId, String commandId);

    /** Where things stand for one approved version of a review — the most recent row. */
    Optional<ReviewReplyExecution> findTopByOrgIdAndReviewIdAndApprovedVersionOrderByCreatedAtDesc(
            UUID orgId, UUID reviewId, Integer approvedVersion);

    /**
     * Whether the API lane already sent (or may have sent) a reply for this review — the
     * per-review double-post guard, backed by {@code uq_review_reply_execution_api_sent}.
     */
    boolean existsByOrgIdAndReviewIdAndLaneAndStatusIn(UUID orgId, UUID reviewId, ReviewExecutionLane lane,
                                                        java.util.Collection<ReviewExecutionStatus> statuses);

    /** The most recent guided observation for one binding. */
    Optional<ReviewReplyExecution> findTopByOrgIdAndSubmissionRefOrderByCreatedAtDesc(UUID orgId,
                                                                                       String submissionRef);

    /**
     * Where this review's reply stands regardless of which version it was — the delivery truth's own lookup.
     *
     * <p>Unversioned, unlike the method above it: that one answers «what happened to the text that stands», and
     * this one answers «has anything ever been sent for this review», which is the question an OperationsCase
     * asks. Append-only table, so the newest row is where the lane stands.
     */
    Optional<ReviewReplyExecution> findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(UUID orgId, UUID reviewId);

    /**
     * Every execution record for one review, newest first — the Decision Workspace's log.
     *
     * <p>Bounded by the review: a reply is executed once per lane and observed a handful of times on the way
     * (composer filled, submission seen, read-back promoted), not streamed.
     */
    List<ReviewReplyExecution> findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(UUID orgId, UUID reviewId);

    /**
     * Every execution record for a page of reviews — one org-scoped batch, never a per-row lookup.
     *
     * <p>The spine's provenance question ({@code ReviewDeliveryTruthReader.deliveredVersions}) is asked for up to
     * 200 reviews at once, which is exactly the shape that must not become 200 queries. Rows rather than ids
     * because the predicate for «was this sent» is this lane's and lives on the truth record, not in SQL: a
     * {@code REFUSED} row and a {@code COMPOSER_FILLED} row are both rows, and neither one sent anything.
     */
    List<ReviewReplyExecution> findAllByOrgIdAndReviewIdIn(UUID orgId, Collection<UUID> reviewIds);
}
