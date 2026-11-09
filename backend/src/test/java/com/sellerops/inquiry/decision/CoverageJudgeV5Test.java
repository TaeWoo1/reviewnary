package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.draft.AnswerBasisState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>CoverageJudge v5: v4, and a value the evidence says is not settled yet</b> (2026-11-05, eleventh review's re-eval: the
 * seller recorded 「새 상품 도착일은 아직 정할 수 없음」 and the reply said nothing of it). One rule is added to v4; every other
 * line and the answer's shape are v4's, so a comparison measures that rule.
 */
class CoverageJudgeV5Test {

    private static String rules(String version) {
        String p = InquiryDecisionPrompt.judgeSystem(version);
        return p.substring(0, p.indexOf("출력은 "));
    }

    @Test
    @DisplayName("v5 adds only the 「not settled yet」 rule to v4: PARTIAL, the record cited, the value missing — and an "
            + "absent value is never read as 「not yet」")
    void oneRuleAdded() {
        List<String> v4 = List.of(rules(InquiryDecisionPrompt.JUDGE_V4).split("\n"));
        List<String> v5 = List.of(rules(InquiryDecisionPrompt.JUDGE_V5).split("\n"));
        assertThat(v5).hasSize(v4.size() + 1);
        assertThat(v5).containsAll(v4);
        List<String> added = v5.stream().filter(line -> !v4.contains(line)).toList();
        assertThat(added).singleElement().satisfies(line -> assertThat(line)
                .startsWith("- need가 값 자체(언제 · 얼마 · 어느 것)를 묻는데 근거(판매자 확인 기록 포함)가 그 값이 아직 "
                        + "정해지지 않았다고 적고 있으면 그 need는 PARTIAL입니다.")
                .contains("evidence에 그 근거를 넣고").contains("아직 남은 그 값은 missing에")
                // Asked whether it is settled, 「not yet」 is the whole answer (twelfth review, 2026-11-05).
                .contains("need가 정해졌는지 여부를 묻는다면 「아직 정해지지 않았다」가 그 답이므로 이 규칙으로 낮추지 않습니다")
                .contains("근거에 값이 없다는 것만으로는 정해지지 않았다고 보지 않습니다"));
        String v4Output = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V4);
        String v5Output = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V5);
        assertThat(v5Output.substring(v5Output.indexOf("출력은 "))).isEqualTo(v4Output.substring(v4Output.indexOf("출력은 ")));
    }

    /**
     * Synthetic inputs (twelfth review): the two verdicts v5 tells apart, and what each does downstream. The seller recorded
     * 「새 상품 도착일은 아직 정할 수 없음」. Asked when it arrives, PARTIAL: the question stays open for the seller, and the
     * drafter may say it is not settled only because the record says so. Asked whether the day is set, FULL: 「not yet」
     * answers it, and nothing is left open.
     */
    @Test
    @DisplayName("asked when: PARTIAL keeps the question open; asked whether settled: FULL closes it — the record is the "
            + "evidence either way")
    void whenOrWhether() {
        // As the collector builds it: the seller's record, attributed to the order this inquiry is about.
        String orderKey = "inquiry:" + java.util.UUID.randomUUID();
        EvidenceCandidate record = new EvidenceCandidate(null, EvidenceCandidate.Kind.SELLER_CHECK, "판매자 확인",
                "판매자가 판매자센터에서 직접 확인한 이 문의의 결과(이 문의에만 해당, 채널이 확인한 사실 아님): 접수·진행 중 — "
                        + "교환 접수함. 새 상품 도착일은 아직 정할 수 없음", null, null, "IN_PROGRESS",
                EvidenceScope.order(orderKey));
        EvidenceScope.CaseScope thisCase = new EvidenceScope.CaseScope(null, orderKey);

        InquiryDecisionEngineTest.ScriptedModel when = new InquiryDecisionEngineTest.ScriptedModel(
                List.of(InquiryDecisionEngineTest.need("N1", "교환 상품이 언제 도착하는지", NeedType.ORDER_STATE)),
                InquiryDecisionEngineTest.verdicts(InquiryDecisionEngineTest.verdict("N1", NeedStatus.PARTIAL, "E1")));
        NeedDecision asked = InquiryDecisionEngine.decide(java.util.UUID.randomUUID(), "문의", when,
                needs -> new InquiryDecisionEngine.Pool(List.of(record), List.of()), DetailCapability.NOT_ACQUIRED,
                thisCase);
        assertThat(asked.basis()).isEqualTo(AnswerBasisState.PARTIAL_ANSWER);
        assertThat(asked.openQuestions()).as("saying 「not yet」 closes nothing").hasSize(1);
        assertThat(asked.answerScope()).contains("확정하기 어렵다는 말은 근거(판매자 확인 기록 포함)에 그렇게 적혀 있을 때만");
        assertThat(asked.toTakeUp()).extracting(n -> n.need().id()).containsExactly("N1");

        InquiryDecisionEngineTest.ScriptedModel whether = new InquiryDecisionEngineTest.ScriptedModel(
                List.of(InquiryDecisionEngineTest.need("N1", "교환 상품 도착일이 정해졌는지", NeedType.ORDER_STATE)),
                InquiryDecisionEngineTest.verdicts(InquiryDecisionEngineTest.verdict("N1", NeedStatus.FULL, "E1")));
        NeedDecision settled = InquiryDecisionEngine.decide(java.util.UUID.randomUUID(), "문의", whether,
                needs -> new InquiryDecisionEngine.Pool(List.of(record), List.of()), DetailCapability.NOT_ACQUIRED,
                thisCase);
        assertThat(settled.openQuestions()).as("「not yet」 is the answer to whether").isEmpty();
        assertThat(settled.answerScope()).doesNotContain("근거가 답하는 데까지만");
    }

    @Test
    @DisplayName("no product vocabulary in v5 either")
    void noDomainWords() {
        String p = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V5);
        for (String word : List.of("몰딩", "디스펜서", "전선", "종이컵", "호수", "선바로", "mm", "가닥", "박스", "할인", "도착", "교환")) {
            assertThat(p).as(word).doesNotContain(word);
        }
    }
}
