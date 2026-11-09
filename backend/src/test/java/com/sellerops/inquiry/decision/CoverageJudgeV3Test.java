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
 * <b>CoverageJudge v3: v2's rules, the answer keyed by need id</b> (owner, 2026-10-29). The count v2 states as an array
 * bound becomes required properties, which Claude's structured outputs keep; the code's strict set check stays behind it.
 */
class CoverageJudgeV3Test {

    private static InquiryDecisionProperties props(String version, String vendor) {
        return new InquiryDecisionProperties(true, ORG.toString(), "m", "k", 800, 6000, "low", version, "",
                "json_schema", vendor);
    }

    /** v1/v2 verdict objects without their {@code need} field, keyed by it. */
    private static final String KEYED = "{\"verdicts\":{\"N1\":" + V1.replace("\"need\":\"N1\",", "") + ",\"N2\":"
            + V2.replace("\"need\":\"N2\",", "") + "}}";

    private static String failureOf(String content) {
        return InquiryDecisionGenerator.parseKeyedJudge(content, List.of("N1", "N2")).failure();
    }

    @Test
    @DisplayName("the same verdicts as v2, read from the keyed answer")
    void keyedAnswerReadsAsV2() {
        var keyed = InquiryDecisionGenerator.parseKeyedJudge(KEYED, List.of("N1", "N2"));
        var listed = InquiryDecisionGenerator.parseJudge("{\"verdicts\":[" + V1 + "," + V2 + "]}", List.of("N1", "N2"));
        assertThat(keyed.failure()).isNull();
        assertThat(keyed.verdicts()).isEqualTo(listed.verdicts());
    }

    @Test
    @DisplayName("a missing need, an invented need and a need answered twice still fail the set")
    void theSetCheckStays() {
        assertThat(failureOf("{\"verdicts\":{\"N1\":" + V1.replace("\"need\":\"N1\",", "") + "}}"))
                .isEqualTo("VERDICT_SET");
        String one = V1.replace("\"need\":\"N1\",", "");
        String two = V2.replace("\"need\":\"N2\",", "");
        assertThat(failureOf("{\"verdicts\":{\"N1\":" + one + ",\"N2\":" + two + ",\"N9\":" + two + "}}"))
                .as("an invented need").isEqualTo("VERDICT_SET");
        assertThat(failureOf("{\"verdicts\":{\"N1\":" + one + ",\"N1\":" + two + "}}")).as("a doubled key")
                .isEqualTo("VERDICT_SET");
        assertThat(failureOf("{\"verdicts\":[" + V1 + "," + V2 + "]}")).as("v2's list is not v3's answer")
                .isEqualTo("UNPARSEABLE");
        assertThat(failureOf("")).isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("the schema's count is required keys — and Claude's copy keeps them")
    void theCountIsTheShape() throws Exception {
        for (String vendor : List.of("OPENAI", "ANTHROPIC")) {
            JsonNode body = JSON.readTree(new InquiryDecisionGenerator(null, props(InquiryDecisionPrompt.JUDGE_V3, vendor))
                    .judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS));
            JsonNode schema = "OPENAI".equals(vendor) ? body.path("response_format").path("json_schema").path("schema")
                    : body.path("output_config").path("format").path("schema");
            JsonNode verdicts = schema.path("properties").path("verdicts");
            assertThat(verdicts.path("type").asText()).as(vendor).isEqualTo("object");
            assertThat(verdicts.path("additionalProperties").asBoolean(true)).as(vendor).isFalse();
            assertThat(verdicts.path("required")).as(vendor).extracting(JsonNode::asText).containsExactly("N1", "N2");
            assertThat(verdicts.path("properties").path("N1").path("properties").has("need")).as(vendor).isFalse();
            assertThat(verdicts.path("properties").path("N1").path("required")).as(vendor).isNotEmpty();
        }
    }

    @Test
    @DisplayName("v3 sends v2's rules word for word; only the output line differs")
    void onlyTheShapeDiffers() {
        String v2 = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V2);
        String v3 = InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V3);
        assertThat(v3.substring(0, v3.indexOf("출력은 "))).isEqualTo(v2.substring(0, v2.indexOf("출력은 ")));
        assertThat(v3).contains("need id를 키로").doesNotContain("\"verdicts\":[");
    }

    @Test
    @DisplayName("end to end through the generator: a keyed Claude answer is the same verdicts")
    void throughTheGenerator() {
        String claude = JSON.createObjectNode().put("stop_reason", "end_turn").set("content",
                JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", KEYED))).toString();
        AgentLlmTransport t = (uri, headers, json) -> new AgentLlmTransport.Response(200, claude, 3);
        InquiryDecisionGenerator g = new InquiryDecisionGenerator(t, props(InquiryDecisionPrompt.JUDGE_V3, "ANTHROPIC"));
        var a = g.judge(ORG, g.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS), List.of("N1", "N2"), 1, 0);
        assertThat(a.failure()).isNull();
        assertThat(a.value()).containsOnlyKeys("N1", "N2");
    }
}
