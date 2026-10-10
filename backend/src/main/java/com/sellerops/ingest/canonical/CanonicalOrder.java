package com.sellerops.ingest.canonical;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Source-agnostic per-order (product-order granularity) record. Carries only fields a channel
 * actually returns — no buyer PII (name / phone / address / memo) and no raw payload. Normalization
 * of {@code rawStatusCode} into a canonical status is done at ingestion, not here, so this record
 * stays a faithful projection of the source.
 *
 * <p>Identity is {@code externalOrderId} (the channel's stable per-line id — NAVER's
 * {@code productOrderId}); {@code parentOrderId} (NAVER's {@code orderId}) is the payment-unit
 * grouping. {@code summaryDate} is the KST bucket the daily summary already uses, so the two stay
 * consistent. {@code paidAt}/{@code statusChangedAt} are stored only when the source supplies them.
 * {@code sourceRow} is the 1-based originating position (for error reporting).
 *
 * <p><b>{@code productRefs} is a LIST, and the reason is that a row's contents differ by channel</b>
 * (Order Context Foundation v1, D1). NAVER's row is one product order, so it carries at most one
 * reference; Coupang's row is one shipment box, whose {@code orderItems} may name several products.
 * A single reference here would make one product in a two-product box into the product of the whole
 * order. An empty list is the honest value for a source that gave no identifier — the same thing
 * {@link ChannelProductRef#absent()} says for one row, and the answer to it is no attribution.
 */
public record CanonicalOrder(
        String externalOrderId,
        String parentOrderId,
        String rawStatusCode,
        long paymentAmount,
        LocalDate summaryDate,
        Instant paidAt,
        Instant statusChangedAt,
        int sourceRow,
        List<ChannelProductRef> productRefs) {

    public CanonicalOrder {
        productRefs = productRefs == null ? List.of() : List.copyOf(productRefs);
    }

    /**
     * The shape before D1 — no product reference at all.
     *
     * <p>Kept so that every existing caller and test of the eight-argument record is untouched: a
     * source that does not project an identifier is the product as it behaved before this arc, and it
     * still behaves exactly that way.
     */
    public CanonicalOrder(String externalOrderId, String parentOrderId, String rawStatusCode,
                          long paymentAmount, LocalDate summaryDate, Instant paidAt,
                          Instant statusChangedAt, int sourceRow) {
        this(externalOrderId, parentOrderId, rawStatusCode, paymentAmount, summaryDate, paidAt,
                statusChangedAt, sourceRow, List.of());
    }
}
