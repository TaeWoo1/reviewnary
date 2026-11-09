package com.sellerops.agent.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Messages. Three things differ from OpenAI beyond spelling, and each is handled here so no capability has to
 * know it:
 * <ul>
 *   <li><b>The answer is the {@code text} blocks</b>, not the first block — a current model thinks first, and its
 *   thinking block comes before the answer;</li>
 *   <li><b>effort is {@code output_config.effort}</b> ({@code low}…{@code max}); OpenAI's {@code minimal} becomes
 *   {@code low}, and a word Anthropic does not know is left out rather than sent;</li>
 *   <li><b>a schema is {@code output_config.format}</b>, and the constraints Claude's structured outputs do not take —
 *   numeric and string bounds, item counts — are removed from the copy sent ({@link #forClaude}). The caller's parser
 *   still checks every one of them; the schema was always the first fence, never the only one.</li>
 * </ul>
 * There is no JSON-object mode: the prompt asks for JSON and the parser reads it, as before.
 */
public final class AnthropicMessagesWire implements LlmWire {

    static final AnthropicMessagesWire INSTANCE = new AnthropicMessagesWire();
    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    static final String API_VERSION = "2023-06-01";
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    /** Keywords Claude's structured outputs refuse; the parser after the call is where they are enforced. */
    private static final Set<String> UNSUPPORTED = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minLength", "maxLength", "maxItems", "uniqueItems");

    private AnthropicMessagesWire() {
    }

    @Override
    public LlmVendor vendor() {
        return LlmVendor.ANTHROPIC;
    }

    @Override
    public URI endpoint() {
        return ENDPOINT;
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }

    @Override
    public String body(LlmRequest request) {
        ObjectNode root = LlmWireSupport.MAPPER.createObjectNode();
        root.put("model", request.model());
        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject().put("role", "user");
        if (request.images().isEmpty()) {
            user.put("content", request.user());
        } else {
            ArrayNode content = user.putArray("content");
            for (LlmRequest.Image image : request.images()) {
                ObjectNode src = content.addObject().put("type", "image").putObject("source");
                src.put("type", "base64").put("media_type", image.mediaType()).put("data", image.base64());
            }
            if (request.user() != null && !request.user().isBlank()) {
                content.addObject().put("type", "text").put("text", request.user());
            }
        }
        root.put("max_tokens", request.maxOutputTokens());
        root.put("system", request.system());
        String effort = effortOf(request.effort());
        if (effort != null || request.format().kind() == LlmRequest.Format.Kind.JSON_SCHEMA) {
            ObjectNode config = root.putObject("output_config");
            if (effort != null) {
                config.put("effort", effort);
            }
            if (request.format().kind() == LlmRequest.Format.Kind.JSON_SCHEMA) {
                config.putObject("format").put("type", "json_schema").set("schema", forClaude(request.format().schema()));
            }
        }
        return root.toString();
    }

    /** The configured effort in Anthropic's spelling, or null to leave it out. */
    public static String effortOf(String configured) {
        if (configured == null) {
            return null;
        }
        String e = configured.strip().toLowerCase(Locale.ROOT);
        if ("minimal".equals(e)) {
            e = "low";
        }
        return EFFORTS.contains(e) ? e : null;
    }

    /** A copy of the schema without the keywords Claude's structured outputs refuse; {@code minItems} above 1 too. */
    public static ObjectNode forClaude(ObjectNode schema) {
        ObjectNode copy = schema.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            for (Iterator<String> names = o.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (UNSUPPORTED.contains(name) || ("minItems".equals(name) && o.path(name).asInt() > 1)) {
                    names.remove();
                }
            }
            o.elements().forEachRemaining(AnthropicMessagesWire::strip);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(AnthropicMessagesWire::strip);
        }
    }

    @Override
    public LlmReply read(AgentLlmTransport.Response response) {
        if (response == null || !response.ok()) {
            return LlmWireSupport.failedHttp(response);
        }
        try {
            JsonNode root = LlmWireSupport.MAPPER.readTree(response.body());
            String finish = root.path("stop_reason").asText(null);
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                    text.append(block.path("text").asText());
                }
            }
            String said = text.toString();
            if ("refusal".equals(finish)) {
                return new LlmReply(null, LlmWireSupport.blankToNull(said), finish, "REFUSAL", null);
            }
            if ("max_tokens".equals(finish)) {
                return new LlmReply(null, LlmWireSupport.blankToNull(said), finish, "TRUNCATED", null);
            }
            return said.isBlank() ? new LlmReply(null, null, finish, "EMPTY", null)
                    : new LlmReply(said, said, finish, null, null);
        } catch (Exception e) {
            return new LlmReply(null, LlmWireSupport.blankToNull(response.body()), null, "UNPARSEABLE", null);
        }
    }
}
