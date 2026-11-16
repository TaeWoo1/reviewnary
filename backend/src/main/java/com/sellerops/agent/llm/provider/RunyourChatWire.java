package com.sellerops.agent.llm.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.Map;

/**
 * Runyour's OpenAI-compatible chat endpoint. Uses the documented max_tokens field, with no
 * provider-specific reasoning_effort forwarded to an arbitrary routed model. The capability keeps
 * its system prompt, response schema, validation and payload floor. Compatibility of a specific
 * routed model with structured output or images still needs that model's own evaluation.
 *
 * <p><b>A Claude model behind the router takes Claude's schema rules</b> (2026-11-15, the first real evaluation call: the
 * coverage judge came back HTTP 400 because Claude's structured outputs accept {@code minItems} 0 or 1 only, a rule the
 * direct Anthropic wire already applies). For an {@code anthropic/} model the schema sent is
 * {@link AnthropicMessagesWire#forClaude}'s copy; every other routed model gets the request byte for byte as before. The
 * capability's own parser still checks every count and id the copy no longer states.
 */
public final class RunyourChatWire implements LlmWire {

    static final RunyourChatWire INSTANCE = new RunyourChatWire();
    private static final URI ENDPOINT = URI.create("https://api.runyour.ai/v1/chat/completions");

    private RunyourChatWire() {
    }

    @Override
    public LlmVendor vendor() {
        return LlmVendor.RUNYOUR;
    }

    @Override
    public URI endpoint() {
        return ENDPOINT;
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        return OpenAiChatWire.INSTANCE.headers(apiKey);
    }

    @Override
    public String body(LlmRequest request) {
        ObjectNode root;
        try {
            root = (ObjectNode) LlmWireSupport.MAPPER.readTree(OpenAiChatWire.INSTANCE.body(request));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not build Runyour request", e);
        }
        root.remove("max_completion_tokens");
        root.remove("reasoning_effort");
        root.put("max_tokens", request.maxOutputTokens());
        if (request.format().kind() == LlmRequest.Format.Kind.JSON_SCHEMA && routesToClaude(request.model())) {
            ((ObjectNode) root.path("response_format").path("json_schema"))
                    .set("schema", AnthropicMessagesWire.forClaude(request.format().schema()));
        }
        return root.toString();
    }

    /** Runyour names a model by its maker: {@code anthropic/claude-…}. */
    static boolean routesToClaude(String model) {
        return model != null && model.strip().toLowerCase(java.util.Locale.ROOT).startsWith("anthropic/");
    }

    @Override
    public LlmReply read(AgentLlmTransport.Response response) {
        return OpenAiChatWire.INSTANCE.read(response);
    }
}
