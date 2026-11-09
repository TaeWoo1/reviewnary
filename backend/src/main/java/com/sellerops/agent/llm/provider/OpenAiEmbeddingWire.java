package com.sellerops.agent.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI embeddings: {@code model}, {@code dimensions}, {@code input} — the bytes the knowledge search always sent. */
public final class OpenAiEmbeddingWire implements EmbeddingWire {

    static final OpenAiEmbeddingWire INSTANCE = new OpenAiEmbeddingWire();
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/embeddings");

    private OpenAiEmbeddingWire() {
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
    public String body(String model, int dimensions, List<String> texts) {
        ObjectNode body = LlmWireSupport.MAPPER.createObjectNode();
        body.put("model", model);
        body.put("dimensions", dimensions);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        return body.toString();
    }

    @Override
    public List<float[]> read(AgentLlmTransport.Response response, int expected) {
        if (response == null || !response.ok()) {
            return List.of();
        }
        try {
            JsonNode data = LlmWireSupport.MAPPER.readTree(response.body()).path("data");
            float[][] out = new float[expected][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode vector = item.path("embedding");
                if (index < 0 || index >= expected || !vector.isArray()) {
                    return List.of();
                }
                float[] values = new float[vector.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (float) vector.get(i).asDouble();
                }
                out[index] = values;
            }
            List<float[]> vectors = new ArrayList<>(expected);
            for (float[] vector : out) {
                if (vector == null) {
                    return List.of();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (Exception e) {
            return List.of();
        }
    }
}
