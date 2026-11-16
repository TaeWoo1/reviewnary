package com.sellerops.agent.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentLlmCallMetrics;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.operator.AgentLlmWireFormat;
import com.sellerops.knowledge.semantic.KnowledgeEmbeddingProperties;
import com.sellerops.product.detail.image.ImageFactExtractionGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunyourWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmWire wire = LlmWire.of(LlmVendor.RUNYOUR);

    @Test
    void allVendorSelectorsKeepRunyourInsteadOfSilentlyChoosingOpenAi() {
        assertThat(LlmVendor.parse(" runyour ")).isEqualTo(LlmVendor.RUNYOUR);
        assertThat(AgentDraftGenerator.Vendor.of("runyour")).isEqualTo(AgentDraftGenerator.Vendor.RUNYOUR);
        assertThat(AgentLlmWireFormat.Vendor.of("runyour")).isEqualTo(AgentLlmWireFormat.Vendor.RUNYOUR);
        assertThat(ImageFactExtractionGenerator.Vendor.of("runyour"))
                .isEqualTo(ImageFactExtractionGenerator.Vendor.RUNYOUR);
        assertThatThrownBy(() -> AgentDraftGenerator.Vendor.of("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wire.endpoint().toString()).isEqualTo("https://api.runyour.ai/v1/chat/completions");
        assertThat(wire.headers("synthetic-key")).containsExactly(java.util.Map.entry("Authorization", "Bearer synthetic-key"));
    }

    @Test
    void routerKeepsPayloadAndSchemaButUsesItsOwnOutputCap() throws Exception {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("answer").put("type", "string");
        var request = new LlmRequest("anthropic/test", "system", "question",
                List.of(new LlmRequest.Image("image/png", "QUJD", "high")), 300, "low",
                LlmRequest.Format.jsonSchema("answer", schema));
        var body = JSON.readTree(wire.body(request));
        assertThat(body.path("model").asText()).isEqualTo("anthropic/test");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(300);
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("response_format").path("json_schema").path("schema")).isEqualTo(schema);
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").path(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,QUJD");
        assertThat(body.toString()).doesNotContain("synthetic-key");
    }

    /** Every count, bound and id list Claude's structured outputs refuse or take, in one schema. */
    private static ObjectNode countedSchema() {
        ObjectNode schema = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.putObject("verdicts").put("type", "array").put("minItems", 2).put("maxItems", 2)
                .putObject("items").put("type", "string").put("maxLength", 40);
        props.putObject("needs").put("type", "array").put("minItems", 5);
        props.putObject("one").put("type", "array").put("minItems", 1);
        props.putObject("none").put("type", "array").put("maxItems", 0);
        props.putObject("score").put("type", "number").put("minimum", 0).put("maximum", 1);
        schema.putArray("required").add("verdicts");
        return schema;
    }

    private static List<String> refusedKeywords(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> found = new java.util.ArrayList<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                boolean refused = java.util.Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
                        "multipleOf", "minLength", "maxLength", "maxItems", "uniqueItems").contains(name)
                        || ("minItems".equals(name) && node.path(name).asInt() > 1);
                if (refused) {
                    found.add(name);
                }
            });
        }
        node.elements().forEachRemaining(child -> found.addAll(refusedKeywords(child)));
        return found;
    }

    @Test
    void aClaudeModelBehindTheRouterGetsClaudesSchemaAndTheCallersSchemaIsUntouched() throws Exception {
        ObjectNode schema = countedSchema();
        ObjectNode before = schema.deepCopy();
        assertThat(refusedKeywords(schema)).as("the fixture states what Claude refuses").hasSize(7);
        var request = new LlmRequest("anthropic/claude-opus-5-5", "system", "question", List.of(), 2400, "minimal",
                LlmRequest.Format.jsonSchema("judge", schema));

        var body = JSON.readTree(wire.body(request));
        var sent = body.path("response_format").path("json_schema");
        assertThat(sent.path("schema").isObject()).isTrue();
        assertThat(refusedKeywords(sent.path("schema"))).isEmpty();
        assertThat(sent.path("schema")).isEqualTo(AnthropicMessagesWire.forClaude(before));
        assertThat(sent.path("schema").path("properties").path("one").path("minItems").asInt()).isEqualTo(1);
        assertThat(sent.path("schema").path("required").path(0).asText()).isEqualTo("verdicts");
        assertThat(sent.path("name").asText()).isEqualTo("judge");
        assertThat(sent.path("strict").asBoolean()).isTrue();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2400);
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(schema).as("the capability's schema object is never edited in place").isEqualTo(before);
        assertThat(JSON.readTree(wire.body(request)).path("response_format").path("json_schema").path("schema"))
                .as("a second build is the same").isEqualTo(sent.path("schema"));
        assertThat(JSON.readTree(wire.body(new LlmRequest(" Anthropic/claude-x ", "s", "u", List.of(), 10, null,
                LlmRequest.Format.jsonSchema("judge", schema)))).path("response_format").path("json_schema")
                .path("schema")).as("the maker prefix in any case").isEqualTo(sent.path("schema"));
    }

    @Test
    void everyOtherRoutedModelKeepsTheRequestByteForByte() throws Exception {
        ObjectNode schema = countedSchema();
        for (String model : List.of("openai/gpt-5", "google/gemini-x", "claude-opus-5-5", "my-anthropic/x")) {
            var request = new LlmRequest(model, "system", "question", List.of(), 2400, "minimal",
                    LlmRequest.Format.jsonSchema("judge", schema));
            ObjectNode expected = (ObjectNode) JSON.readTree(LlmWire.of(LlmVendor.OPENAI).body(request));
            expected.remove("max_completion_tokens");
            expected.remove("reasoning_effort");
            expected.put("max_tokens", 2400);
            assertThat(wire.body(request)).as(model).isEqualTo(expected.toString());
            assertThat(refusedKeywords(JSON.readTree(wire.body(request)))).as(model).hasSize(7);
        }
        var plain = new LlmRequest("anthropic/claude-opus-5-5", "s", "u", List.of(), 10, null,
                LlmRequest.Format.jsonObject());
        assertThat(JSON.readTree(wire.body(plain)).path("response_format").path("type").asText())
                .as("a JSON-object request has no schema to clean").isEqualTo("json_object");
    }

    private static AgentLlmTransport.Response response(int code, String body) {
        return new AgentLlmTransport.Response(code, body, 7);
    }

    @Test
    void unsuccessfulRouterResponsesStayFailuresWithoutASecondVendor() {
        assertThat(wire.read(response(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\"}}]}"))
                .failure()).isEqualTo("TRUNCATED");
        assertThat(wire.read(response(200, "{\"choices\":[{\"message\":{\"refusal\":\"no\"}}]}"))
                .failure()).isEqualTo("REFUSAL");
        assertThat(wire.read(response(429, "{\"error\":{\"code\":\"rate_limit_error\"}}"))
                .failure()).isEqualTo("HTTP_429");
        assertThat(wire.read(response(200, "not-json")).failure()).isEqualTo("UNPARSEABLE");
        assertThat(wire.read(response(200, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"))
                .content()).isEqualTo("{}");
    }

    @Test
    void routerUsageUsesTheSameMeterAndMissingUsageIsUnknown() {
        var metrics = AgentLlmCallMetrics.of(response(200,
                "{\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":45,\"completion_tokens_details\":{\"reasoning_tokens\":6}}}"));
        assertThat(metrics.promptTokens()).isEqualTo(123);
        assertThat(metrics.completionTokens()).isEqualTo(45);
        assertThat(metrics.reasoningTokens()).isEqualTo(6);
        assertThat(AgentLlmCallMetrics.of(response(200, "{}")).promptTokens()).isEqualTo(AgentLlmCallMetrics.UNREPORTED);
    }

    @Test
    void embeddingsHaveTheirOwnEndpointAndCacheNamespace() throws Exception {
        var embeddings = EmbeddingWire.of(LlmVendor.RUNYOUR);
        assertThat(embeddings.endpoint().toString()).isEqualTo("https://api.runyour.ai/v1/embeddings");
        assertThat(JSON.readTree(embeddings.body("embedding-test", 2, List.of("manual")))
                .path("input").path(0).asText()).isEqualTo("manual");
        assertThat(embeddings.read(response(200, "{\"data\":[{\"index\":0,\"embedding\":[0.5,1]}]}"), 1))
                .singleElement().satisfies(v -> assertThat(v).containsExactly(0.5f, 1f));
        assertThat(embeddings.read(response(200, "{}"), 1)).isEmpty();
        var props = new KnowledgeEmbeddingProperties(false, "", "embedding-test", "", 2, "RUNYOUR");
        assertThat(props.storedModel()).isEqualTo("runyour:embedding-test");
        assertThatThrownBy(() -> new KnowledgeEmbeddingProperties(true, "*", "", "test-key", 2, "RUNYOUR"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("embedding model");
    }
}
