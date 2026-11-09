package com.sellerops.agent.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The two wires write one request and read one answer the same way</b> (shared model connection, 2026-10-29). Each
 * capability's own contract test proves its result is vendor-independent; this proves the wires underneath.
 */
class LlmWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode schema() {
        ObjectNode s = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        s.putArray("required").add("items");
        ObjectNode items = s.putObject("properties").putObject("items").put("type", "array").put("minItems", 2)
                .put("maxItems", 2);
        items.putObject("items").put("type", "string").put("maxLength", 40).putArray("enum").add("A").add("B");
        return s;
    }

    private static LlmRequest request(LlmRequest.Format format, List<LlmRequest.Image> images) {
        return new LlmRequest("m", "SYS", "USER", images, 900, "minimal", format);
    }

    @Test
    @DisplayName("the same request: the system and user turns, budget, effort and schema, each in its vendor's fields")
    void oneRequestTwoSpellings() throws Exception {
        LlmRequest r = request(LlmRequest.Format.jsonSchema("shape", schema()), List.of());
        JsonNode o = JSON.readTree(LlmWire.of(LlmVendor.OPENAI).body(r));
        JsonNode a = JSON.readTree(LlmWire.of(LlmVendor.ANTHROPIC).body(r));

        assertThat(o.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(o.path("messages").path(1).path("content").asText()).isEqualTo("USER");
        assertThat(o.path("max_completion_tokens").asInt()).isEqualTo(900);
        assertThat(o.path("reasoning_effort").asText()).isEqualTo("minimal");
        assertThat(o.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(o.path("response_format").path("json_schema").path("schema")).isEqualTo(schema());

        assertThat(a.path("system").asText()).isEqualTo("SYS");
        assertThat(a.path("messages")).hasSize(1);
        assertThat(a.path("messages").path(0).path("content").asText()).isEqualTo("USER");
        assertThat(a.path("max_tokens").asInt()).isEqualTo(900);
        assertThat(a.path("output_config").path("effort").asText()).isEqualTo("low");
        JsonNode claude = a.path("output_config").path("format").path("schema");
        assertThat(claude.path("properties").path("items").has("maxItems")).isFalse();
        assertThat(claude.path("properties").path("items").has("minItems")).isFalse();
        assertThat(claude.path("properties").path("items").path("items").has("maxLength")).isFalse();
        assertThat(claude.path("properties").path("items").path("items").path("enum")).hasSize(2);
        assertThat(schema().path("properties").path("items").has("maxItems")).as("the caller's schema is untouched")
                .isTrue();
    }

    @Test
    @DisplayName("JSON-object mode is OpenAI's response_format; Claude has none and gets no format field")
    void jsonObjectMode() throws Exception {
        LlmRequest r = request(LlmRequest.Format.jsonObject(), List.of());
        assertThat(JSON.readTree(LlmWire.of(LlmVendor.OPENAI).body(r)).path("response_format").path("type").asText())
                .isEqualTo("json_object");
        assertThat(JSON.readTree(LlmWire.of(LlmVendor.ANTHROPIC).body(r)).path("output_config").has("format")).isFalse();
    }

    @Test
    @DisplayName("an image travels as a data URL to OpenAI and as a base64 block before the text to Claude")
    void images() throws Exception {
        LlmRequest r = request(LlmRequest.Format.jsonObject(),
                List.of(new LlmRequest.Image("image/png", "QUJD", "high")));
        JsonNode o = JSON.readTree(LlmWire.of(LlmVendor.OPENAI).body(r)).path("messages").path(1).path("content");
        assertThat(o.path(0).path("type").asText()).isEqualTo("text");
        assertThat(o.path(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,QUJD");
        assertThat(o.path(1).path("image_url").path("detail").asText()).isEqualTo("high");
        JsonNode a = JSON.readTree(LlmWire.of(LlmVendor.ANTHROPIC).body(r)).path("messages").path(0).path("content");
        assertThat(a.path(0).path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(a.path(0).path("source").path("data").asText()).isEqualTo("QUJD");
        assertThat(a.path(1).path("text").asText()).isEqualTo("USER");

        LlmRequest imageOnly = new LlmRequest("m", "SYS", null, List.of(new LlmRequest.Image("image/png", "QUJD", null)),
                10, null, LlmRequest.Format.jsonObject());
        assertThat(JSON.readTree(LlmWire.of(LlmVendor.ANTHROPIC).body(imageOnly)).path("messages").path(0)
                .path("content")).as("no empty text block").hasSize(1);
    }

    private static AgentLlmTransport.Response ok(String body) {
        return new AgentLlmTransport.Response(200, body, 1);
    }

    @Test
    @DisplayName("the same answer and the same failure words from either envelope")
    void oneReading() {
        LlmWire openai = LlmWire.of(LlmVendor.OPENAI);
        LlmWire claude = LlmWire.of(LlmVendor.ANTHROPIC);
        assertThat(openai.read(ok("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}"))
                .content()).isEqualTo("{}");
        assertThat(claude.read(ok("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},{\"type\":\"text\","
                + "\"text\":\"{}\"}],\"stop_reason\":\"end_turn\"}")).content()).isEqualTo("{}");

        assertThat(openai.read(ok("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\"}}]}"))
                .failure()).isEqualTo("TRUNCATED");
        assertThat(claude.read(ok("{\"content\":[{\"type\":\"text\",\"text\":\"{\"}],\"stop_reason\":\"max_tokens\"}"))
                .failure()).isEqualTo("TRUNCATED");
        assertThat(openai.read(ok("{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"no\"}}]}")).failure())
                .isEqualTo("REFUSAL");
        assertThat(claude.read(ok("{\"content\":[],\"stop_reason\":\"refusal\"}")).failure()).isEqualTo("REFUSAL");
        assertThat(claude.read(ok("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}],\"stop_reason\":\"end_turn\"}"))
                .failure()).isEqualTo("EMPTY");
        assertThat(claude.read(ok("not json")).failure()).isEqualTo("UNPARSEABLE");
        LlmReply limited = claude.read(new AgentLlmTransport.Response(429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}", 1));
        assertThat(limited.failure()).isEqualTo("HTTP_429");
        assertThat(limited.errorCode()).isEqualTo("rate_limit_error");
        assertThat(openai.read(new AgentLlmTransport.Response(0, "timeout", 1)).failure()).isEqualTo("TRANSPORT");
    }

    @Test
    @DisplayName("each vendor authenticates its own way, and the key travels only in its header")
    void headers() {
        assertThat(LlmWire.of(LlmVendor.OPENAI).headers("k")).containsExactly(java.util.Map.entry("Authorization", "Bearer k"));
        assertThat(LlmWire.of(LlmVendor.ANTHROPIC).headers("k")).containsEntry("x-api-key", "k")
                .containsEntry("anthropic-version", AnthropicMessagesWire.API_VERSION).hasSize(2);
    }

    @Test
    @DisplayName("a vendor name nobody wired refuses; embeddings refuse Claude, which has no embedding API")
    void unknownVendorsRefuse() {
        assertThat(LlmVendor.parse(null)).isEqualTo(LlmVendor.OPENAI);
        assertThat(LlmVendor.parse(" anthropic ")).isEqualTo(LlmVendor.ANTHROPIC);
        assertThatThrownBy(() -> LlmVendor.parse("UPSTAGE")).hasMessageContaining("UPSTAGE");
        assertThatThrownBy(() -> EmbeddingWire.of(LlmVendor.ANTHROPIC)).hasMessageContaining("no embedding API");
        assertThat(EmbeddingWire.of(LlmVendor.OPENAI).read(ok("{\"data\":[{\"index\":0,\"embedding\":[0.5,1]}]}"), 1))
                .singleElement().satisfies(v -> assertThat(v).containsExactly(0.5f, 1f));
        assertThat(EmbeddingWire.of(LlmVendor.OPENAI).read(ok("{\"data\":[]}"), 1)).as("one missing is none").isEmpty();
    }
}
