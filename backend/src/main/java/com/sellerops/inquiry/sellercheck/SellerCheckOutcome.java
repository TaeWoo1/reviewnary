package com.sellerops.inquiry.sellercheck;

/**
 * The four things a seller can find on the seller centre (2026-11-02 seller-check review). Each is a different thing to
 * say to the customer and a different thing left afterwards — which is why a free sentence alone is not enough.
 */
public enum SellerCheckOutcome {

    /** The action was actually carried out. The only outcome a reply may call finished. */
    DONE("처리 완료"),

    /** Received or under way. Not finished, and no date the record does not hold. */
    IN_PROGRESS("접수·진행 중"),

    /** It cannot be done, and why. Still checked like any other reply — a refusal earns no exemption. */
    CANNOT("처리 불가"),

    /** The seller needs something from the customer before they can check. */
    NEEDS_CUSTOMER_INFO("고객 정보가 더 필요함");

    private final String labelKo;

    SellerCheckOutcome(String labelKo) {
        this.labelKo = labelKo;
    }

    public String labelKo() {
        return labelKo;
    }

    /** A closed word from a request, or null when it is not one of the four. */
    public static SellerCheckOutcome parse(String value) {
        if (value == null) {
            return null;
        }
        for (SellerCheckOutcome o : values()) {
            if (o.name().equals(value.strip())) {
                return o;
            }
        }
        return null;
    }
}
