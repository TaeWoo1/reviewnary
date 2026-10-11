package com.sellerops.operationscase;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.review.Review;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every read is org-scoped in the query itself. An OperationsCase names customers' inquiries and reviews; a read
 * that crossed organisations would put one seller's customers on another seller's screen or into another seller's
 * investigation. {@code OperationsCaseSafetyFenceTest} asserts every declaration here takes an org.
 */
public interface OperationsCaseRepository extends JpaRepository<OperationsCase, UUID> {

    /** Has this exact observed state already been processed — whatever became of the case afterwards? */
    Optional<OperationsCase> findByOrgIdAndSubjectKindAndSubjectIdAndSignature(
            UUID orgId, OperationsSubjectKind subjectKind, UUID subjectId, String signature);

    /** The single open case for a subject — {@code uq_proactive_case_open_subject} guarantees at most one. */
    Optional<OperationsCase> findByOrgIdAndSubjectKindAndSubjectIdAndStatus(
            UUID orgId, OperationsSubjectKind subjectKind, UUID subjectId, OperationsCaseStatus status);

    Optional<OperationsCase> findByIdAndOrgId(UUID id, UUID orgId);

    List<OperationsCase> findByOrgIdAndResponsibilityIdAndStatusOrderByCreatedAtAsc(
            UUID orgId, UUID responsibilityId, OperationsCaseStatus status, Pageable page);

    List<OperationsCase> findByOrgIdAndResponsibilityIdAndCaseKindAndStatusOrderByCreatedAtDesc(
            UUID orgId, UUID responsibilityId, OperationsCaseKind caseKind, OperationsCaseStatus status,
            Pageable page);

    long countByOrgIdAndResponsibilityIdAndCaseKindAndStatus(
            UUID orgId, UUID responsibilityId, OperationsCaseKind caseKind, OperationsCaseStatus status);

