package com.sellerops.attention.reply;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewReplyOutcomeRepository extends JpaRepository<ReviewReplyOutcome, UUID> {

    /**
     * The prior effect of a command id within an org — the idempotency lookup, backed by
     * {@code uq_review_reply_outcome_org_command}. Org-scoped for the same reason the approval audit
     * key is; see {@link ReviewReplyOutcome}.
     */
    Optional<ReviewReplyOutcome> findByOrgIdAndCommandId(UUID orgId, String commandId);

    /**
     * The outcome recorded against a binding, if any — how "is this {@code submissionRef} spent?" is
     * answered. Backed by {@code uq_review_reply_outcome_submission_ref} (one outcome per binding).
     */
    Optional<ReviewReplyOutcome> findBySubmissionRef(String submissionRef);

    /**
     * The latest outcome recorded for a given approved version of a review — how the prep view shows
     * whether the CURRENT approved reply has been reported. Multiple outcomes can exist for one
     * version across retries (abort → re-mint → submit); the most recent is the one that describes
     * where things stand.
     */
    Optional<ReviewReplyOutcome> findTopByOrgIdAndReviewIdAndRecordedVersionOrderByCreatedAtDesc(
            UUID orgId, UUID reviewId, Integer recordedVersion);

    /**
     * Every outcome ever reported for one review, newest first — the Decision Workspace's log.
     *
     * <p>Unversioned on purpose: the prep panel asks about the version that STANDS, and the log asks
     * what the seller has said over the life of the review, including reports against a version they
     * have since replaced. Bounded by the review (a person reports a handful of times, not a stream).
     */
    List<ReviewReplyOutcome> findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(UUID orgId, UUID reviewId);

    /**
     * The operator's most recent report about this review, whichever version it was about — the half
     * {@code ReviewDeliveryTruthReader} merges with the execution record.
     *
     * <p>Unversioned on purpose, like the trail above and unlike the prep panel's lookup: the delivery truth's
     * unversioned question is «has anything ever been reported for this review», which is what an OperationsCase
     * asks before it calls a card closed.
     */
    Optional<ReviewReplyOutcome> findTopByOrgIdAndReviewIdOrderByCreatedAtDesc(UUID orgId, UUID reviewId);

    /**
     * The {@code (reviewId, recordedVersion)} pairs among these reviews the operator reported SUBMITTING — one
     * org-scoped batch per page.
     *
     * <p>Sibling of {@link #findReviewIdsWithReportedSubmission}, and the difference is the version: that one
     * answers «does this review carry a reported submission for the text that STANDS» and joins the approval to
     * find out, which is the worklist's question. This one answers «which exact versions were reported», because
     * the knowledge spine's provenance sentence is about a version of a sentence rather than about a review — an
     * operator who edits and re-approves after posting has new text that was never submitted, and labelling it
     * 「등록했다고 기록」 would attribute a post to words nobody posted.
     *
     * <p>{@code OPERATOR_REPORTED_SUBMITTED} only. {@code SUBMISSION_ABORTED} means "I did not post it".
     */
    @Query("""
            select distinct o.reviewId, o.recordedVersion from ReviewReplyOutcome o
            where o.orgId = :orgId and o.reviewId in :reviewIds
              and o.operatorOutcome
                  = com.sellerops.attention.reply.OperatorOutcome.OPERATOR_REPORTED_SUBMITTED
            """)
    List<Object[]> findReportedSubmissionVersions(@Param("orgId") UUID orgId,
                                                  @Param("reviewIds") Collection<UUID> reviewIds);

    /**
     * Which of these reviews carry a REPORTED submission for the reply version that currently
     * stands — one org-scoped batch query per drill-down page, never a per-row lookup (same shape as
     * {@code ReviewReplyDraftRepository.findReviewIdsWithDraft}).
     *
     * <p>Joined to the APPROVAL so the version is the one that stands: outcomes carry
     * {@code recorded_version} because they describe one approved version, not a review, and an
     * operator who edits and re-approves after posting has new text that was never posted.
     *
     * <p>{@code OPERATOR_REPORTED_SUBMITTED} only. {@code SUBMISSION_ABORTED} means "I did not post
     * it" and must leave the review fully in the worklist.
     *
     * <p>Mirrors {@code ReviewRepository.REPORTED_SUBMISSION_PREDICATE}, which is the same rule
     * expressed where the counting and ordering happen; {@code IngestedReviewReportedSubmissionTest}
     * asserts the two agree on one seed so the number, the order and the marker cannot drift.
     */
    @Query("""
            select distinct o.reviewId from ReviewReplyOutcome o, ReviewReplyApproval ap
            where o.orgId = :orgId and o.reviewId in :reviewIds
              and ap.orgId = o.orgId and ap.reviewId = o.reviewId
              and ap.state = com.sellerops.attention.reply.ReviewReplyApprovalState.APPROVED
              and ap.approvedVersion = o.recordedVersion
              and o.operatorOutcome
                  = com.sellerops.attention.reply.OperatorOutcome.OPERATOR_REPORTED_SUBMITTED
            """)
    List<UUID> findReviewIdsWithReportedSubmission(@Param("orgId") UUID orgId,
                                                   @Param("reviewIds") Collection<UUID> reviewIds);
}
