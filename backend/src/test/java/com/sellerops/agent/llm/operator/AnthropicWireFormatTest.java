package com.sellerops.agent.llm.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentDraftResponseParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Speaking to a current Claude model</b> (2026-10-29, before the first Claude evaluation). Current models think before
 * they answer, so the answer is not the first content block; and they take their depth from {@code output_config.effort},
 * which the configuration's effort never reached.
 */
class AnthropicWireFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String THOUGHT_THEN_ANSWER = """
            {"content":[{"type":"thinking","thinking":"","signature":"x"},
                        {"type":"text","text":"{\\"category\\":"},{"type":"text","text":"\\"Q\\"}"}],
             "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""";

    @Test
    @DisplayName("the answer is read from the text blocks, after a thinking block — both parsers")
    void theAnswerFollowsAThinkingBlock() {
        assertThat(AgentOperatorResponseParser.assistantText(THOUGHT_THEN_ANSWER)).contains("{\"category\":\"Q\"}");
        assertThat(AgentDraftResponseParser.assistantText(THOUGHT_THEN_ANSWER)).contains("{\"category\":\"Q\"}");
        assertThat(AgentOperatorResponseParser.assistantText("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}]}"))
                .as("thinking alone is no answer").isEmpty();
    }

    @Test
    @DisplayName("the configured effort goes to Claude as output_config.effort; OpenAI keeps reasoning_effort")
    void theEffortReachesClaude() throws Exception {
        JsonNode claude = MAPPER.readTree(AgentLlmWireFormat.body(AgentLlmWireFormat.Vendor.ANTHROPIC, "claude-opus-5-5",
                "sys", "user", 16000, "low"));
        assertThat(claude.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(claude.path("max_tokens").asInt()).isEqualTo(16000);
        assertThat(claude.has("reasoning_effort")).isFalse();
        assertThat(claude.has("thinking")).as("thinking is left to the model; effort is the control").isFalse();

        JsonNode minimal = MAPPER.readTree(AgentLlmWireFormat.body(AgentLlmWireFormat.Vendor.ANTHROPIC, "m", "s", "u",
                100, "minimal"));
        assertThat(minimal.path("output_config").path("effort").asText()).isEqualTo("low");
        JsonNode none = MAPPER.readTree(AgentLlmWireFormat.body(AgentLlmWireFormat.Vendor.ANTHROPIC, "m", "s", "u",
                100, null));
        assertThat(none.has("output_config")).isFalse();

        JsonNode openai = MAPPER.readTree(AgentLlmWireFormat.body(AgentLlmWireFormat.Vendor.OPENAI, "gpt", "s", "u",
                100, "low"));
        assertThat(openai.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(openai.has("output_config")).isFalse();
    }
}
