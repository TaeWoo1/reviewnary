package com.sellerops.connector;

import java.util.List;

/**
 * One page returned by a {@link PullConnector#fetch}.
 *
 * <p>{@code records} is intentionally {@code List<?>} of canonical records whose
 * concrete type matches {@code dataType} (e.g. {@code CanonicalInquiry} for
 * {@code INQUIRY}). A {@code SyncRunExecutor} dispatches by {@code dataType} to the
 * right {@code IngestionService} method, avoiding unsafe casts.
 *
 * <p>{@code orders} is an <b>additive, optional</b> secondary payload: per-order
 * ({@code CanonicalOrder}) records a per-order channel (NAVER) emits alongside the
 * daily {@code ORDER_SUMMARY} {@code records}. It is empty for every aggregate-only
 * channel and for every non-order page, so the primary path is unchanged.
 *
 * <p>{@code nextCursorValue}/{@code hasMore} drive resumable paging.
 * {@code rateLimited}/{@code retryAfterSeconds} carry a throttling signal for the
 * rate-limit governor; a page only emits the signal, it does not act on it.
 * {@code source} is the originating connector kind (e.g. MOCK_API).
 *
 * <p>{@code accountSideStop} (2026-11-09): a secondary read the page made beside its records — Cafe24's comment look for
 * a shop answer — stopped on the account's or the channel's side (a refused token or permission, a 429, an outage). The
 * page's records are good and are kept; the name says only that what was learned beside them is incomplete for reasons
 * that are not about any one record, so whatever would run on that account next (the open-inquiry recheck) waits for a
 * later run. Null for every page that made no such read or whose read did not stop that way. Never a token, id or text.
 */
public record FetchPage(
        DataType dataType,
        List<?> records,
        List<?> orders,
        String nextCursorValue,
        boolean hasMore,
        boolean rateLimited,
        Integer retryAfterSeconds,
        String source,
        String accountSideStop) {

    /** A normal page of records with no per-order payload. */
    public static FetchPage of(DataType dataType, List<?> records, String nextCursorValue,
                               boolean hasMore, String source) {
        return new FetchPage(dataType, List.copyOf(records), List.of(), nextCursorValue, hasMore,
                false, null, source, null);
    }

    /**
     * A page that carries both aggregate {@code records} and per-order {@code orders} — used by a
     * per-order channel so the daily summary and the order-level rows land from the same page.
     */
    public static FetchPage ofWithOrders(DataType dataType, List<?> records, List<?> orders,
                                         String nextCursorValue, boolean hasMore, String source) {
        return new FetchPage(dataType, List.copyOf(records), List.copyOf(orders), nextCursorValue,
                hasMore, false, null, source, null);
    }

    /** A throttled response: no records, retry after the given seconds, cursor unchanged. */
    public static FetchPage rateLimited(DataType dataType, String cursorValue,
                                        int retryAfterSeconds, String source) {
        return new FetchPage(dataType, List.of(), List.of(), cursorValue, true, true,
                retryAfterSeconds, source, null);
    }

    /** This page, noting that a secondary read beside its records stopped on the account's side ({@code stop}). */
    public FetchPage withAccountSideStop(String stop) {
        return new FetchPage(dataType, records, orders, nextCursorValue, hasMore, rateLimited, retryAfterSeconds,
                source, stop);
    }
}
