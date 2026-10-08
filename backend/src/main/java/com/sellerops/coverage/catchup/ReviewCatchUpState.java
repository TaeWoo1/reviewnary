package com.sellerops.coverage.catchup;

/**
 * <b>Where a catch-up is, and — when it has stopped — why.</b>
 *
 * <p>Three of these are stops that are not failures, and they are kept apart because each one means a
 * different next thing for the seller: sign in, accept that one day holds more than a page, or press again
 * later. Collapsing them into one FAILED would make the product say 「수집하지 못했습니다」 about a run that
 * read five weeks correctly and then met a login wall.
 */
public enum ReviewCatchUpState {
    /** A child job is queued or running. */
    RUNNING,
    /** A window met the channel's sign-in wall. The next window is not queued; the seller signs in and resumes. */
    PAUSED_AUTH,
    /** Every day from the boundary to today has been read. */
    COMPLETE,
    /** A single day held at least a full page. Nothing past that day may be claimed as read. */
    STOPPED_SATURATED,
    /** One press's bound was reached — windows, days, rows or wall-clock. Pressing again continues. */
    STOPPED_LIMIT,
    /** A window ended in a way this lane cannot continue from. What completed before it still stands. */
    FAILED;

    public boolean live() {
        return this == RUNNING || this == PAUSED_AUTH;
    }
}
