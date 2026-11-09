package com.sellerops.review.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.provider.LlmReply;
import com.sellerops.agent.llm.provider.LlmRequest;
import com.sellerops.agent.llm.provider.LlmVendor;
import com.sellerops.agent.llm.provider.LlmWire;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * One review photo, one call — the review-photo vision capability's only way to a vendor.
 *
 * <p>The photo travels as a data URL of the bytes this backend fetched, never as its CDN address (handing the vendor
 * the address would make them fetch it — a second egress SellerOps does not control). {@link #requestBody} is the
 * payload floor: the photo, {@link ReviewMediaVisionPrompt}, the rating and the review's own words.
 *
 * <p>Every failure is empty with a sanitized reason; vendor error bodies can quote the request, and the request is a
 * customer's picture.
 */
public class ReviewMediaVisionGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the model reported about one photo. */
    public record Observation(String depicts, ReviewMedia.ProblemVisible problemVisible, String problemDescription) {
    }

    public record Result(Optional<Observation> observation, String reason) {
    }

    private final AgentLlmTransport http;
    private final String modelId;
    private final String apiKey;
    private final int maxOutputTokens;
    private final String reasoningEffort;
    /** The vendor's spelling of the call (shared model connection, 2026-10-29); prompt, schema and reading stay here. */
    private final LlmWire wire;

    public ReviewMediaVisionGenerator(AgentLlmTransport http, String modelId, String apiKey, int maxOutputTokens,
                                      String reasoningEffort) {
        this(http, LlmVendor.OPENAI, modelId, apiKey, maxOutputTokens, reasoningEffort);
    }

    public ReviewMediaVisionGenerator(AgentLlmTransport http, LlmVendor vendor, String modelId, String apiKey,
                                      int maxOutputTokens, String reasoningEffort) {
        this.http = http;
        this.modelId = modelId;
        this.apiKey = apiKey;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningEffort = reasoningEffort;
        this.wire = LlmWire.of(vendor);
    }

    /** The model identity an inspected row records — the vendor too, so a vendor change alone re-reads the photo. */
    public String modelVersion() {
        return ReviewMediaVisionPrompt.PROMPT_VERSION + "+" + wire.vendor().name().toLowerCase(java.util.Locale.ROOT)
                + ":" + modelId;
    }

    public Result inspect(byte[] imageBytes, String contentType, Integer rating, String reviewText) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new Result(Optional.empty(), "no_bytes");
        }
        AgentLlmTransport.Response response = http.post(wire.endpoint(), wire.headers(apiKey),
                requestBody(imageBytes, contentType, rating, reviewText));
        if (response.status() == 0) {
            return new Result(Optional.empty(), "transport");
        }
        if (!response.ok()) {
            return new Result(Optional.empty(), "http:" + response.status());
        }
        LlmReply reply = wire.read(response);
        if ("TRUNCATED".equals(reply.failure())) {
            return new Result(Optional.empty(), "budget_exhausted");
        }
        if ("UNPARSEABLE".equals(reply.failure())) {
            return new Result(Optional.empty(), "unreadable_envelope");
        }
        if ("REFUSAL".equals(reply.failure())) {
            return new Result(Optional.empty(), "refusal");
        }
        return parse(reply.content()).map(o -> new Result(Optional.of(o), "ok"))
                .orElse(new Result(Optional.empty(), "off_schema"));
    }

    /** The model's JSON, held to the schema: a closed verdict and two bounded sentences. */
    static Optional<Observation> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            String depicts = bounded(node.path("depicts").asText(null), 120);
            ReviewMedia.ProblemVisible visible;
            try {
                visible = ReviewMedia.ProblemVisible.valueOf(node.path("problemVisible").asText(""));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
            if (depicts == null || depicts.isBlank()) {
                return Optional.empty();
            }
            String description = visible == ReviewMedia.ProblemVisible.YES
                    ? bounded(node.path("problemDescription").asText(null), 160) : null;
            return Optional.of(new Observation(depicts, visible, description));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String bounded(String value, int max) {
        if (value == null || "null".equals(value)) {
            return null;
        }
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    /** The whole outgoing payload. Package-private so the floor test asserts the bytes, not the intent. */
    String requestBody(byte[] imageBytes, String contentType, Integer rating, String reviewText) {
        String mediaType = contentType == null || contentType.isBlank() ? "image/jpeg" : contentType;
        LlmRequest request = new LlmRequest(modelId, ReviewMediaVisionPrompt.system(),
                ReviewMediaVisionPrompt.user(rating, reviewText),
                List.of(new LlmRequest.Image(mediaType, Base64.getEncoder().encodeToString(imageBytes), "high")),
                maxOutputTokens, reasoningEffort, LlmRequest.Format.jsonSchema(ReviewMediaVisionPrompt.schemaName(),
                        schema()));
        return wire.body(request);
    }

    /** A closed verdict and two sentences, every field required. */
    private static ObjectNode schema() {
        ObjectNode shape = MAPPER.createObjectNode();
        shape.put("type", "object");
        shape.put("additionalProperties", false);
        shape.putArray("required").add("depicts").add("problemVisible").add("problemDescription");
        ObjectNode props = shape.putObject("properties");
        props.putObject("depicts").put("type", "string");
        ObjectNode visible = props.putObject("problemVisible");
        visible.put("type", "string");
        visible.putArray("enum").add("YES").add("NO").add("UNCLEAR");
        props.putObject("problemDescription").putArray("type").add("string").add("null");
        return shape;
    }
}
