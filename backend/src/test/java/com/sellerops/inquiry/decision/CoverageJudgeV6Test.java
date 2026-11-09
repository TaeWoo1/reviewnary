package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.inquiry.draft.AnswerBasisState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>CoverageJudge v6: v5, and a need the seller's completed action made moot</b> (2026-11-06 thirteenth review). R1
 * (2026-11-05): the seller recorded 「교환 상품 발송함」 and 「can this be exchanged」 still waited on the rule that would have
 * decided it, so the seller was asked to decide again what they had already done. The judge only names the record
 * ({@code settled_by}); code decides, and closes the need as settled — never as answered.
 */
class CoverageJudgeV6Test {

    private static final String ORDER = "inquiry:" + UUID.randomUUID();
    private static final EvidenceScope.CaseScope THIS_CASE = new EvidenceScope.CaseScope(null, ORDER);

    private static String rules(String version) {
        String p = InquiryDecisionPrompt.judgeSystem(version);
        return p.substring(0, p.indexOf("출력은 "));
    }

    private static EvidenceCandidate record(String outcome, String orderKey) {
        return new EvidenceCandidate(null, EvidenceCandidate.Kind.SELLER_CHECK, "판매자 확인",
                "판매자가 판매자센터에서 직접 확인한 이 문의의 결과(이 문의에만 해당, 채널이 확인한 사실 아님): 처리 완료 — "
                        + "교환 상품 발송함. 송장 등록 완료", UUID.randomUUID(), null, outcome, EvidenceScope.order(orderKey));
    }

    private static final EvidenceCandidate RULE = new EvidenceCandidate(null, EvidenceCandidate.Kind.ORG_KNOWLEDGE,
            "교환 기한", "교환은 상품 수령 후 7일 이내 신청하실 수 있습니다.", UUID.randomUUID(), null, null);

    /**
     * N1 judged PARTIAL on the rule (E1) for want of what would decide it, the record (E2) named as settling it; N2 the
     * action this inquiry asked for, answered in full by that record unless {@code actionStatus} says otherwise.
     */
    private static NeedDecision decide(NeedType type, EvidenceCandidate record, boolean fence) {
        return decide(type, record, fence, NeedStatus.FULL);
    }

    private static NeedDecision decide(NeedType type, EvidenceCandidate record, boolean fence, NeedStatus actionStatus) {
        InquiryDecisionEngineTest.ScriptedModel model = new InquiryDecisionEngineTest.ScriptedModel(
                List.of(InquiryDecisionEngineTest.need("N1", "포장이 찢어져 온 상품을 교환할 수 있는지", type),
                        InquiryDecisionEngineTest.need("N2", "이 주문을 교환해 달라는 요청", NeedType.ORDER_ACTION)),
                InquiryDecisionEngineTest.verdicts(new NeedVerdict("N1", NeedStatus.PARTIAL, List.of("E1"),
                        List.of("포장 찢김이 불량에 해당하는지에 대한 기준"), List.of(), List.of(), null, List.of(), "E2"),
                        InquiryDecisionEngineTest.verdict("N2", actionStatus, "E2")));
        return InquiryDecisionEngine.decide(UUID.randomUUID(), "문의", model,
                needs -> new InquiryDecisionEngine.Pool(List.of(RULE, record), List.of()), DetailCapability.NOT_ACQUIRED,
                THIS_CASE, fence ? new com.sellerops.inquiry.authority.AuthorityFence.Context(null, null, ORDER) : null);
    }

