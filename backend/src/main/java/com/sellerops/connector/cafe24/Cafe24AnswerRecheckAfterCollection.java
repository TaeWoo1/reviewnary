package com.sellerops.connector.cafe24;

import com.sellerops.collect.ScheduledCollectionFollowUp;
import com.sellerops.connector.DataType;
import com.sellerops.sync.SyncJob;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The open-inquiry recheck, at the end of a scheduled collection</b> (2026-11-08, inquiry_operational_truth_v1 §10.3).
 * After a scheduled Cafe24 INQUIRY run, one {@link Cafe24AnswerRecheckRunner#run recheck} over the same seller account —
 * within its daily budget, under its lock, past its account wait, leaving out what was looked at successfully today.
 *
 * <p><b>Only after a clean run:</b> trigger {@code SCHEDULED}, the Cafe24 API connector, data type {@code INQUIRY},
 * status {@code SUCCESS} and not rate limited. A {@code PARTIAL}, {@code FAILED} or rate-limited collection means the
 * account is in trouble right now — the recheck does not add requests to it, and the next clean collection brings it
 * back. So the recheck never runs before the new-inquiry sweep of its own cycle has finished.
 *
 * <p><b>Clean includes the comment look</b> (2026-11-09). A collection can end {@code SUCCESS} by its articles while the
 * comment look beside them was cut off by the account or the channel — a 401/403, a 429, a 503. Its articles and the
 * outcomes it did learn are kept; the recheck does not follow it ({@link SyncJob#getAccountSideStop()}). And a job whose
 * comment look is not known — not the instance a run in this process just returned — does not call for one either:
 * unknown is not clean. One article's own trouble (its read failed, its author unproven) does not hold the recheck back.
 *
 * <p><b>Off unless set:</b> this bean exists only with {@code sellerops.inquiry.recheck.auto-enabled=true}
 * ({@link Cafe24AnswerRecheckConfiguration}).
 */
public final class Cafe24AnswerRecheckAfterCollection implements ScheduledCollectionFollowUp {

    private static final Logger log = LoggerFactory.getLogger(Cafe24AnswerRecheckAfterCollection.class);

    static final String SCHEDULED = "SCHEDULED";

    private final Cafe24AnswerRecheckRunner runner;

    public Cafe24AnswerRecheckAfterCollection(Cafe24AnswerRecheckRunner runner) {
        this.runner = runner;
    }

    @Override
    public void afterScheduledCollection(SyncJob finished) {
        recheckAfter(finished);
    }

    /** The recheck this finished job calls for, run now; empty when it calls for none. */
    public Optional<Cafe24AnswerRecheckRunner.Outcome> recheckAfter(SyncJob finished) {
        if (!callsForRecheck(finished)) {
            if (cleanApartFromTheCommentLook(finished)) {
                // Counts and names only — never an article, a token or a mall.
                log.info("카페24 열린 문의 재확인 생략: 수집 중 댓글 확인이 계정·채널 쪽에서 멈춤 사유={}",
                        finished.isAccountSideKnown() ? finished.getAccountSideStop() : "UNKNOWN");
            }
            return Optional.empty();
        }
        return Optional.of(runner.run(finished.getOrgId(), finished.getSellerAccountId(), finished.getChannelId()));
    }

    static boolean callsForRecheck(SyncJob job) {
        return cleanApartFromTheCommentLook(job)
                && job.isAccountSideKnown() && job.getAccountSideStop() == null;
    }

    private static boolean cleanApartFromTheCommentLook(SyncJob job) {
        return job != null
                && SCHEDULED.equals(job.getTrigger())
                && Cafe24ApiConnector.KIND.equals(job.getJobType())
                && DataType.INQUIRY.name().equals(job.getDataType())
                && "SUCCESS".equals(job.getStatus())
                && !job.isRateLimited()
                && job.getOrgId() != null && job.getSellerAccountId() != null && job.getChannelId() != null;
    }
}
