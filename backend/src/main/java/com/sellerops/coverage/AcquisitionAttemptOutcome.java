package com.sellerops.coverage;

import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.sync.SyncJob;

/**
 * <b>How the most recent attempt to read one channel × data type ended.</b>
 *
 * <p>This exists because one field was being asked to carry two facts. «When did we last read this channel»
 * and «does this channel still answer» are different questions, and {@code lastSuccessfulSyncAt} was
 * answering both: it was computed as «the latest run, if it succeeded», so a later failure deleted a date
 * that remained true. Measured 2026-10-07 — Coupang 리뷰 was read on 09-14 and the product held its 33 rows;
 * a guided run reached the WING sign-in wall that evening and coverage then said 「확인된 적 없음」 about a
 * channel it had demonstrably read. A sign-in that expired is news about today, not about September.
 *
 * <p>So the two facts are two fields now, and this enum is the second one's vocabulary. It is deliberately
 * small: a seller is told one of four things, and the one that was missing is the one that cost a session —
 * <b>the sign-in wall is not a failure of this product</b>, it is a step only the seller can take, and a word
 * that lumped it in with a timeout sent people to look at the wrong thing.
 *
 * <p>Two record shapes can be an attempt, because two lanes reach a store: a connector pull writes a
 * {@link SyncJob}, and a screen read writes a {@code ScheduledAsideJob}. Both are mapped here so that
 * neither lane's attempts are invisible — the 2026-10-08 NAVER 리뷰 OPERATOR read wrote no sync run at all,
 * and reading only sync runs would have left today's answer blank for a channel that was attempted minutes ago.
 */
public enum AcquisitionAttemptOutcome {

    /** Rows were read and stored. */
    SUCCESS,

    /** Rows were read, and the lane says it did not cover the whole list (a one-page read, a stopped walk). */
    PARTIAL,

    /**
     * The channel's own sign-in wall stopped the read. Nothing was read and nobody typed anything.
     *
     * <p>The remedy belongs to the seller and to no automation: sign in on the marketplace, then collect
     * again. Never merged into {@link #FAILED} — a seller told «수집 실패» goes looking for a broken product.
     */
    AUTH_REQUIRED,

    /** Any other way an attempt that reached the channel ended without rows. */
    FAILED;

    /** Whether this attempt established that the channel still answers with data. */
    public boolean readData() {
        return this == SUCCESS || this == PARTIAL;
    }

    /**
     * A {@link SyncJob}'s ending, as one of these words.
     *
     * <p>The auth case is recognised from the failure code, and <b>also</b> from the legacy error message:
     * the guided Coupang lane records the channel's own code (`LOGIN_REQUIRED`) and, until 2026-10-08, put it
     * only in {@code errorMessage}. Rows already in the database carry that shape and are the very rows this
     * package exists to stop misreading, so both are accepted and the writer now fills the code as well.
     */
    public static AcquisitionAttemptOutcome of(SyncJob job) {
        if (job == null) {
            return null;
        }
        if ("SUCCESS".equals(job.getStatus())) {
            return SUCCESS;
        }
        if ("PARTIAL".equals(job.getStatus())) {
            return PARTIAL;
        }
        return isAuthMarker(job.getFailureCode()) || isAuthMarker(job.getErrorMessage()) ? AUTH_REQUIRED : FAILED;
    }

    /** A screen read's ending, as one of these words. */
    public static AcquisitionAttemptOutcome of(AsideJobOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case OBSERVED -> SUCCESS;
            case AUTH_REQUIRED -> AUTH_REQUIRED;
            default -> FAILED;
        };
    }

    /**
     * The closed set of words either lane uses for «the channel asked for a sign-in».
     *
     * <p>Matched exactly, never by substring: {@code errorMessage} also carries free text from other lanes
     * (a walk's stop reason, for one), and a substring rule there would start reading prose as a code.
     */
    private static boolean isAuthMarker(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        return SyncJob.FAILURE_AUTH_REQUIRED.equals(value) || "LOGIN_REQUIRED".equals(value);
    }
}