    @Test
    @DisplayName("v6 adds one rule and one field to v5, and its answer one property — nothing else")
    void oneRuleOneField() {
        List<String> v5 = List.of(rules(InquiryDecisionPrompt.JUDGE_V5).split("\n"));
        List<String> v6 = List.of(rules(InquiryDecisionPrompt.JUDGE_V6).split("\n"));
        assertThat(v6).hasSize(v5.size() + 2).containsAll(v5);
        List<String> added = v6.stream().filter(line -> !v5.contains(line)).toList();
        assertThat(added).hasSize(2);
        assertThat(added.get(0)).startsWith("- 판매자 확인 기록이 고객이 요청한 조치를 이 주문에 이미 했다고(처리 완료) 적고 있고")
                .contains("status · evidence · missing은 이 규칙과 상관없이 위 규칙대로 판정합니다")
                .contains("원인 · 안전 · 시점 · 금액처럼 고객이 따로 물은 것").contains("접수 · 진행 중 · 처리 불가");
        assertThat(added.get(1)).startsWith("- settled_by:");
        String out5 = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V5);
        String out6 = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V6);
        assertThat(out6.substring(out6.indexOf("출력은 ")))
                .isEqualTo(out5.substring(out5.indexOf("출력은 ")).replace("\"precedents\":[\"P1\"]}",
                        "\"precedents\":[\"P1\"],\"settled_by\":null}"));
        ObjectNode schema6 = InquiryDecisionPrompt.judgeSchema(InquiryDecisionPrompt.JUDGE_V6, List.of("N1"),
                List.of("E1"), List.of());
        ObjectNode schema5 = InquiryDecisionPrompt.judgeSchema(InquiryDecisionPrompt.JUDGE_V5, List.of("N1"),
                List.of("E1"), List.of());
        assertThat(schema6.toString()).contains("\"settled_by\"");
        assertThat(schema5.toString()).as("v5's request is unchanged").doesNotContain("settled_by");
        assertThat(InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V6)).isNotEqualTo(out5);
        assertThat(new InquiryDecisionProperties(true, "", "m", "k", 0, 0, null).judgePrompt())
                .isEqualTo(InquiryDecisionPrompt.JUDGE_V6);
    }

    @Test
    @DisplayName("the answer's settled_by is read, and a JSON null is no record")
    void parsed() {
        var named = InquiryDecisionGenerator.parseJudge("{\"verdicts\":[{\"need\":\"N1\",\"status\":\"PARTIAL\","
                + "\"evidence\":[\"E1\"],\"missing\":[\"기준\"],\"customer_input\":[],\"assumptions\":[],"
                + "\"ask_customer\":null,\"precedents\":[],\"settled_by\":\"E2\"}]}");
        assertThat(named.get("N1").settledBy()).isEqualTo("E2");
        var none = InquiryDecisionGenerator.parseJudge("{\"verdicts\":[{\"need\":\"N1\",\"status\":\"PARTIAL\","
                + "\"evidence\":[\"E1\"],\"missing\":[\"기준\"],\"customer_input\":[],\"assumptions\":[],"
                + "\"ask_customer\":null,\"precedents\":[],\"settled_by\":null}]}");
        assertThat(none.get("N1").settledBy()).isNull();
    }

    @Test
    @DisplayName("a rule an action done for this order made moot: covered for the seller, settled on the record, nothing "
            + "left open — and the authority fence keeps it, by the seller's name")
    void settled() {
        for (boolean fence : List.of(false, true)) {
            EvidenceCandidate done = record("DONE", ORDER);
            NeedDecision d = decide(NeedType.POLICY, done, fence);
            NeedResult n = d.needs().get(0);
            assertThat(n.status()).as("fence=%s", fence).isEqualTo(NeedStatus.FULL);
            assertThat(n.judged()).isEqualTo(NeedStatus.PARTIAL);
            assertThat(n.enforcement()).isEqualTo(NeedResult.Enforcement.SETTLED_BY_ACTION);
            assertThat(n.settlingRecord().sourceId()).isEqualTo(done.sourceId());
            assertThat(d.openQuestions()).isEmpty();
            assertThat(d.settledByAction()).hasSize(1);
            assertThat(d.basis()).isEqualTo(AnswerBasisState.GROUNDED);
            assertThat(d.answerScope()).contains("→ 판매자가 이 주문에 그 조치를 이미 했습니다");
            if (fence) {
                assertThat(n.resolution().capability()).isEqualTo(com.sellerops.inquiry.authority.CapabilityId.SELLER);
                assertThat(n.resolution().state()).isEqualTo(com.sellerops.inquiry.authority.ResolutionState.RESOLVED);
            }
        }
    }

    @Test
    @DisplayName("the action done must be one this inquiry asked for: with no action need the record answers in full, nothing "
            + "is settled — and an action request itself is never settled")
    void onlyBesideTheActionItDecided() {
        for (NeedStatus action : List.of(NeedStatus.PARTIAL, NeedStatus.NONE)) {
            NeedDecision d = decide(NeedType.POLICY, record("DONE", ORDER), false, action);
            assertThat(d.settledByAction()).as("action %s", action).isEmpty();
            assertThat(d.needs().get(0).status()).isEqualTo(NeedStatus.PARTIAL);
            assertThat(d.needs().get(0).enforcement()).isNull();
            assertThat(d.openQuestions()).anyMatch(q -> q.startsWith("포장이 찢어져 온 상품을 교환할 수 있는지 — "));
        }
        // A rule question alone, no action asked: the record of some action does not settle it.
        InquiryDecisionEngineTest.ScriptedModel alone = new InquiryDecisionEngineTest.ScriptedModel(
                List.of(InquiryDecisionEngineTest.need("N1", "교환할 수 있는지", NeedType.POLICY)),
                InquiryDecisionEngineTest.verdicts(new NeedVerdict("N1", NeedStatus.PARTIAL, List.of("E1"),
                        List.of("기준"), List.of(), List.of(), null, List.of(), "E2")));
        assertThat(InquiryDecisionEngine.decide(UUID.randomUUID(), "문의", alone,
                needs -> new InquiryDecisionEngine.Pool(List.of(RULE, record("DONE", ORDER)), List.of()),
                DetailCapability.NOT_ACQUIRED, THIS_CASE).settledByAction()).isEmpty();
        // Refund done, exchange still asked: the exchange request is never settled by the refund's record.
        InquiryDecisionEngineTest.ScriptedModel two = new InquiryDecisionEngineTest.ScriptedModel(
                List.of(InquiryDecisionEngineTest.need("N1", "이 주문을 환불해 달라는 요청", NeedType.ORDER_ACTION),
                        InquiryDecisionEngineTest.need("N2", "다른 주문을 교환해 달라는 요청", NeedType.ORDER_ACTION)),
                InquiryDecisionEngineTest.verdicts(InquiryDecisionEngineTest.verdict("N1", NeedStatus.FULL, "E2"),
                        new NeedVerdict("N2", NeedStatus.PARTIAL, List.of("E1"), List.of("교환 진행 여부"), List.of(),
                                List.of(), null, List.of(), "E2")));
        NeedDecision refund = InquiryDecisionEngine.decide(UUID.randomUUID(), "문의", two,
                needs -> new InquiryDecisionEngine.Pool(List.of(RULE, record("DONE", ORDER)), List.of()),
                DetailCapability.NOT_ACQUIRED, THIS_CASE);
        assertThat(refund.settledByAction()).isEmpty();
        assertThat(refund.openQuestions()).singleElement().asString().startsWith("다른 주문을 교환해 달라는 요청");
    }

    @Test
    @DisplayName("nothing else is settled: an action under way, refused or awaiting the customer; another order's record; "
            + "a date, a product fact or the seller's own decision the customer asked")
    void notSettled() {
        for (String outcome : List.of("IN_PROGRESS", "CANNOT", "NEEDS_CUSTOMER_INFO")) {
            assertThat(decide(NeedType.POLICY, record(outcome, ORDER), true).settledByAction()).as(outcome).isEmpty();
        }
        assertThat(decide(NeedType.POLICY, record("DONE", "inquiry:" + UUID.randomUUID()), false).settledByAction())
                .as("another order's record").isEmpty();
        for (NeedType type : List.of(NeedType.ORDER_STATE, NeedType.PRODUCT_SPEC, NeedType.PRODUCT_USAGE,
                NeedType.SELLER_DECISION, NeedType.CATALOGUE_AVAILABILITY)) {
            NeedDecision d = decide(type, record("DONE", ORDER), false);
            assertThat(d.settledByAction()).as(type.name()).isEmpty();
            assertThat(d.needs().get(0).enforcement()).isNotEqualTo(NeedResult.Enforcement.SETTLED_BY_ACTION);
        }
        // The judge naming the store's rule instead of the seller's record settles nothing.
        InquiryDecisionEngineTest.ScriptedModel model = new InquiryDecisionEngineTest.ScriptedModel(
                List.of(InquiryDecisionEngineTest.need("N1", "교환할 수 있는지", NeedType.POLICY)),
                InquiryDecisionEngineTest.verdicts(new NeedVerdict("N1", NeedStatus.PARTIAL, List.of("E1"),
                        List.of("기준"), List.of(), List.of(), null, List.of(), "E1")));
        NeedDecision byRule = InquiryDecisionEngine.decide(UUID.randomUUID(), "문의", model,
                needs -> new InquiryDecisionEngine.Pool(List.of(RULE, record("DONE", ORDER)), List.of()),
                DetailCapability.NOT_ACQUIRED, THIS_CASE);
        assertThat(byRule.settledByAction()).isEmpty();
        assertThat(byRule.openQuestions()).hasSize(1);
    }

    @Test
    @DisplayName("no product vocabulary in v6 either")
    void noDomainWords() {
        String p = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V6);
        for (String word : List.of("몰딩", "디스펜서", "전선", "종이컵", "호수", "선바로", "mm", "가닥", "박스", "할인", "도착", "교환",
                "포장", "발송")) {
            assertThat(p).as(word).doesNotContain(word);
        }
    }
}
