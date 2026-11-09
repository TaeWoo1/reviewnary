package com.sellerops.collect;

import com.sellerops.sync.SyncJob;

/**
 * Work that follows one finished <b>scheduled</b> collection run (2026-11-08). {@link SyncScheduleRunner} calls every
 * bean of this type after it has rescheduled the run, with the run's finished job — whatever its status, so each
 * follow-up decides for itself what it waits for. A follow-up that throws never stops the rest of the batch.
 *
 * <p>Not called for manual, retry or backfill runs, and not for a source an active responsibility owns (that runtime
 * collects it instead).
 */
public interface ScheduledCollectionFollowUp {

    void afterScheduledCollection(SyncJob finished);
}
