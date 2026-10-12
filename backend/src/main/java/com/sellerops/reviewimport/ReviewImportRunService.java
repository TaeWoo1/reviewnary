package com.sellerops.reviewimport;

import com.sellerops.collect.runtime.CollectionMethod;
import com.sellerops.common.ApiException;
import com.sellerops.connector.FileUploadConnector;
import com.sellerops.ingest.IngestResult;
import com.sellerops.ingest.UploadType;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one segment's export through the EXISTING upload/ingest path and records the attempt. Reuses
 * {@link FileUploadConnector} unchanged (parse → map → dedup-safe ingest → sync_job), so overlap-safe
 * dedup (on {@code external_id} = 리뷰글번호) and reply-state/timestamp preservation carry over exactly.
 *
 * <p>Each call is one attempt: it links its own sync_job, so retry history is preserved. The
 * scope-confirmation gate is enforced here — the export scope must have been established to match this
 * segment before the file is accepted — and {@link ScopeEvidence} records which way it was established.
 * A guided Action Window run may read the selected range back off the live controls; the manual fallback
 * cannot, so it is an operator attestation. Neither is described as the other.
 *
 * <p>Execution vs coverage stay separate: a hard ingest failure marks the attempt and the segment
 * {@link SegmentExecutionState#FAILED} with coverage untouched; a success (a valid EMPTY included) marks
 * {@link SegmentExecutionState#COMPLETED} + {@link SegmentCoverageState#COVERED}. {@code coveredRows} is
 * new + duplicate (rows now accounted for).
 *
 * <p>Completeness is a measurement, not a presumption (2026-10-13). When the guided run read the
 * marketplace screen's row total for the exported range ({@code expectedRows}), the file is reconciled
 * against it: equal, with no failed row, sets {@code rowsReconciled}; anything else means this export did not
 * bring the whole range, so the attempt is FAILED and the segment is not COVERED — the operator splits it and
 * exports the shorter windows. The rows that did arrive stay (dedup makes the re-export overlap-safe). With no
 * total read (the manual fallback, or a read that found none), {@code rowsReconciled} stays false: "scope
 * exported successfully" is not "all expected rows reconciled".
 */
@Service
public class ReviewImportRunService {

    private final ReviewImportPlanRepository plans;
    private final ReviewImportSegmentRepository segments;
    private final ReviewImportSegmentAttemptRepository attempts;
    private final FileUploadConnector uploadConnector;
    private final ReviewImportPlanService planService;
    private final ApplicationEventPublisher events;

    public ReviewImportRunService(ReviewImportPlanRepository plans,
                                  ReviewImportSegmentRepository segments,
                                  ReviewImportSegmentAttemptRepository attempts,
                                  FileUploadConnector uploadConnector,
                                  ReviewImportPlanService planService,
                                  ApplicationEventPublisher events) {
        this.plans = plans;
        this.segments = segments;
        this.attempts = attempts;
        this.uploadConnector = uploadConnector;
        this.planService = planService;
        this.events = events;
    }

    /**
     * Import one segment's exported file via the MANUAL fallback (the seller picked the file themselves).
     * That path has no read-back of what was exported, so its evidence is by definition
     * {@link ScopeEvidence#OPERATOR_CONFIRMED} — the operator's attestation, never a machine check.
     */
    @Transactional
    public ReviewImportSegmentAttempt importSegment(UUID orgId, UUID segmentId, boolean scopeConfirmed,
                                                    String filename, InputStream data) {
        return importSegment(orgId, segmentId, scopeConfirmed, ScopeEvidence.OPERATOR_CONFIRMED, filename, data);
    }

    /**
     * Import one segment's exported file. {@code scopeConfirmed} MUST be true — the export scope was
     * established to match this segment — and {@code scopeEvidence} records HOW (a guided run that read
     * the range back off the page, versus an operator attestation). Returns the recorded attempt
     * (SUCCEEDED or FAILED); a FAILED attempt is a normal outcome the operator can retry, not an API error.
     */
    @Transactional
    public ReviewImportSegmentAttempt importSegment(UUID orgId, UUID segmentId, boolean scopeConfirmed,
                                                    ScopeEvidence scopeEvidence,
                                                    String filename, InputStream data) {
        return importSegment(orgId, segmentId, scopeConfirmed, scopeEvidence, null, filename, data);
    }

    /**
     * As above, with the row total the marketplace screen stated for the exported range ({@code null} when
     * it was not read). A total that the file does not match fails the attempt instead of covering the
     * segment — see the class note.
     */
    @Transactional
    public ReviewImportSegmentAttempt importSegment(UUID orgId, UUID segmentId, boolean scopeConfirmed,
                                                    ScopeEvidence scopeEvidence, Integer expectedRows,
                                                    String filename, InputStream data) {
        if (expectedRows != null && expectedRows < 0) {
            throw ApiException.badRequest("화면 총 건수가 올바르지 않습니다.");
        }
        ReviewImportSegment segment = segments.findByIdAndOrgId(segmentId, orgId)
                .orElseThrow(() -> ApiException.notFound("구간을 찾을 수 없습니다."));
        if (segment.isSuperseded()) {
            throw ApiException.conflict("분할되어 대체된 구간에는 가져오기를 실행할 수 없습니다.");
        }
        if (segment.getExecutionState() == SegmentExecutionState.ACTIVE) {
            throw ApiException.conflict("이미 진행 중인 구간입니다.");
        }
        if (!scopeConfirmed) {
            throw ApiException.badRequest("내보내기 범위가 이 구간과 일치하는지 먼저 확인해 주세요.");
        }
        // Org-scoped, and it has to be: the ingest below writes into THIS plan's channel. An unscoped
        // findById here would have let an org-scoped segment resolve a plan belonging to another org and
        // land the seller's exported reviews on that org's channel. See ReviewImportIdentityFence.
        ReviewImportPlan plan = plans.findByIdAndOrgId(segment.getPlanId(), orgId)
                .orElseThrow(() -> ApiException.notFound("가져오기 계획을 찾을 수 없습니다."));

        ReviewImportSegmentAttempt attempt = new ReviewImportSegmentAttempt();
        attempt.setSegmentId(segmentId);
        attempt.setOrgId(orgId);
        attempt.setAttemptNo(attempts.nextAttemptNo(segmentId));
        attempt.setScopeConfirmed(true);
        attempt.setScopeEvidence(scopeEvidence);
        attempt.setExpectedRows(expectedRows);
        attempt.setResult(SegmentAttemptResult.ACTIVE);
        attempt.setStartedAt(Instant.now());
        attempt = attempts.save(attempt);

        segment.setExecutionState(SegmentExecutionState.ACTIVE);
        segments.save(segment);

        IngestResult result = uploadConnector.ingest(orgId, plan.getChannelId(), UploadType.REVIEW,
                filename, data, CollectionMethod.SELLER_CENTER_EXPORT);

        attempt.setSyncJobId(result.syncJobId());
        attempt.setRowsNew(result.successRows());
        attempt.setRowsDuplicate(result.skippedRows());
        attempt.setRowsFailed(result.failedRows());
        attempt.setFinishedAt(Instant.now());

        int accounted = result.successRows() + result.skippedRows();
        boolean hardFailure = "FAILED".equals(result.status());
        boolean shortOfTotal = !hardFailure && expectedRows != null
                && (accounted != expectedRows || result.failedRows() > 0);
        if (hardFailure) {
            attempt.setResult(SegmentAttemptResult.FAILED);
            attempt.setErrorMessage(result.errorMessage());
            segment.setExecutionState(SegmentExecutionState.FAILED);
            // coverage stays UNVERIFIED — a failed attempt is not a coverage conclusion.
        } else if (shortOfTotal) {
            attempt.setResult(SegmentAttemptResult.FAILED);
            attempt.setErrorMessage("받은 파일이 " + accounted + "건, 화면 총 건수는 " + expectedRows
                    + "건입니다. 이 구간을 더 짧게 나눠 다시 받아 주세요.");
            segment.setExecutionState(SegmentExecutionState.FAILED);
            // coverage stays UNVERIFIED — the window was not brought whole, whatever did arrive.
        } else {
            attempt.setResult(SegmentAttemptResult.SUCCEEDED);
            segment.setExecutionState(SegmentExecutionState.COMPLETED);
            segment.setCoverageState(SegmentCoverageState.COVERED); // a valid empty (0 rows) is still COVERED
            segment.setCoveredRows(accounted);
            segment.setRowsReconciled(expectedRows != null);
        }
        attempts.save(attempt);
        segments.save(segment);
        planService.recomputePlanStatus(plan.getId());

        // A COVERED segment (or a short one that still brought new rows) means new reviews landed; let downstream analysis refresh off them.
        // Fired only on success, AFTER_COMMIT-consumed and best-effort, so it never affects this ingest.
        if (attempt.getResult() == SegmentAttemptResult.SUCCEEDED || (shortOfTotal && result.successRows() > 0)) {
            events.publishEvent(
                    new ReviewSegmentIngestedEvent(orgId, plan.getChannelId(), ReviewImportCalendar.today()));
        }
        return attempt;
    }

    /**
     * Conclude that a segment's range cannot be covered (earlier than the earliest date NAVER lets the
     * seller select, per the live UI). Coverage → MISSING and execution → COMPLETED: this is a terminal
     * operator CONCLUSION (no more attempts), not a failed attempt, and it reads to the seller as
     * "가져올 수 없는 기간". The segment stays reachable and its attempt history (if any) is preserved.
     */
    @Transactional
    public ReviewImportSegment markMissing(UUID orgId, UUID segmentId) {
        ReviewImportSegment segment = segments.findByIdAndOrgId(segmentId, orgId)
                .orElseThrow(() -> ApiException.notFound("구간을 찾을 수 없습니다."));
        if (segment.isSuperseded()) {
            throw ApiException.conflict("분할되어 대체된 구간입니다.");
        }
        segment.setCoverageState(SegmentCoverageState.MISSING);
        segment.setExecutionState(SegmentExecutionState.COMPLETED);
        segments.save(segment);
        planService.recomputePlanStatus(segment.getPlanId());
        return segment;
    }

    /** End a plan with remaining work; it stays reachable. */
    @Transactional
    public ReviewImportPlan abandonPlan(UUID orgId, UUID planId) {
        ReviewImportPlan plan = plans.findByIdAndOrgId(planId, orgId)
                .orElseThrow(() -> ApiException.notFound("가져오기 계획을 찾을 수 없습니다."));
        plan.setStatus(ReviewImportPlanStatus.ABANDONED);
        return plans.save(plan);
    }
}
