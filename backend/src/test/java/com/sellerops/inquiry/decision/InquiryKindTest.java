package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The seller's correction of what kind of inquiry this is, on the plan alone (2026-11-05 seventh review). */
class InquiryKindTest {

    private static InquiryNeed n(String id, NeedType t) {
        return new InquiryNeed(id, "물은 것 " + id, t, "검색 " + id);
    }

    @Test
    @DisplayName("the service's reading: an action before a state, anything else answered from information")
    void theReading() {
        assertThat(InquiryKind.of(List.of(n("N1", NeedType.ORDER_STATE), n("N2", NeedType.ORDER_ACTION))))
                .isEqualTo(InquiryKind.ORDER_ACTION);
        assertThat(InquiryKind.of(List.of(n("N1", NeedType.POLICY), n("N2", NeedType.ORDER_STATE))))
                .isEqualTo(InquiryKind.ORDER_STATE);
        assertThat(InquiryKind.of(List.of(n("N1", NeedType.PRODUCT_SPEC)))).isEqualTo(InquiryKind.HOW_TO);
        assertThat(InquiryKind.of(List.of())).isNull();
    }

    @Test
    @DisplayName("방법 안내: order needs are answered from policy; nothing else moves")
    void howTo() {
        List<InquiryNeed> out = InquiryKind.apply(
                List.of(n("N1", NeedType.ORDER_ACTION), n("N2", NeedType.PRODUCT_SPEC), n("N3", NeedType.ORDER_STATE)),
                InquiryKind.HOW_TO);
        assertThat(out).extracting(InquiryNeed::type)
                .containsExactly(NeedType.POLICY, NeedType.PRODUCT_SPEC, NeedType.POLICY);
        assertThat(out).extracting(InquiryNeed::id, InquiryNeed::ask, InquiryNeed::search)
                .as("the customer's words and searches stay").containsExactly(
                        org.assertj.core.groups.Tuple.tuple("N1", "물은 것 N1", "검색 N1"),
                        org.assertj.core.groups.Tuple.tuple("N2", "물은 것 N2", "검색 N2"),
                        org.assertj.core.groups.Tuple.tuple("N3", "물은 것 N3", "검색 N3"));
        assertThat(InquiryKind.apply(List.of(n("N1", NeedType.POLICY)), InquiryKind.HOW_TO))
                .as("nothing added").hasSize(1);
    }

    @Test
    @DisplayName("상태 확인 · 조치 요청: an order need of the other kind becomes this kind; with none, one is added")
    void orderKinds() {
        assertThat(InquiryKind.apply(List.of(n("N1", NeedType.ORDER_ACTION), n("N2", NeedType.POLICY)),
                InquiryKind.ORDER_STATE)).extracting(InquiryNeed::type)
                .containsExactly(NeedType.ORDER_STATE, NeedType.POLICY);
        assertThat(InquiryKind.apply(List.of(n("N1", NeedType.ORDER_STATE)), InquiryKind.ORDER_ACTION))
                .extracting(InquiryNeed::type).containsExactly(NeedType.ORDER_ACTION);
        List<InquiryNeed> added = InquiryKind.apply(List.of(n("N1", NeedType.POLICY), n("S1", NeedType.PRODUCT_SPEC)),
                InquiryKind.ORDER_ACTION);
        assertThat(added).hasSize(3);
        assertThat(added.get(2).type()).isEqualTo(NeedType.ORDER_ACTION);
        assertThat(added.get(2).id()).as("never an id the plan already uses").isEqualTo("S2");
        assertThat(added.get(2).ask()).isEqualTo(InquiryKind.ACTION_ASK);
        List<InquiryNeed> full = java.util.stream.IntStream.rangeClosed(1, InquiryDecisionEngine.MAX_NEEDS)
                .mapToObj(i -> n("N" + i, NeedType.POLICY)).toList();
        assertThat(InquiryKind.apply(full, InquiryKind.ORDER_STATE)).as("a plan at its limit is not pushed past it")
                .hasSize(InquiryDecisionEngine.MAX_NEEDS);
    }

    @Test
    @DisplayName("the corrected model plans under the correction and judges, meters and fails as the model does")
    void theCorrectedModel() {
        InquiryDecisionModel base = new InquiryDecisionModel() {
            @Override
            public boolean enabledFor(UUID orgId) {
                return true;
            }

            @Override
            public Answer<List<InquiryNeed>> plan(UUID orgId, String question) {
                return question.isEmpty() ? new Answer<>(null, CallCost.NONE, "EMPTY")
                        : new Answer<>(List.of(n("N1", NeedType.ORDER_ACTION)), new CallCost(1, 2, 3, 4));
            }

            @Override
            public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                          List<EvidenceCandidate> evidence,
                                                          List<PrecedentCandidate> precedents) {
                return new Answer<>(Map.of(), new CallCost(1, 0, 0, 0));
            }
        };
        InquiryDecisionModel corrected = InquiryKind.correcting(base, InquiryKind.HOW_TO);
        var planned = corrected.plan(UUID.randomUUID(), "환불은 어떻게 하나요");
        assertThat(planned.value()).extracting(InquiryNeed::type).containsExactly(NeedType.POLICY);
        assertThat(planned.cost()).isEqualTo(new InquiryDecisionModel.CallCost(1, 2, 3, 4));
        assertThat(corrected.plan(UUID.randomUUID(), "").failure()).isEqualTo("EMPTY");
        assertThat(InquiryKind.correcting(base, null)).as("no correction, the model itself").isSameAs(base);
        assertThat(corrected.chargedTo(com.sellerops.agent.quota.AgentUsageActor.USER)
                .plan(UUID.randomUUID(), "x").value()).extracting(InquiryNeed::type).containsExactly(NeedType.POLICY);
    }
}
