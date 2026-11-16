package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.currency.KnowledgeState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** coverage-judge/v7 (2026-11-15): v6 plus one rule about a candidate's {@code state}, and nothing else. */
class CoverageJudgeV7Test {

    @Test
    @DisplayName("v7 adds exactly one rule line to v6 and keeps v6's answer shape")
    void oneRuleAdded() {
        List<String> v6 = List.of(InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V6).split("\n"));
        List<String> v7 = new ArrayList<>(List.of(InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V7)
                .split("\n")));
        List<String> added = new ArrayList<>(v7);
        added.removeAll(v6);
        assertThat(added).singleElement().asString().contains("state").contains("[확인 필요]").contains("[대체됨]");
        v7.removeAll(added);
        assertThat(v7).isEqualTo(v6);
        assertThat(InquiryDecisionPrompt.judgeSchema(InquiryDecisionPrompt.JUDGE_V7, List.of("N1"), List.of("E1"),
                List.of()).toString()).contains("\"settled_by\"").contains("coverage_judge_v7");
    }

    @Test
    @DisplayName("a candidate's state travels beside its text; a candidate without one is sent exactly as before")
    void theStateTravelsOnlyWhenThere() {
        InquiryNeed need = new InquiryNeed("N1", "교환 기한", NeedType.POLICY, "교환 기한");
        EvidenceCandidate plain = new EvidenceCandidate("E1", EvidenceCandidate.Kind.ORG_KNOWLEDGE, "배송 안내",
                "출고 후 1~2일 안에 도착합니다.", null, null, null);
        String before = InquiryDecisionPrompt.judgeUser("q", List.of(need), List.of(plain), List.of(), null);
        assertThat(before).doesNotContain("\"state\"");
        EvidenceCandidate marked = plain.withMark(new KnowledgeState.Mark(List.of("[확인 필요] 교환 신청 기한이 자료마다 달라요"),
                Map.of(), java.util.Set.of()));
        String after = InquiryDecisionPrompt.judgeUser("q", List.of(need), List.of(marked), List.of(), null);
        assertThat(after).contains("\"state\":\"[확인 필요] 교환 신청 기한이 자료마다 달라요\"");
        assertThat(after.replace(",\"state\":\"[확인 필요] 교환 신청 기한이 자료마다 달라요\"", "")).isEqualTo(before);
    }
}
