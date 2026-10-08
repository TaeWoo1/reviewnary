package com.sellerops.localagent;

import com.sellerops.responsibility.aside.ScheduledAsideJob;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>One screen read, as the seller's screen may see it.</b>
 *
 * <p>Sanitized by omission rather than by filtering: there is no field here for a URL, a page, a store id, a
 * credential, a device or a selector, because the row has none of those either. What is left is what a seller
 * asked for — did it run, did it read my store, how many came in, and how far it reached.
 *
 * @param state     the one word the screen shows ({@link LocalAgentRunState})
 * @param observed  rows the page printed, or null when nothing was read. Null is not 0 and never renders as 0
 * @param inserted  rows that reached canonical storage as new, counted by ingest — null until delivery
 * @param changed   already-stored rows this read advanced, counted by ingest — null until delivery
 * @param complete  whether the read can promise nothing newer was missed behind its one page
 */
public record ScreenReadView(UUID jobId, LocalAgentRunState state, Integer observed, Integer inserted,
                             Integer changed, boolean complete, Instant startedAt, Instant finishedAt,
                             CatchUp catchUp) {

    /**
     * <b>When one press is walking several periods, the state belongs to the walk — not to whichever child
     * settled a second ago.</b>
     *
     * <p>A screen polling one child would see it finish while five windows were still to come, say
     * 「수집 완료」 and stop watching. So a child of a catch-up reports its parent's state, and these numbers
     * are the parent's: how many periods are done, how many rows it has read, and — when it has stopped — the
     * one word that says why. The window dates are deliberately absent: the seller has no use for them, and
     * they belong in the run record.
     *
     * @param stopReason {@code AUTH_REQUIRED}, {@code DAY_SATURATED}, {@code MAX_*} — or null while running.
     *                   Four of the five stops are not failures and the screen says a different thing for each
     */
    public record CatchUp(int windowsDone, int rowsObserved, String runState, String stopReason) {
    }

    public static ScreenReadView of(ScheduledAsideJob job, boolean includeCounts) {
        LocalAgentRunState state = LocalAgentRunState.of(job);
        boolean delivered = state == LocalAgentRunState.SUCCESS || state == LocalAgentRunState.PARTIAL;
        return new ScreenReadView(job.getId(), state,
                includeCounts ? job.getObservedCount() : null,
                delivered ? job.getInsertedCount() : null,
                delivered ? job.getChangedCount() : null,
                state == LocalAgentRunState.SUCCESS,
                job.getCreatedAt(), job.getSettledAt(), null);
    }

    /** The same job, answered at the level of the walk it belongs to. */
    public ScreenReadView withCatchUp(LocalAgentRunState walkState, CatchUp catchUp) {
        return new ScreenReadView(jobId, walkState, observed, inserted, changed,
                walkState == LocalAgentRunState.SUCCESS, startedAt, finishedAt, catchUp);
    }
}
