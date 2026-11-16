package com.sellerops.knowledge.claim;

/**
 * <b>The closed list of things a figure in the seller's material can be about</b> (2026-11-15, conflict step 2 —
 * {@code DESIGN_conflict.md} §4). Two figures are compared only when both are about the same one of these, under the same
 * condition and in the same unit; a figure about anything else is 「not compared」 — neither a conflict nor an agreement.
 *
 * <p>Deliberately short. Each entry is a value a customer is told and a seller can get wrong between two documents; adding
 * one is a decision about what the product compares, not a tuning knob.
 */
public enum ClaimAttribute {

    RETURN_WINDOW("반품 신청 기한", Unit.DAY),
    EXCHANGE_WINDOW("교환 신청 기한", Unit.DAY),
    RETURN_SHIPPING_FEE("반품 배송비", Unit.WON),
    SHIPPING_FEE("배송비", Unit.WON),
    FREE_SHIPPING_THRESHOLD("무료배송 기준 금액", Unit.WON),
    DISPATCH_CUTOFF("출고 마감 시각", Unit.HOUR),
    DELIVERY_DAYS("출고 후 도착 기간", Unit.DAY),
    PRODUCTION_DAYS("제작 기간", Unit.DAY);

    /** The unit family a value of this attribute is stated in. Business days are a unit of their own. */
    public enum Unit { DAY, BUSINESS_DAY, WON, HOUR }

    private final String labelKo;
    private final Unit unit;

    ClaimAttribute(String labelKo, Unit unit) {
        this.labelKo = labelKo;
        this.unit = unit;
    }

    public String labelKo() {
        return labelKo;
    }

    public Unit unit() {
        return unit;
    }
}
