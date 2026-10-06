package com.sellerops.order;

import com.sellerops.order.fact.OrderPaymentState;

/**
 * The deliberately minimal normalization of a channel's raw order status. Under the current NAVER
 * request scope ({@code lastChangedType=PAYED}) the only status actually observed is payment-completed,
 * so this normalization is honest about what it can and cannot claim:
 *
 * <ul>
 *   <li>{@link #PAID} — raw {@code "PAYED"}, the payment-completed status.</li>
 *   <li>{@link #UNKNOWN} — any other or unrecognized raw code. <b>Fail closed:</b> we never guess a
 *       shipping / cancel / return / claim meaning from a code we have not observed live.</li>
 * </ul>
 *
 * <p>The raw code is always stored verbatim beside this. Extending normalization (and observing real
 * status transitions) requires widening the request's {@code lastChangedType} and confirming the value
 * set against a real seller — {@code correct-IP live proof pending}.
 *
 * <p><b>Which code means what is not decided here.</b> It is decided in
 * {@link ChannelOrderStatusVocabulary}, the one table of confirmed codes, and this enum asks it — so
 * widening the vocabulary and widening ingestion's normalization are the same edit rather than two.
 *
 * <p><b>The channel is part of the question.</b> {@code PAYED} is NAVER's word, confirmed on NAVER;
 * the same five letters arriving from another channel prove nothing, and a normalization that could
 * not see the channel would write {@code PAID} for every future channel that happens to spell it the
 * same way. A row whose channel cannot be named normalizes to {@link #UNKNOWN} — the raw code is
 * stored verbatim either way, so nothing is lost but the claim.
 */
public enum NormalizedOrderStatus {
    PAID,
    UNKNOWN;

    /**
     * Map one channel's raw status code to a canonical status, failing closed on anything unobserved
     * — including a code whose letters we know from ANOTHER channel.
     */
    public static NormalizedOrderStatus fromRaw(String channelCode, String rawStatusCode) {
        return ChannelOrderStatusVocabulary.axesFromStored(channelCode, rawStatusCode).payment()
                == OrderPaymentState.PAID ? PAID : UNKNOWN;
    }
}
