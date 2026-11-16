package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.draft.AnswerBasisState;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.currency.KnowledgeState;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Owner decision 2 (2026-11-14), held by code</b>: the certain part is answered, only the disagreeing part goes to the
 * seller, and when the disagreement is the answer the whole message does — whatever the judge said.
 */
class NeedAggregationConflictTest {

    private static final String CURRENT = "상품 수령 후 7일 이내에 교환·반품을 신청할 수 있습니다. 단순 변심에 의한 교환·반품은 "
            + "왕복 배송비 6,000원을 고객님이 부담합니다.";
    private static final String OLD = "단순 변심 반품 시 반품 배송비는 편도 3,000원입니다. 교환은 수령 후 14일 이내에 신청해 주세요.";
    private final UUID current = UUID.randomUUID();
    private final UUID old = UUID.randomUUID();
    private final KnowledgeState state = KnowledgeState.of(List.of(
            new KnowledgeState.Source(current, "ORG", null, "ORG", "교환·반품 안내", 1, null, ClaimReader.read(CURRENT)),
            new KnowledgeState.Source(old, "ORG", null, "ORG", "옛 공지", 1, null, ClaimReader.read(OLD))),
            List.of(), LocalDate.of(2026, 11, 14), null);

    private Map<String, EvidenceCandidate> pool() {
        Map<String, EvidenceCandidate> pool = new LinkedHashMap<>();
        pool.put("E1", new EvidenceCandidate("E1", EvidenceCandidate.Kind.ORG_KNOWLEDGE, "교환·반품 안내", CURRENT, current,
                null, null).withMark(state.markFor(current, CURRENT)));
        pool.put("E2", new EvidenceCandidate("E2", EvidenceCandidate.Kind.ORG_KNOWLEDGE, "옛 공지", OLD, old, null, null)
                .withMark(state.markFor(old, OLD)));
        return pool;
    }

    private static NeedVerdict full(String need) {
        return new NeedVerdict(need, NeedStatus.FULL, List.of("E1", "E2"), List.of(), List.of(), List.of(), null,
                List.of());
    }

    @Test
    @DisplayName("q14: the exchange window is the answer and is not settled — the need is not answered, nothing is drafted")
    void theDisagreementIsTheAnswer() {
        InquiryNeed need = new InquiryNeed("N1", "운영 기준 안내", NeedType.POLICY, "운영 기준 안내");
        List<NeedResult> out = NeedAggregation.enforce(List.of(need), Map.of("N1", full("N1")), pool(), Map.of(),
                DetailCapability.NOT_APPLICABLE, EvidenceScope.CaseScope.NONE, "교환은 받고 며칠 안에 신청해야 하나요?");
        assertThat(out).singleElement().satisfies(n -> {
            assertThat(n.judged()).isEqualTo(NeedStatus.FULL);
            assertThat(n.status()).isEqualTo(NeedStatus.NONE);
            assertThat(n.enforcement()).isEqualTo(NeedResult.Enforcement.UNRESOLVED_CONFLICT);
            assertThat(n.missing()).contains("교환 신청 기한").contains("7일").contains("14일");
        });
        assertThat(NeedAggregation.basis(out)).as("the whole message goes to the seller")
                .isEqualTo(AnswerBasisState.NO_ANSWER_BASIS);
    }

    @Test
    @DisplayName("q06: a return question is untouched by the exchange disagreement, and the drafter is told beside it")
    void anotherAttributesDisagreementMovesNothing() {
        InquiryNeed need = new InquiryNeed("N1", "운영 기준 안내", NeedType.POLICY, "운영 기준 안내");
        List<NeedResult> out = NeedAggregation.enforce(List.of(need), Map.of("N1", full("N1")), pool(), Map.of(),
                DetailCapability.NOT_APPLICABLE, EvidenceScope.CaseScope.NONE, "침구 단순 변심 반품 배송비? 며칠 안에?");
        assertThat(out).singleElement().satisfies(n -> {
            assertThat(n.status()).isEqualTo(NeedStatus.FULL);
            assertThat(n.enforcement()).isNull();
        });
        NeedDecision decision = new NeedDecision(NeedDecision.Outcome.DECIDED, out, NeedAggregation.basis(out), null, 2,
                0, DetailCapability.NOT_APPLICABLE);
        assertThat(decision.basis()).isEqualTo(AnswerBasisState.GROUNDED);
        assertThat(decision.passages()).anySatisfy(p -> assertThat(p.text()).contains("[확인 필요] 교환 신청 기한"));
    }

