package com.sellerops.inquiry.decision;

import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.EVIDENCE;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.JSON;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.NEEDS;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.ORG;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.PRECEDENTS;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.V1;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.V2;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>CoverageJudge v4: v2 held to what the customer asked</b> (2026-11-02, case-eval-r11u: 「20박스 사면 할인되나요」 came
 * back PARTIAL for want of a rate nobody asked for). Two rules differ from v2; every other line and the answer's shape
 * are v2's, so a comparison measures the rules.
 */
class CoverageJudgeV4Test {

    private static String rules(String version) {
        String p = InquiryDecisionPrompt.judgeSystem(version);
        return p.substring(0, p.indexOf("출력은 "));
    }

    @Test
    @DisplayName("v4 adds the received and asked-scope rules and narrows the seller-decision rule — nothing else differs")
    void threeRulesDiffer() {
        List<String> v2 = List.of(rules(InquiryDecisionPrompt.JUDGE_V2).split("\n"));
        List<String> v4 = List.of(rules(InquiryDecisionPrompt.JUDGE_V4).split("\n"));
        assertThat(v4).hasSize(v2.size() + 2);
        List<String> changed = v4.stream().filter(line -> !v2.contains(line)).toList();
        assertThat(changed).hasSize(3);
        assertThat(changed.get(0)).startsWith("- 이 고객의 개별 주문 상태나 판매자의 판단이 있어야 답이 되는 need는, 일반 기준이 있어도 "
                + "FULL이 아닙니다. 다만 판매자가 그 판단을 조건과 함께 기준으로 적어 두었고");
        assertThat(changed.get(1)).startsWith("- received(문의 받은 때)가 주어지면").contains("customer_input")
                .contains("공휴일인지는");
        assertThat(changed.get(2)).startsWith("- need가 묻는 것까지만 판정합니다.").contains("missing에도 쓰지 않습니다");
        String v2Output = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V2);
        String v4Output = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V4);
        assertThat(v4Output.substring(v4Output.indexOf("출력은 "))).isEqualTo(v2Output.substring(v2Output.indexOf("출력은 ")));
    }

    @Test
    @DisplayName("no product vocabulary in v4 either")
    void noDomainWords() {
        String p = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V4);
        for (String word : List.of("몰딩", "디스펜서", "전선", "종이컵", "호수", "선바로", "mm", "가닥", "박스", "할인")) {
            assertThat(p).as(word).doesNotContain(word);
        }
    }

    @Test
    @DisplayName("v6 is the default instruction since 2026-11-06, v4 and v5 stay selectable, and an unknown name refuses to boot")
    void theDefault() {
        assertThat(new InquiryDecisionProperties(true, ORG.toString(), "m", "k", 800, 2400, "low").judgePrompt())
                .isEqualTo(InquiryDecisionPrompt.JUDGE_V6);
        assertThat(new InquiryDecisionProperties(true, ORG.toString(), "m", "k", 800, 2400, "low", "", "")
                .judgePrompt()).isEqualTo(InquiryDecisionPrompt.JUDGE_V6);
        assertThat(new InquiryDecisionProperties(true, ORG.toString(), "m", "k", 800, 2400, "low",
                InquiryDecisionPrompt.JUDGE_V5, "").judgePrompt()).isEqualTo(InquiryDecisionPrompt.JUDGE_V5);
        assertThat(new InquiryDecisionProperties(true, ORG.toString(), "m", "k", 800, 2400, "low",
                InquiryDecisionPrompt.JUDGE_V4, "").judgePrompt()).isEqualTo(InquiryDecisionPrompt.JUDGE_V4);
        assertThat(new InquiryDecisionProperties(true, ORG.toString(), "m", "k", 800, 2400, "low",
                InquiryDecisionPrompt.JUDGE_V2, "").judgePrompt()).as("v2 stays selectable")
                .isEqualTo(InquiryDecisionPrompt.JUDGE_V2);
    }

    @Test
    @DisplayName("through the generator: v4's rules go out, and v2's list answer reads as the same verdicts")
    void throughTheGenerator() throws Exception {
        String listed = "{\"verdicts\":[" + V1 + "," + V2 + "]}";
        String claude = JSON.createObjectNode().put("stop_reason", "end_turn").set("content",
                JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", listed))).toString();
        String[] sent = new String[1];
        AgentLlmTransport t = (uri, headers, json) -> {
            sent[0] = json;
            return new AgentLlmTransport.Response(200, claude, 3);
        };
        InquiryDecisionGenerator g = new InquiryDecisionGenerator(t, new InquiryDecisionProperties(true,
                ORG.toString(), "m", "k", 800, 6000, "low", InquiryDecisionPrompt.JUDGE_V4, "", "json_schema",
                "ANTHROPIC"));
        var a = g.judge(ORG, g.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS), List.of("N1", "N2"), 1, 0);
        assertThat(a.failure()).isNull();
        assertThat(a.value()).isEqualTo(InquiryDecisionGenerator.parseJudge(listed, List.of("N1", "N2")).verdicts());
        JsonNode body = JSON.readTree(sent[0]);
        assertThat(body.toString()).contains("need가 묻는 것까지만 판정합니다");
    }

    @Test
    @DisplayName("when the inquiry arrived goes to the judge as its own field, and nothing changes without it")
    void theReceivedLine() {
        InquiryDecisionGenerator g = new InquiryDecisionGenerator(null, new InquiryDecisionProperties(true,
                ORG.toString(), "m", "k", 800, 6000, "low", InquiryDecisionPrompt.JUDGE_V4, "", "json_schema",
                "ANTHROPIC"));
        String moment = InquiryMoment.of(java.time.Instant.parse("2026-11-01T10:37:00Z"));
        assertThat(g.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS, moment)).contains("\\\"received\\\"")
                .contains("2026년 11월 1일 일요일 오후 7시 37분(한국 시간) · 주말");
        assertThat(g.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS, null))
                .isEqualTo(g.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS)).doesNotContain("\\\"received\\\"");
    }
}
