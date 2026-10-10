package com.sellerops.operationspolicy;

/**
 * <b>How far one seller-declared policy reaches</b> — and a value the seller states rather than one derived from
 * what happened to be resolvable.
 *
 * <p>Two values, like every other ORG/PRODUCT pair in this schema. It is its own enum rather than a reuse of
 * {@code KnowledgeSpineScope} (where a corpus holds) or {@code OpportunityRules.Scope} (where a suggested sentence
 * would go) because this one answers a third question — which future cases a RULE decides — and a shared enum
 * across three questions is how a change made for one of them silently moves the others.
 *
 * <p><b>The declaration is the point.</b> {@code seller_guidance} derives its scope from whether the case's
 * product happened to have an operator name, so a seller who chose 「이 상품만」 on a product with no name got a
 * company-wide row and was never told. Here the scope arrives from the seller and
 * {@code SellerOperationsPolicyService.declare} refuses a PRODUCT policy it cannot bind, rather than widening it.
 */
public enum OperationsPolicyScope {

    /** Every product this organisation sells. */
    ORG,

    /** One product, named by the seller. Never inferred, never widened. */
    PRODUCT;

    public static OperationsPolicyScope parse(String raw) {
        if (raw == null) {
            return null;
        }
        String wanted = raw.strip();
        for (OperationsPolicyScope value : values()) {
            if (value.name().equals(wanted)) {
                return value;
            }
        }
        return null;
    }

    public String labelKo() {
        return this == ORG ? "회사 전체" : "이 상품만";
    }
}
