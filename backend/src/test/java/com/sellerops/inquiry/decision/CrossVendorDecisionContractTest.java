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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Inquiry decision keeps one result contract whatever the vendor</b> (owner, 2026-10-29: the shared model connection,
 * proven first on this capability). The prompt, the schema and the parser are the capability's own; only the wire
 * differs. The same answer, arriving in OpenAI's envelope or in Anthropic's, must give the same needs and the same
 * verdicts, and the same failure words when there is no answer.
 */
class CrossVendorDecisionContractTest {

    private static InquiryDecisionProperties props(String vendor) {
        return new InquiryDecisionProperties(true, ORG.toString(), "model-x", "key-x", 800, 6000, "minimal",
                InquiryDecisionPrompt.JUDGE_V2, "", "json_schema", vendor);
    }

    /** Claude's envelope: a thinking block first, the answer in text blocks after it. */
    private static String claude(String content, String stop) {
        ObjectNode root = JSON.createObjectNode();
        var blocks = root.putArray("content");
        blocks.addObject().put("type", "thinking").put("thinking", "").put("signature", "s");
        if (content != null) {
            blocks.addObject().put("type", "text").put("text", content);
        }
        root.put("stop_reason", stop);
        root.putObject("usage").put("input_tokens", 10).put("output_tokens", 5);
        return root.toString();
    }

    private record Sent(URI uri, Map<String, String> headers, String body) {
    }

    private static AgentLlmTransport answering(List<Sent> sent, int status, String body) {
        return (uri, headers, json) -> {
            sent.add(new Sent(uri, headers, json));
            return new AgentLlmTransport.Response(status, body, 7);
        };
    }

    private static final String VERDICTS = "{\"verdicts\":[" + V1 + "," + V2 + "]}";
    private static final String PLAN = "{\"needs\":[{\"id\":\"x\",\"ask\":\"사이즈\",\"type\":\"PRODUCT_SPEC\",\"search\":\"사이즈 표\"}]}";

    @Test
    @DisplayName("the same answer gives the same verdicts and the same needs from either vendor")
    void sameAnswerSameResult() {
        List<Sent> sent = new ArrayList<>();
        InquiryDecisionGenerator openai = new InquiryDecisionGenerator(
                answering(sent, 200, JudgeIntegrationContractTest.envelope(VERDICTS)), props("OPENAI"));
        InquiryDecisionGenerator claude = new InquiryDecisionGenerator(
                answering(sent, 200, claude(VERDICTS, "end_turn")), props("ANTHROPIC"));

        var a = openai.judge(ORG, openai.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS), List.of("N1", "N2"), 1, 0);
        var b = claude.judge(ORG, claude.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS), List.of("N1", "N2"), 1, 0);
        assertThat(a.failure()).isNull();
        assertThat(b.failure()).isNull();
        assertThat(b.value()).isEqualTo(a.value());

        InquiryDecisionGenerator openaiPlan = new InquiryDecisionGenerator(
                answering(sent, 200, JudgeIntegrationContractTest.envelope(PLAN)), props("OPENAI"));
        InquiryDecisionGenerator claudePlan = new InquiryDecisionGenerator(
                answering(sent, 200, claude(PLAN, "end_turn")), props("ANTHROPIC"));
        assertThat(claudePlan.plan(ORG, claudePlan.planBody("q")).value())
                .isEqualTo(openaiPlan.plan(ORG, openaiPlan.planBody("q")).value())
                .hasSize(1);

        assertThat(sent.get(0).uri().getHost()).isEqualTo("api.openai.com");
        assertThat(sent.get(1).uri().getHost()).isEqualTo("api.anthropic.com");
        assertThat(sent.get(1).headers()).containsEntry("x-api-key", "key-x").containsKey("anthropic-version")
                .doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("no answer is the same failure word from either vendor")
    void sameFailureWords() {
        assertThat(judge("ANTHROPIC", 200, claude("{\"verdicts\":[", "max_tokens"))).isEqualTo("TRUNCATED");
        assertThat(judge("ANTHROPIC", 200, claude(null, "refusal"))).isEqualTo("REFUSAL");
        assertThat(judge("ANTHROPIC", 200, claude(null, "end_turn"))).as("thinking alone").isEqualTo("EMPTY");
        assertThat(judge("ANTHROPIC", 429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}"))
                .isEqualTo("HTTP_429");
        assertThat(judge("ANTHROPIC", 0, "timeout")).isEqualTo("TRANSPORT");
        assertThat(judge("ANTHROPIC", 200, "not json")).isEqualTo("UNPARSEABLE");
        assertThat(judge("ANTHROPIC", 200, claude("{\"verdicts\":[" + V1 + "]}", "end_turn")))
                .as("the strict verdict set is the capability's own, whatever the vendor").isEqualTo("VERDICT_SET");
    }

    private static String judge(String vendor, int status, String body) {
        InquiryDecisionGenerator g = new InquiryDecisionGenerator(answering(new ArrayList<>(), status, body),
                props(vendor));
        return g.judge(ORG, g.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS), List.of("N1", "N2"), 1, 0).failure();
    }

    @Test
    @DisplayName("Claude gets the same instruction and schema, in its own fields, without the constraints it refuses")
    void claudeGetsTheSameRequestInItsSpelling() throws Exception {
        InquiryDecisionGenerator openai = new InquiryDecisionGenerator(null, props("OPENAI"));
        InquiryDecisionGenerator claude = new InquiryDecisionGenerator(null, props("ANTHROPIC"));
        JsonNode o = JSON.readTree(openai.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS));
        JsonNode c = JSON.readTree(claude.judgeBody("q", NEEDS, EVIDENCE, PRECEDENTS));

        assertThat(c.path("system").asText()).isEqualTo(o.path("messages").path(0).path("content").asText());
        assertThat(c.path("messages").path(0).path("content").asText())
                .isEqualTo(o.path("messages").path(1).path("content").asText());
        assertThat(c.path("max_tokens").asInt()).isEqualTo(o.path("max_completion_tokens").asInt());
        assertThat(c.path("output_config").path("effort").asText()).as("minimal has no Claude level").isEqualTo("low");
        assertThat(c.has("response_format")).isFalse();
        assertThat(c.has("reasoning_effort")).isFalse();

        JsonNode openaiSchema = o.path("response_format").path("json_schema").path("schema");
        JsonNode claudeSchema = c.path("output_config").path("format").path("schema");
        assertThat(c.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(openaiSchema.path("properties").path("verdicts").has("maxItems")).isTrue();
        assertThat(claudeSchema.path("properties").path("verdicts").has("maxItems")).isFalse();
        assertThat(claudeSchema.path("properties").path("verdicts").has("minItems")).isFalse();
        assertThat(claudeSchema.path("properties").path("verdicts").path("items").path("properties").path("need")
                .path("enum")).as("the closed id list stays").isEqualTo(openaiSchema.path("properties")
                .path("verdicts").path("items").path("properties").path("need").path("enum"));
        assertThat(claudeSchema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("an unknown vendor refuses to boot instead of becoming OpenAI")
    void unknownVendorRefuses() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> props("UPSTAGE"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UPSTAGE");
        assertThat(props("").vendor()).isEqualTo(com.sellerops.agent.llm.provider.LlmVendor.OPENAI);
    }
}
