package com.sellerops.inquiry.decision;

import java.util.ArrayList;
import java.util.List;

/**
 * What kind of inquiry this is, for the one distinction the seller can correct (2026-11-05 seventh review): even about
 * an order, explaining how something works, checking what happened to this order, and doing something to it are three
 * different jobs (FINDINGS §B95). Read off the planner's need types; never off a sentence.
 *
 * <ul>
 *   <li>{@link #HOW_TO} — answered from the store's policy or product information; nothing about this order.</li>
 *   <li>{@link #ORDER_STATE} — what happened to this order: the seller looks it up.</li>
 *   <li>{@link #ORDER_ACTION} — something to be done to this order: the seller does it, or says why not.</li>
 * </ul>
 */
public enum InquiryKind {
    HOW_TO("방법 안내", "정책이나 상품 정보로 답하는 문의"),
    ORDER_STATE("상태 확인", "이 주문이 어떻게 됐는지 묻는 문의"),
    ORDER_ACTION("조치 요청", "이 주문에 무언가를 해 달라는 요청");

    /** The need a correction adds when the plan had none of its kind — the seller's own words for what is asked. */
    static final String STATE_ASK = "이 주문이 지금 어떤 상태인지";
    static final String ACTION_ASK = "이 주문에 요청된 조치를 할 수 있는지와 그 결과";

    private final String labelKo;
    private final String helpKo;

    InquiryKind(String labelKo, String helpKo) {
        this.labelKo = labelKo;
        this.helpKo = helpKo;
    }

    public String labelKo() {
        return labelKo;
    }

    public String helpKo() {
        return helpKo;
    }

    public static InquiryKind parse(String s) {
        if (s == null) {
            return null;
        }
        for (InquiryKind k : values()) {
            if (k.name().equals(s.trim())) {
                return k;
            }
        }
        return null;
    }

    /** The planner's reading: an action asked for is the larger job, then a state; anything else is answered from information. */
    public static InquiryKind of(List<InquiryNeed> needs) {
        if (needs == null || needs.isEmpty()) {
            return null;
        }
        if (needs.stream().anyMatch(n -> n.type() == NeedType.ORDER_ACTION)) {
            return ORDER_ACTION;
        }
        if (needs.stream().anyMatch(n -> n.type() == NeedType.ORDER_STATE)) {
            return ORDER_STATE;
        }
        return HOW_TO;
    }

    /**
     * The plan under the seller's correction. Only order needs move; every other need, its words and its search stay.
     * <ul>
     *   <li>{@link #HOW_TO}: an order need becomes a policy need — answered from what the store says, not this order.</li>
     *   <li>{@link #ORDER_STATE} / {@link #ORDER_ACTION}: an order need of the other kind becomes this kind; with no
     *       order need at all, one of this kind is added, in the seller's words, so the seller centre is asked for.</li>
     * </ul>
     * The plan's size limit still holds: a plan already at it gets no added need and stays as the planner wrote it.
     */
    public static List<InquiryNeed> apply(List<InquiryNeed> needs, InquiryKind kind) {
        if (needs == null || kind == null) {
            return needs;
        }
        List<InquiryNeed> out = new ArrayList<>();
        boolean order = false;
        for (InquiryNeed n : needs) {
            NeedType t = n.type();
            boolean isOrder = t == NeedType.ORDER_STATE || t == NeedType.ORDER_ACTION;
            order |= isOrder;
            NeedType to = !isOrder ? t
                    : kind == HOW_TO ? NeedType.POLICY
                    : kind == ORDER_STATE ? NeedType.ORDER_STATE : NeedType.ORDER_ACTION;
            out.add(to == t ? n : new InquiryNeed(n.id(), n.ask(), to, n.search()));
        }
        if (!order && kind != HOW_TO && out.size() < InquiryDecisionEngine.MAX_NEEDS) {
            String id = "S1";
            while (idTaken(out, id)) {
                id = "S" + (Integer.parseInt(id.substring(1)) + 1);
            }
            out.add(new InquiryNeed(id, kind == ORDER_STATE ? STATE_ASK : ACTION_ASK,
                    kind == ORDER_STATE ? NeedType.ORDER_STATE : NeedType.ORDER_ACTION, null));
        }
        return out;
    }

    private static boolean idTaken(List<InquiryNeed> needs, String id) {
        return needs.stream().anyMatch(n -> id.equals(n.id()));
    }

    /**
     * The model, its plan read under the seller's correction. The judge, the metering and the switch are the model's own;
     * nothing else changes.
     */
    public static InquiryDecisionModel correcting(InquiryDecisionModel model, InquiryKind kind) {
        if (model == null || kind == null) {
            return model;
        }
        return new InquiryDecisionModel() {
            @Override
            public boolean enabledFor(java.util.UUID orgId) {
                return model.enabledFor(orgId);
            }

            @Override
            public Answer<List<InquiryNeed>> plan(java.util.UUID orgId, String question) {
                Answer<List<InquiryNeed>> planned = model.plan(orgId, question);
                return planned.value() == null || planned.value().isEmpty() ? planned
                        : new Answer<>(apply(planned.value(), kind), planned.cost(), planned.failure());
            }

            @Override
            public Answer<java.util.Map<String, NeedVerdict>> judge(java.util.UUID orgId, String question,
                                                                    List<InquiryNeed> needs,
                                                                    List<EvidenceCandidate> evidence,
                                                                    List<PrecedentCandidate> precedents) {
                return model.judge(orgId, question, needs, evidence, precedents);
            }

            @Override
            public Answer<java.util.Map<String, NeedVerdict>> judge(java.util.UUID orgId, String question,
                                                                    List<InquiryNeed> needs,
                                                                    List<EvidenceCandidate> evidence,
                                                                    List<PrecedentCandidate> precedents,
                                                                    String received) {
                return model.judge(orgId, question, needs, evidence, precedents, received);
            }

            @Override
            public InquiryDecisionModel chargedTo(com.sellerops.agent.quota.AgentUsageActor actor) {
                return correcting(model.chargedTo(actor), kind);
            }
        };
    }
}
