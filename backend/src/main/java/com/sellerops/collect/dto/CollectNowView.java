package com.sellerops.collect.dto;

import com.sellerops.collect.CollectNowRouter;
import com.sellerops.localagent.ScreenReadView;

/**
 * <b>What one press of 지금 수집 started.</b>
 *
 * <p>Two shapes because the two routes finish at different times and a screen must not pretend otherwise. An
 * API run is synchronous: by the time this record exists the rows are in, and {@code run} carries the counts.
 * A screen read is handed to a program on the seller's own machine and this record is the receipt — the job's
 * state is {@code RUNNING} and the screen asks again.
 *
 * <p>{@code path} is here so the screen can say the right sentence, not so it can decide anything: the
 * decision was made on the server before either field was filled.
 *
 * @param path       which route the server chose for this account and data type
 * @param dataType   the data type as resolved, echoed so a reply cannot be read against the wrong row
 * @param run        the finished pull run — {@code null} on the screen-read route
 * @param screenRead the job now on the seller's desk — {@code null} on the API route
 */
public record CollectNowView(CollectNowRouter.Path path, String dataType, SyncRunView run,
                             ScreenReadView screenRead) {

    public static CollectNowView ofPull(String dataType, SyncRunView run) {
        return new CollectNowView(CollectNowRouter.Path.API, dataType, run, null);
    }

    public static CollectNowView ofScreenRead(String dataType, ScreenReadView screenRead) {
        return new CollectNowView(CollectNowRouter.Path.SCREEN_READ, dataType, null, screenRead);
    }
}