    @Test
    @DisplayName("two needs: the settled one is answered, the disagreeing one is left to the seller with the reason")
    void onlyTheDisagreeingPartIsAsked() {
        InquiryNeed fee = new InquiryNeed("N1", "단순 변심 반품 배송비", NeedType.POLICY, "반품 배송비");
        InquiryNeed window = new InquiryNeed("N2", "교환 신청 기한", NeedType.POLICY, "교환 며칠 안에");
        List<NeedResult> out = NeedAggregation.enforce(List.of(fee, window), Map.of("N1", full("N1"), "N2", full("N2")),
                pool(), Map.of(), DetailCapability.NOT_APPLICABLE, EvidenceScope.CaseScope.NONE, null);
        assertThat(out.get(0).status()).isEqualTo(NeedStatus.FULL);
        assertThat(out.get(1).status()).isEqualTo(NeedStatus.NONE);
        assertThat(NeedAggregation.basis(out)).isEqualTo(AnswerBasisState.PARTIAL_ANSWER);
        NeedDecision decision = new NeedDecision(NeedDecision.Outcome.DECIDED, out, NeedAggregation.basis(out), null, 2,
                0, DetailCapability.NOT_APPLICABLE);
        assertThat(decision.openQuestions()).singleElement().asString()
                .startsWith("교환 신청 기한 — ").contains("자료마다 달라요");
        assertThat(decision.answerScope()).contains("N2. 교환 신청 기한 → 이번 답변에서 다루지 않습니다");
    }

    @Test
    @DisplayName("a need asking a settled and an unsettled attribute is answered in part")
    void aNeedAskingBothIsPartial() {
        InquiryNeed need = new InquiryNeed("N1", "반품 배송비와 교환 기한", NeedType.POLICY, "반품 배송비 교환 며칠");
        List<NeedResult> out = NeedAggregation.enforce(List.of(need), Map.of("N1", full("N1")), pool(), Map.of(),
                DetailCapability.NOT_APPLICABLE, EvidenceScope.CaseScope.NONE, null);
        assertThat(out).singleElement().satisfies(n -> {
            assertThat(n.status()).isEqualTo(NeedStatus.PARTIAL);
            assertThat(n.enforcement()).isEqualTo(NeedResult.Enforcement.UNRESOLVED_CONFLICT);
            assertThat(NeedAggregation.answersSomething(n)).isTrue();
        });
    }

    @Test
    @DisplayName("once the seller says which document is current, the same question is answered")
    void aReplacementSettlesIt() {
        KnowledgeState settled = KnowledgeState.of(state.sources(), List.of(new KnowledgeState.Replacement(
                        UUID.randomUUID(), current, old, com.sellerops.knowledge.claim.ClaimAttribute.EXCHANGE_WINDOW,
                        com.sellerops.knowledge.claim.ClaimCondition.GENERAL)), LocalDate.of(2026, 11, 14), null);
        Map<String, EvidenceCandidate> pool = new LinkedHashMap<>();
        pool.put("E1", new EvidenceCandidate("E1", EvidenceCandidate.Kind.ORG_KNOWLEDGE, "교환·반품 안내", CURRENT, current,
                null, null).withMark(settled.markFor(current, CURRENT)));
        pool.put("E2", new EvidenceCandidate("E2", EvidenceCandidate.Kind.ORG_KNOWLEDGE, "옛 공지", OLD, old, null, null)
                .withMark(settled.markFor(old, OLD)));
        InquiryNeed need = new InquiryNeed("N1", "운영 기준 안내", NeedType.POLICY, "운영 기준 안내");
        List<NeedResult> out = NeedAggregation.enforce(List.of(need), Map.of("N1", full("N1")), pool, Map.of(),
                DetailCapability.NOT_APPLICABLE, EvidenceScope.CaseScope.NONE, "교환은 받고 며칠 안에 신청해야 하나요?");
        assertThat(out.get(0).status()).isEqualTo(NeedStatus.FULL);
        assertThat(pool.get("E2").mark().line()).contains("[대체됨]");
        assertThat(pool.get("E1").mark().line()).contains("[현행]");
    }
}
