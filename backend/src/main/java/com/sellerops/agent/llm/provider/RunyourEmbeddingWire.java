package com.sellerops.agent.llm.provider;

import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Runyour's documented embeddings endpoint; the model is chosen separately from chat models. */
public final class RunyourEmbeddingWire implements EmbeddingWire {

    static final RunyourEmbeddingWire INSTANCE = new RunyourEmbeddingWire();
    private static final URI ENDPOINT = URI.create("https://api.runyour.ai/v1/embeddings");

    private RunyourEmbeddingWire() {
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
        return OpenAiEmbeddingWire.INSTANCE.headers(apiKey);
    }

    @Override
    public String body(String model, int dimensions, List<String> texts) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("choose a Runyour embedding model before enabling embeddings");
        }
        return OpenAiEmbeddingWire.INSTANCE.body(model, dimensions, texts);
    }

    @Override
    public List<float[]> read(AgentLlmTransport.Response response, int expected) {
        return OpenAiEmbeddingWire.INSTANCE.read(response, expected);
    }
}
