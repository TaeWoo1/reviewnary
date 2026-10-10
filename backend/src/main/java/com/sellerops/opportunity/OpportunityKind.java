package com.sellerops.opportunity;

/**
 * The four things a seller can DO about a repeated problem in v1 — the closed vocabulary of
 * {@code improvement_opportunity.kind}.
 *
 * <p>Each kind names a place the seller controls (their FAQ, their detail page, their operating
 * rules, their product) rather than a cause. "접착 불만이 반복됩니다" is an issue; "접착 안내를 FAQ에
 * 보완하는 것을 검토하세요" is an opportunity — the second is a suggestion about where to act, and it
 * carries no claim about WHY the customers complained.
 *
 * <p>The prepared action stops at a draft, and the draft's destination is a seam that already exists
 * and already belongs to the seller: FAQ / 운영 기준 drafts go into the knowledge library through the
 * quick-add the inquiry and review screens use, and the other two are text the seller copies. Nothing
 * here can reach a marketplace ({@code OpportunitySafetyFenceTest}).
 */
public enum OpportunityKind {
    /** Add a customer-facing answer to the product's FAQ — chosen when the library says nothing about the aspect. */
    FAQ_SUPPLEMENT("FAQ 보완", "FAQ 초안 준비"),
    /** Make what the seller already knows visible BEFORE purchase — the detail page / 안내. */
    PRODUCT_GUIDE_SUPPLEMENT("상품 상세·안내 보완", "상세페이지 안내문 초안 준비"),
    /** The company's own rule about a topic that is not one product's (배송) — register or sharpen it. */
    OPERATING_POLICY_SUPPLEMENT("운영 기준 보완", "운영 기준 초안 준비"),
    /** The product or its packaging itself keeps failing — a review memo, not a verdict. */
    PRODUCT_IMPROVEMENT_REVIEW("제품 개선 검토", "제품 개선 검토 메모 준비");

    private final String labelKo;
    private final String actionLabelKo;

    OpportunityKind(String labelKo, String actionLabelKo) {
        this.labelKo = labelKo;
        this.actionLabelKo = actionLabelKo;
    }

    public String labelKo() {
        return labelKo;
    }

    /** The one prepared action this kind offers — a draft, never a send. */
    public String actionLabelKo() {
        return actionLabelKo;
    }

    /**
     * Whether two kinds are two NAMES for one suggestion — the same slot on the same repeated problem.
     *
     * <p><b>Why this exists.</b> {@link #FAQ_SUPPLEMENT} and {@link #PRODUCT_GUIDE_SUPPLEMENT} are the one
     * product-guidance slot, and which of the two names it carries is decided by a single derived fact:
     * whether the seller's library already says anything about the aspect
     * ({@code OpportunityRules.guidanceKindOf}). Applying the suggestion is what writes that sentence — so the
     * seller's own act flipped the name, the stored decision stopped matching the derived candidate, and the
     * card they had just applied came back as a different kind with no decision on it: 검토 전, as if nothing
     * had happened. The decision was never lost; the join could not find it.
     *
     * <p>The table's identity is unchanged — {@code (org, issue, kind)}, one decision per name
     * ({@code uq_improvement_opportunity_identity}). This is about which derived candidate a STORED decision
     * answers to, and it is deliberately not a general looseness: every other pair of kinds is a different
     * place to act, and only this pair is one slot whose name moves under the seller's hand.
     */
    public boolean namesSameSlotAs(OpportunityKind other) {
        return this == other || (productGuidance() && other.productGuidance());
    }

    private boolean productGuidance() {
        return this == FAQ_SUPPLEMENT || this == PRODUCT_GUIDE_SUPPLEMENT;
    }
}
