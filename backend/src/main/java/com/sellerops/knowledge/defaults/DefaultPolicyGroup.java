package com.sellerops.knowledge.defaults;

import com.sellerops.knowledge.org.OrgKnowledgeType;

/**
 * The four shop-standard documents the questionnaire writes. One document per topic rather than one for everything,
 * because the org lane matches a rule on its type and its title (KnowledgeTopic): a 현금영수증 question must not be
 * answered from the shipping rules because one long document mentioned both.
 */
public enum DefaultPolicyGroup {

    SHIPPING("배송", OrgKnowledgeType.SHIPPING_POLICY, "배송 안내 (기본 설정)"),
    RETURNS("교환·반품", OrgKnowledgeType.EXCHANGE_REFUND_POLICY, "교환·반품 안내 (기본 설정)"),
    SERVICE("상담·A/S", OrgKnowledgeType.GENERAL_CS_FAQ, "상담 시간·A/S 안내 (기본 설정)"),
    RECEIPTS("영수증", OrgKnowledgeType.CASH_RECEIPT, "현금영수증·세금계산서 안내 (기본 설정)");

    private final String labelKo;
    private final OrgKnowledgeType type;
    private final String title;

    DefaultPolicyGroup(String labelKo, OrgKnowledgeType type, String title) {
        this.labelKo = labelKo;
        this.type = type;
        this.title = title;
    }

    public String labelKo() {
        return labelKo;
    }

    public OrgKnowledgeType type() {
        return type;
    }

    public String title() {
        return title;
    }
}
