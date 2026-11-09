package com.sellerops.knowledge.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentLlmCallMetrics;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.provider.EmbeddingWire;
import com.sellerops.agent.llm.provider.LlmVendor;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one call the semantic-retrieval capability makes.
 *
 * <p><b>The payload floor is the narrowest of the seven.</b> Three fields leave: the model, the
 * dimension count, and the texts to embed. No organisation, no product, no identifier, no customer
 * name, no order, no instruction — an embedding request has nowhere to put them and this class does
 * not invent a place. {@code KnowledgeEmbeddingPayloadFloorTest} asserts the serialized bytes.
 *
 * <p>One door, in the shape {@code AgentDraftBoundaryTest} fixes for every capability: this is the
 * only class that may hold the transport for this capability, and {@link KnowledgeEmbeddingService}
 * is the only class that may construct it — so the organisation gate is checked at the door and a
 * caller holding a generator would be an allow-list nobody runs.
 */
public class KnowledgeEmbeddingGenerator {

    /** The most texts one request carries. A whole product library is far below it. */
    static final int MAX_BATCH = 64;

    /**
     * What was embedded — for the log line, and for nothing else.
     *
     * <p>It does not reach the request: the payload floor is the model, the dimension count and the
     * texts, and this would be a fourth field. It reports which of the capability's two economics a
     * call belongs to.
     */
    enum Kind {
        /** A seller's own knowledge sentence. Cached by content in {@code knowledge_embedding}. */
        PASSAGE,
        /** A customer's question, or the restatement of one. Never written down. */
        QUESTION
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingGenerator.class);

    private final AgentLlmTransport transport;
    private final KnowledgeEmbeddingProperties properties;

    /** The embedding vendor's spelling — its own setting, apart from every chat vendor (2026-10-29). */
    private final EmbeddingWire wire;

    /** The AI meter (2026-10-31): set by {@link KnowledgeEmbeddingService} when the context has one. */
    final SemanticMeter meter = new SemanticMeter();

    KnowledgeEmbeddingGenerator(AgentLlmTransport transport, KnowledgeEmbeddingProperties properties) {
        this.transport = transport;
        this.properties = properties;
        this.wire = EmbeddingWire.of(properties == null ? LlmVendor.OPENAI : properties.vendor());
    }

    /**
     * The vectors for these texts, in the same order.
     *
     * @return one vector per input, or an empty list when the vendor refused or answered a shape this
     *         does not recognise. <b>Empty is a real answer</b>: the caller falls back to the lexical
     *         scorer rather than reporting an absence it could not measure.
     */
    List<float[]> embed(UUID orgId, Kind kind, List<String> texts) {
        if (texts.isEmpty() || texts.size() > MAX_BATCH) {
            return List.of();
        }
        AgentLlmTransport.Response response = meter.post(orgId,
                kind == Kind.QUESTION ? SemanticMeter.EMBED_QUESTION_STEP : SemanticMeter.EMBED_PASSAGE_STEP,
                () -> transport.post(wire.endpoint(), wire.headers(properties.apiKey()), requestBody(texts)));
        if (response == null) {
            // The day's AI ceiling refused the call: no vectors, and the caller falls back to the lexical scorer.
            return List.of();
        }
        // Metadata only — see the note in KnowledgeQuestionIntentGenerator. The two kinds are
        // reported apart because they are different exposures with different economics: a passage is
        // embedded once per organisation and cached in the database forever, a question is embedded
        // for one search and never written down. A cost report that added them would be answering
        // neither "what did this library cost to index" nor "what does a search cost".
        log.info("knowledge_embedding orgId={} kind={} texts={} answered={} {}", orgId, kind,
                texts.size(), response.ok(), AgentLlmCallMetrics.of(response).toLogFields());
        return wire.read(response, texts.size());
    }

    /** Package-visible so the payload floor test can assert the exact bytes that leave. */
    String requestBody(List<String> texts) {
        return wire.body(properties.model(), properties.dimensions(), texts);
    }
}
