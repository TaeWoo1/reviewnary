package com.sellerops.knowledge.claim;

/**
 * Under which condition a value holds. Two values are compared only under the same condition: 「단순 변심 6,000원」 and
 * 「불량 0원」 are two rules, not a disagreement.
 */
public enum ClaimCondition {
    GENERAL(""),
    CHANGE_OF_MIND("단순 변심"),
    DEFECT("불량·오배송"),
    REMOTE_AREA("제주·도서산간");

    private final String labelKo;

    ClaimCondition(String labelKo) {
        this.labelKo = labelKo;
    }

    public String labelKo() {
        return labelKo;
    }
}
