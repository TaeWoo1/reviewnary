package com.sellerops.agent.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI Chat Completions. The bytes are the ones every capability wrote by hand before this class — model, the system
 * turn then the user turn, {@code max_completion_tokens}, {@code response_format}, {@code reasoning_effort} — so moving a
 * capability here leaves its payload floor test unchanged.
 */
public final class OpenAiChatWire implements LlmWire {

    static final OpenAiChatWire INSTANCE = new OpenAiChatWire();
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");

    private OpenAiChatWire() {
    }

    @Override
    public LlmVendor vendor() {
        return LlmVendor.OPENAI;
    }

    @Override
    public URI endpoint() {
        return ENDPOINT;
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    @Override
    public String body(LlmRequest request) {
        ObjectNode root = LlmWireSupport.MAPPER.createObjectNode();
        root.put("model", request.model());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        ObjectNode user = messages.addObject().put("role", "user");
        if (request.images().isEmpty()) {
            user.put("content", request.user());
        } else {
            ArrayNode content = user.putArray("content");
            if (request.user() != null && !request.user().isBlank()) {
                content.addObject().put("type", "text").put("text", request.user());
            }
            for (LlmRequest.Image image : request.images()) {
                ObjectNode url = content.addObject().put("type", "image_url").putObject("image_url");
                url.put("url", "data:" + image.mediaType() + ";base64," + image.base64());
                if (image.detail() != null) {
                    url.put("detail", image.detail());
                }
            }
        }
        root.put("max_completion_tokens", request.maxOutputTokens());
        switch (request.format().kind()) {
            case JSON_OBJECT -> root.putObject("response_format").put("type", "json_object");
            case JSON_SCHEMA -> {
                ObjectNode format = root.putObject("response_format").put("type", "json_schema");
                ObjectNode js = format.putObject("json_schema");
                js.put("name", request.format().name()).put("strict", true);
                js.set("schema", request.format().schema());
            }
            case TEXT -> { }
        }
        if (request.effort() != null) {
            root.put("reasoning_effort", request.effort());
        }
        return root.toString();
    }

    /**
     * A refusal, a cut-off answer, an HTTP failure and an empty message are different facts — each is still no answer.
     */
    @Override
    public LlmReply read(AgentLlmTransport.Response response) {
        if (response == null || !response.ok()) {
            return LlmWireSupport.failedHttp(response);
        }
        try {
            JsonNode choice = LlmWireSupport.MAPPER.readTree(response.body()).path("choices").path(0);
            String finish = choice.path("finish_reason").asText(null);
            String said = choice.path("message").path("content").asText("");
            JsonNode refusal = choice.path("message").path("refusal");
            if (refusal.isTextual() && !refusal.asText().isBlank()) {
                return new LlmReply(null, refusal.asText(), finish, "REFUSAL", null);
            }
            if ("length".equals(finish)) {
                return new LlmReply(null, LlmWireSupport.blankToNull(said), finish, "TRUNCATED", null);
            }
            return said.isBlank() ? new LlmReply(null, null, finish, "EMPTY", null)
                    : new LlmReply(said, said, finish, null, null);
        } catch (Exception e) {
            return new LlmReply(null, LlmWireSupport.blankToNull(response.body()), null, "UNPARSEABLE", null);
        }
    }
}
