package com.sellerops.knowledge.claim;

/**
 * <b>How two values about the same thing relate</b> ({@code DESIGN_conflict.md} §4). Overlap is never sameness:
 * 「1~2일」 and 「2~3일」 share 2일 and are still two different promises.
 *
 * <ul>
 *   <li>{@link #SAME} — the same value or range;</li>
 *   <li>{@link #EQUIVALENT} — stated differently and the same amount: 편도 3,000원 against 왕복 6,000원;</li>
 *   <li>{@link #CONTAINS} — one range inside the other (1~3일 / 2일). <b>A conflict</b>: a customer told 「2일」 and one told
 *       「1~3일」 were told different things;</li>
 *   <li>{@link #OVERLAP} — ranges that share part and differ (1~2일 / 2~3일) — a conflict;</li>
 *   <li>{@link #DIFFERENT} — disjoint (7일 / 14일) — a conflict;</li>
 *   <li>{@link #NOT_COMPARED} — different attribute, condition or unit, a past value, or legs that do not add up: nothing
 *       is said either way.</li>
 * </ul>
 *
 * <p><b>Who pays is not an amount</b> (2026-11-15 review). Two fees that name different payers disagree whatever their
 * amounts — 「왕복 6,000원 고객 부담」 against 「편도 3,000원 판매자 부담」 is {@link #DIFFERENT}, not {@link #EQUIVALENT}. One
 * leg against the other is the same amount only when no payer is named against another; the ×2 never says the payer.
 */
public enum ClaimRelation {
    SAME, EQUIVALENT, CONTAINS, OVERLAP, DIFFERENT, NOT_COMPARED;

    public boolean conflicts() {
        return this == CONTAINS || this == OVERLAP || this == DIFFERENT;
    }

    public static ClaimRelation of(KnowledgeClaim a, KnowledgeClaim b) {
        if (a == null || b == null || a.historical() || b.historical() || !a.slot().equals(b.slot())) {
            return NOT_COMPARED;
        }
        if (a.payer() != null && b.payer() != null && a.payer() != b.payer()) {
            return DIFFERENT;
        }
        if (a.leg() != b.leg()) {
            // One way against both ways: the same amount when one is twice the other, otherwise two conditions the text
            // does not let us line up — not compared. A leg on one side only is not compared either.
            if (a.leg() == null || b.leg() == null || a.low() != a.high() || b.low() != b.high()) {
                return NOT_COMPARED;
            }
            long ra = a.leg() == KnowledgeClaim.Leg.ONE_WAY ? a.low() * 2 : a.low();
            long rb = b.leg() == KnowledgeClaim.Leg.ONE_WAY ? b.low() * 2 : b.low();
            return ra == rb ? EQUIVALENT : NOT_COMPARED;
        }
        if (a.low() == b.low() && a.high() == b.high()) {
            return SAME;
        }
        if (a.high() < b.low() || b.high() < a.low()) {
            return DIFFERENT;
        }
        boolean aInB = b.low() <= a.low() && a.high() <= b.high();
        boolean bInA = a.low() <= b.low() && b.high() <= a.high();
        return aInB || bInA ? CONTAINS : OVERLAP;
    }
}