    List<OperationsCase> findByOrgIdAndResponsibilityIdAndDispositionAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            UUID orgId, UUID responsibilityId, CaseDisposition disposition, Instant since, Pageable page);

    long countByOrgIdAndResponsibilityIdAndDispositionAndCreatedAtGreaterThanEqual(
            UUID orgId, UUID responsibilityId, CaseDisposition disposition, Instant since);

    /** Everything Reviewnary looked at in a window, whatever it concluded — the 「확인했습니다」 count. */
    long countByOrgIdAndResponsibilityIdAndCaseKindAndCreatedAtGreaterThanEqual(
            UUID orgId, UUID responsibilityId, OperationsCaseKind caseKind, Instant since);

    long countByOrgIdAndResponsibilityIdAndPreparedActionAndCreatedAtGreaterThanEqual(
            UUID orgId, UUID responsibilityId, CasePreparedAction preparedAction, Instant since);

    List<OperationsCase> findByOrgIdAndResponsibilityIdAndStatusAndNotifiedAtIsNull(
            UUID orgId, UUID responsibilityId, OperationsCaseStatus status);

    /** Recent cases of the same kind on the same product — the investigator's «recent similar cases» tool. */
    List<OperationsCase> findTop3ByOrgIdAndSubjectKindAndProductIdAndIdNotOrderByCreatedAtDesc(
            UUID orgId, OperationsSubjectKind subjectKind, UUID productId, UUID excludedCaseId);

    // ── candidates: rows already collected, in this responsibility's scope ────────────────────────────────────

    /**
     * <b>Two dates, and both of them are a fence.</b>
     *
     * <p>{@code createdAt} is when the row reached us and answers «is this this responsibility's, or was it already
     * there when the job was handed over». {@code receivedAt} is when the customer wrote it and answers «is this the
     * work of now». Neither question is the other, and a candidate rule that asks only the first one was floodable
     * by design: a catch-up walk that fills a store's missing past stores months-old rows with today's
     * {@code createdAt}, and every one of them would arrive as 「지금 확인할 일」. That is the shape the proactive
     * bootstrap audit measured in 2026-08-25 (「고객이 4,300일째 기다립니다」) — and {@code receivedAt} alone is no
     * fence either, which the same audit measured, because a backfill run after the boundary pours the same backlog
     * through the other door. So both are asked, against the same instant, which the caller has already clamped to
     * {@link OperationsCaseProcessor#ACQUISITION_REACH}.
     *
     * <p>Nothing is hidden from the seller by this: the inquiry queue and the review ledger still hold every row.
     * What it decides is only whether the <em>scheduled</em> responsibility opens a case about it.
     */
    @Query("""
            select i from Inquiry i
            where i.orgId = :orgId and i.sellerAccountId = :accountId
              and i.dataOrigin = com.sellerops.common.DataOrigin.REAL
              and i.createdAt > :since and i.receivedAt > :since
            order by i.createdAt asc, i.id asc
            """)
    List<Inquiry> inquiryCandidates(@Param("orgId") UUID orgId, @Param("accountId") UUID accountId,
                                    @Param("since") Instant since, Pageable page);

    /** The review half of {@link #inquiryCandidates} — same two dates, same reason. */
    @Query("""
            select r from Review r
            where r.orgId = :orgId and r.channelId = :channelId
              and r.dataOrigin = com.sellerops.common.DataOrigin.REAL
              and r.createdAt > :since and r.receivedAt > :since
            order by r.createdAt asc, r.id asc
            """)
    List<Review> reviewCandidates(@Param("orgId") UUID orgId, @Param("channelId") UUID channelId,
                                  @Param("since") Instant since, Pageable page);

    /**
     * When this responsibility first READ a source completely — the boundary between what was already there when
     * the job was handed over (owned by the existing screens) and what arrived after it (the responsibility's).
     */
    @Query("""
            select min(s.observedAt) from ResponsibilityRunSource s, ResponsibilityRun r
            where r.id = s.runId and r.responsibilityId = :responsibilityId and s.orgId = :orgId
              and s.sellerAccountId = :accountId and s.dataType = :dataType
              and s.completeness in (com.sellerops.responsibility.SourceCompleteness.COMPLETE,
                                     com.sellerops.responsibility.SourceCompleteness.BOUNDED)
            """)
    Instant firstSettledObservation(@Param("orgId") UUID orgId, @Param("responsibilityId") UUID responsibilityId,
                                    @Param("accountId") UUID accountId, @Param("dataType") String dataType);

    /**
     * <b>When the product's own standing read of that store first covered its whole window</b> — the same boundary
     * {@link #firstSettledObservation} states, for the lane that reads a marketplace screen on the seller's own
     * authority instead of inside a responsibility window (Review Auto-Check v1).
     *
     * <p>It exists because the two lanes answer «may this store be read while nobody is watching» from two
     * different places: a responsibility run asks the deployment ({@code AsideMarketplaceAccess}), and the standing
     * lane asks the seller's own product setting ({@code review_auto_check}). The second is the one the product
     * turns on when a seller connects, so in an ordinary deployment it is the ONLY lane reading NAVER reviews — and
     * a case lane that could only see the first simply never opened a case about a review the product itself had
     * just read.
     *
     * <p><b>Only a SCHEDULED read counts.</b> An operator's press is a person looking at their own screen, not a
     * standing promise to keep that store current, and a boundary drawn at a press would make months of rows
     * «new since» an afternoon someone was curious. <b>Only a BOUNDED delivery counts</b>, for the reason
     * {@link #firstSettledObservation} takes only settled completeness: a PARTIAL page could not rule out a gap
     * behind it, so nothing can be «new since» it. And {@code settledAt} is used rather than the claim or the
     * dispatch because a delivery is recorded while the job is still CLAIMED — so this instant is after that
     * read's own rows were stored, which is what keeps the boundary read from opening cases about its own reading.
     */
    @Query("""
            select min(j.settledAt) from ScheduledAsideJob j
            where j.orgId = :orgId and j.sellerAccountId = :accountId and j.recipe = :recipe
              and j.trigger = com.sellerops.responsibility.aside.AsideTrigger.SCHEDULED
              and j.outcome = com.sellerops.responsibility.aside.AsideJobOutcome.OBSERVED
              and j.identityVerdict = com.sellerops.responsibility.IdentityVerdict.MATCH
              and j.deliveryCompleteness = com.sellerops.responsibility.SourceCompleteness.BOUNDED
            """)
    Instant firstSettledStandingRead(@Param("orgId") UUID orgId, @Param("accountId") UUID accountId,
                                     @Param("recipe") com.sellerops.responsibility.aside.AsideRecipe recipe);

    // ── canonical truth the reconciler and the investigator read ────────────────────────────────────────────

    @Query("""
            select count(t) > 0 from ReviewTriage t
            where t.orgId = :orgId and t.reviewId = :reviewId and t.decidedAt >= :since
            """)
    boolean reviewDecidedSince(@Param("orgId") UUID orgId, @Param("reviewId") UUID reviewId,
                               @Param("since") Instant since);

    /**
     * Did the connector's answered-elsewhere reconciliation complete this work item? That path writes an audit row whose
     * actor is the ingest connector and whose phase moves to COMPLETED ({@code InquiryWorkItemWriter}); a COMPLETED
     * phase without one came from the seller's own workflow.
     */
    @Query("""
            select count(a) > 0 from InquiryWorkItemAudit a
            where a.orgId = :orgId and a.workItemId = :workItemId and a.actor = :actor
              and a.phaseTo = com.sellerops.inquiry.workitem.InquiryWorkItemPhase.COMPLETED
            """)
    boolean completedByActor(@Param("orgId") UUID orgId, @Param("workItemId") UUID workItemId,
                             @Param("actor") String actor);

    @Query("""
            select t.disposition, count(t) from ReviewTriage t, Review r
            where r.id = t.reviewId and t.orgId = :orgId and r.orgId = :orgId and r.productId = :productId
            group by t.disposition
            """)
    List<Object[]> reviewDecisionsForProduct(@Param("orgId") UUID orgId, @Param("productId") UUID productId);

    @Query("""
            select d.authorKind, count(d) from InquiryReplyDraft d, InquiryWorkItem w, Inquiry i
            where d.workItemId = w.id and w.inquiryId = i.id
              and d.orgId = :orgId and i.orgId = :orgId and i.productId = :productId
            group by d.authorKind
            """)
    List<Object[]> inquiryDraftAuthorsForProduct(@Param("orgId") UUID orgId, @Param("productId") UUID productId);

    /**
     * The individual review judgements this seller recorded on a product, newest first — the decisions themselves,
     * where {@link #reviewDecisionsForProduct} only counts them. Evidence for the next investigation; nothing reads
     * these to change a threshold or a policy.
     */
    @Query("""
            select t.disposition, t.decidedAt from ReviewTriage t, Review r
            where r.id = t.reviewId and t.orgId = :orgId and r.orgId = :orgId and r.productId = :productId
            order by t.decidedAt desc
            """)
    List<Object[]> recentReviewDecisionsForProduct(@Param("orgId") UUID orgId, @Param("productId") UUID productId,
                                                   Pageable page);

    /**
     * Where the seller disagreed with the system's own judgement and said so, newest first. Only
     * {@code STANDING} corrections are read: a withdrawn one is a trail entry, not this company's word.
     */
    @Query("""
            select c.shownTier, c.correctedTier, c.correctedAt from TriageCorrection c, Review r
            where r.id = c.reviewId and c.orgId = :orgId and r.orgId = :orgId and r.productId = :productId
              and c.state = com.sellerops.review.triage.feedback.SellerCorrectionState.STANDING
            order by c.correctedAt desc
            """)
    List<Object[]> recentTriageCorrectionsForProduct(@Param("orgId") UUID orgId, @Param("productId") UUID productId,
                                                     Pageable page);

    /**
     * Open REVIEW cases whose review is evidence for one {@code aspect:problem} — the only cases a policy change
     * may re-decide (Seller-declared Operations Policy v1).
     *
     * <p>Joined through the issue memory the extraction after every ingest already wrote, on the key
     * {@code review_issues} is indexed by, and bounded by status: a closed case is not reopened by a rule change.
     * Dismissed issues are excluded for the same reason {@code signatureKeysOfReview} excludes them — 「중요하지
     * 않음」 is the seller saying this is not a problem they are managing.
     */
    @Query("""
            select c from OperationsCase c, ReviewIssueEvidence e, ReviewIssue i
            where c.orgId = :orgId and c.status = :status
              and c.subjectKind = com.sellerops.operationscase.OperationsSubjectKind.REVIEW
              and e.orgId = :orgId and e.reviewId = c.subjectId
              and i.id = e.issueId and i.orgId = :orgId and i.dismissed = false
              and i.signatureKey = :signatureKey
            order by c.createdAt asc
            """)
    List<OperationsCase> findOpenReviewCasesForIssueKey(@Param("orgId") UUID orgId,
                                                        @Param("status") OperationsCaseStatus status,
                                                        @Param("signatureKey") String signatureKey);

    /**
     * The same, narrowed to one product — what a PRODUCT-scope policy reaches and nothing more.
     *
     * <p>Narrowed in SQL rather than filtered in Java so a product rule on a busy key cannot read a page of other
     * products' cards to discard them. The case's own {@code product_id} is the binding the overlay also tests.
     */
    @Query("""
            select c from OperationsCase c, ReviewIssueEvidence e, ReviewIssue i
            where c.orgId = :orgId and c.status = :status
              and c.subjectKind = com.sellerops.operationscase.OperationsSubjectKind.REVIEW
              and c.productId = :productId
              and e.orgId = :orgId and e.reviewId = c.subjectId
              and i.id = e.issueId and i.orgId = :orgId and i.dismissed = false
              and i.signatureKey = :signatureKey
            order by c.createdAt asc
            """)
    List<OperationsCase> findOpenReviewCasesForIssueKeyAndProduct(@Param("orgId") UUID orgId,
                                                                  @Param("status") OperationsCaseStatus status,
                                                                  @Param("signatureKey") String signatureKey,
                                                                  @Param("productId") UUID productId);

    @Query("""
            select c from OperationsCase c
            where c.orgId = :orgId and c.responsibilityId in :responsibilityIds and c.status = :status
            """)
    List<OperationsCase> findOpenForResponsibilities(@Param("orgId") UUID orgId,
                                                     @Param("responsibilityIds") Collection<UUID> responsibilityIds,
                                                     @Param("status") OperationsCaseStatus status);
}
