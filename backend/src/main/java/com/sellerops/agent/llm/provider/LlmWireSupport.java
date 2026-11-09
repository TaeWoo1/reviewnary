package com.sellerops.agent.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** What both wires read the same way: the vendor's error code on an HTTP failure. */
final class LlmWireSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmWireSupport() {
    }

    /** OpenAI {@code error.code}, Anthropic {@code error.type} — only when it is a short closed word. */
    static String errorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            String code = error.path("code").asText(error.path("type").asText(""));
            return code.matches("[a-z_]{1,40}") ? code : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static LlmReply failedHttp(com.sellerops.agent.llm.AgentLlmTransport.Response response) {
        if (response == null) {
            return new LlmReply(null, null, null, "TRANSPORT", null);
        }
        return new LlmReply(null, blankToNull(response.body()), null,
                response.status() == 0 ? "TRANSPORT" : "HTTP_" + response.status(), errorCode(response.body()));
    }
}
