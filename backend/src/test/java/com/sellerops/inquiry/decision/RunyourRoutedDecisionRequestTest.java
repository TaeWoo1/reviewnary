package com.sellerops.inquiry.decision;

import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.EVIDENCE;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.JSON;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.ORG;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.PRECEDENTS;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.V1;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.V2;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.envelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The plan and the judge as they leave for Runyour's Claude</b> (2026-11-15). The first real evaluation call was
 * refused (HTTP 400: {@code minItems} 2 and 5, Claude takes 0 or 1). Offline, with the configuration the evaluation
 * runs — judge v7, json_schema, an {@code anthropic/} model — no schema keyword Claude refuses may leave, the schema
 * must still be there, and the server's own count check must still fail a short answer.
 */
class RunyourRoutedDecisionRequestTest {

    private static final Set<String> REFUSED = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minLength", "maxLength", "maxItems", "uniqueItems");

    private static InquiryDecisionProperties props(String model) {
        return new InquiryDecisionProperties(true, ORG.toString(), model, "key-x", 800, 2400, "minimal",
                InquiryDecisionPrompt.JUDGE_V8, "", "json_schema", "RUNYOUR");
    }

    private static InquiryDecisionGenerator generator(String model, AgentLlmTransport t) {
        return new InquiryDecisionGenerator(t, props(model));
    }

    private static List<InquiryNeed> needs(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> new InquiryNeed("N" + i, "질문" + i, NeedType.POLICY, "검색" + i))
                .toList();
    }

    static List<String> refused(JsonNode node) {
        List<String> found = new ArrayList<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                if (REFUSED.contains(name) || ("minItems".equals(name) && node.path(name).asInt() > 1)) {
                    found.add(name);
                }
            });
        }
        node.elements().forEachRemaining(child -> found.addAll(refused(child)));
        return found;
    }

    private static JsonNode schemaOf(String body) throws Exception {
        JsonNode schema = JSON.readTree(body).path("response_format").path("json_schema").path("schema");
        assertThat(schema.isObject()).as("a schema still leaves — the check below must not pass on nothing").isTrue();
        assertThat(schema.path("properties").size()).isPositive();
        return schema;
    }

    private static final AgentLlmTransport UNUSED = (u, h, b) -> new AgentLlmTransport.Response(500, "", 1);

    @Test
    @DisplayName("judge v7 with 2 and 5 needs and no precedents leaves without a keyword Claude refuses")
    void theJudgeLeavesClean() throws Exception {
        for (int n : new int[] {2, 5}) {
            InquiryDecisionGenerator claude = generator("anthropic/claude-opus-5-5", UNUSED);
            JsonNode sent = schemaOf(claude.judgeBody("q", needs(n), EVIDENCE, PRECEDENTS));
            assertThat(refused(sent)).as("%d needs", n).isEmpty();
            assertThat(sent.toString()).as("the need ids are still an enum").contains("\"N" + n + "\"");

            JsonNode routedElsewhere = schemaOf(generator("openai/gpt-5", UNUSED)
                    .judgeBody("q", needs(n), EVIDENCE, PRECEDENTS));
            assertThat(refused(routedElsewhere)).as("the same judge for a non-Claude model is unchanged — "
                    + "this is the shape Claude refused").contains("minItems", "maxItems");
        }
    }

    @Test
    @DisplayName("the plan leaves with its schema unchanged — it carried nothing Claude refuses")
    void thePlanLeavesUnchanged() throws Exception {
        JsonNode claude = schemaOf(generator("anthropic/claude-opus-5-5", UNUSED).planBody("q"));
        JsonNode other = schemaOf(generator("openai/gpt-5", UNUSED).planBody("q"));
        assertThat(refused(claude)).isEmpty();
        assertThat(claude).isEqualTo(other);
    }

    @Test
    @DisplayName("the server still checks the verdict set the schema no longer states")
    void theServerStillCountsVerdicts() {
        List<String> ids = List.of("N1", "N2");
        InquiryDecisionGenerator full = generator("anthropic/claude-opus-5-5",
                (u, h, b) -> new AgentLlmTransport.Response(200, envelope("{\"verdicts\":[" + V1 + "," + V2 + "]}"), 7));
        assertThat(full.judge(ORG, full.judgeBody("q", needs(2), EVIDENCE, PRECEDENTS), ids, 1, 0).failure()).isNull();

        InquiryDecisionGenerator shortOne = generator("anthropic/claude-opus-5-5",
                (u, h, b) -> new AgentLlmTransport.Response(200, envelope("{\"verdicts\":[" + V1 + "]}"), 7));
        assertThat(shortOne.judge(ORG, shortOne.judgeBody("q", needs(2), EVIDENCE, PRECEDENTS), ids, 1, 0).failure())
                .as("a missing verdict").isEqualTo("VERDICT_SET");

        InquiryDecisionGenerator doubled = generator("anthropic/claude-opus-5-5",
                (u, h, b) -> new AgentLlmTransport.Response(200, envelope("{\"verdicts\":[" + V1 + "," + V1 + "]}"), 7));
        assertThat(doubled.judge(ORG, doubled.judgeBody("q", needs(2), EVIDENCE, PRECEDENTS), ids, 1, 0).failure())
                .as("a doubled verdict").isEqualTo("VERDICT_SET");
    }
}
