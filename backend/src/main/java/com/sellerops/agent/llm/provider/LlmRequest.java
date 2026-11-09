package com.sellerops.agent.llm.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * <b>One model call, in no vendor's spelling</b>: one system instruction, one user turn (text, and optionally images),
 * an output budget, an effort, and the answer's format. Each {@link LlmWire} writes it in its own vendor's bytes.
 *
 * @param effort the configured reasoning effort ({@code minimal}/{@code low}/{@code medium}/{@code high}/…), or null
 *               for the vendor's own default
 */
public record LlmRequest(String model, String system, String user, List<Image> images, int maxOutputTokens,
                         String effort, Format format) {

    public LlmRequest {
        images = images == null ? List.of() : List.copyOf(images);
        format = format == null ? Format.text() : format;
    }

    public static LlmRequest of(String model, String system, String user, int maxOutputTokens, String effort,
                                Format format) {
        return new LlmRequest(model, system, user, List.of(), maxOutputTokens, effort, format);
    }

    /**
     * One picture for the user turn, base64 already, with its media type. {@code detail} is OpenAI's resolution hint
     * ({@code high}/{@code low}/{@code auto}, null for none); Anthropic has no such field and ignores it.
     */
    public record Image(String mediaType, String base64, String detail) {
    }

    /**
     * What the answer must look like. {@code JSON_OBJECT} asks for any JSON object; {@code JSON_SCHEMA} for this schema,
     * enforced by the vendor where it can — the caller's parser still checks every field either way.
     *
     * @param schema the bare JSON Schema (not OpenAI's {@code response_format} wrapper)
     */
    public record Format(Kind kind, String name, ObjectNode schema) {
        public enum Kind { TEXT, JSON_OBJECT, JSON_SCHEMA }

        public static Format text() {
            return new Format(Kind.TEXT, null, null);
        }

        public static Format jsonObject() {
            return new Format(Kind.JSON_OBJECT, null, null);
        }

        public static Format jsonSchema(String name, ObjectNode schema) {
            return new Format(Kind.JSON_SCHEMA, name, schema);
        }

        /**
         * Read OpenAI's {@code response_format} wrapper ({@code {type: json_schema, json_schema: {name, strict,
         * schema}}}) — the shape the existing prompt classes build — as a format.
         */
        public static Format fromOpenAiResponseFormat(ObjectNode responseFormat) {
            if (responseFormat == null) {
                return jsonObject();
            }
            String type = responseFormat.path("type").asText("");
            if ("json_schema".equals(type) && responseFormat.path("json_schema").path("schema").isObject()) {
                return jsonSchema(responseFormat.path("json_schema").path("name").asText("answer"),
                        (ObjectNode) responseFormat.path("json_schema").path("schema"));
            }
            return "text".equals(type) ? text() : jsonObject();
        }
    }
}
