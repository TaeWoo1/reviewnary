package com.sellerops.agent.llm.provider;

import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * <b>One vendor's spelling of an embedding call</b> — chosen apart from the chat vendor (owner, 2026-10-29: 「임베딩은
 * 독립적으로 교체할 수 있게 한다」). Like {@link LlmWire} it writes and reads; the capability's generator posts.
 *
 * <p>Only OpenAI has one today. Anthropic publishes no embedding endpoint, so naming it refuses to boot rather than
 * pretend; a new vendor is a new implementation here, and its vectors are stored under its own model key so they are
 * never compared with another vendor's.
 */
public interface EmbeddingWire {

    LlmVendor vendor();

    URI endpoint();

    Map<String, String> headers(String apiKey);

    String body(String model, int dimensions, List<String> texts);

    /** One vector per input, in order — or empty when the answer is not exactly that. */
    List<float[]> read(AgentLlmTransport.Response response, int expected);

    static EmbeddingWire of(LlmVendor vendor) {
        return switch (vendor) {
            case OPENAI -> OpenAiEmbeddingWire.INSTANCE;
            case ANTHROPIC -> throw new IllegalArgumentException(
                    "Anthropic has no embedding API — choose another embedding vendor");
        };
    }
}
