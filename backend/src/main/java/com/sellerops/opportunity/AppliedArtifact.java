package com.sellerops.opportunity;

/**
 * What the applied draft landed in, when this product can name it.
 *
 * <p>The distinction is not bookkeeping. A remediation with an artifact is a change SellerOps can point at —
 * the org rule or the product source the seller saved, which is still there and still readable. One without is
 * the seller's own word that they did something, which is worth recording and is not the same claim. The
 * {@code applied_ref_kind} column exists so the two can never be printed as one sentence.
 */
public enum AppliedArtifact {

    /** The draft was saved as a company operating rule ({@code org_knowledge_sources}). */
    ORG_KNOWLEDGE("운영 기준"),
    /** The draft was saved onto the product's own shelf ({@code product_knowledge_sources}). */
    PRODUCT_KNOWLEDGE("상품 지식"),
    /**
     * The seller took the draft away and says they acted on it. No artifact in this product.
     *
     * <p>This is the only value that rests on testimony, and it is the honest shape for the product-improvement
     * memo, whose destination was always the seller's clipboard. It is recorded as what it is rather than
     * dressed up as a saved document.
     */
    SELLER_DECLARED("판매자 확인");

    private final String labelKo;

    AppliedArtifact(String labelKo) {
        this.labelKo = labelKo;
    }

    public String labelKo() {
        return labelKo;
    }

    /** Whether applying this way is itself a change in the seller's own records. */
    public boolean isRecordedChange() {
        return this != SELLER_DECLARED;
    }
}
