package com.sellerops.knowledge.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentLlmCallMetrics;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.provider.LlmReply;
import com.sellerops.agent.llm.provider.LlmRequest;
import com.sellerops.agent.llm.provider.LlmVendor;
import com.sellerops.agent.llm.provider.LlmWire;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one call the retrieval-intent capability makes.
 *
 * <p><b>The payload floor.</b> What leaves is the model, the two prompt turns, and the output
 * settings — and the only variable content in either turn is the customer's own sentence. No
 * organisation, no product, no identifier, no seller passage; the request has nowhere to put them
 * and this class does not invent a place. {@code KnowledgeQuestionIntentPayloadFloorTest} asserts
 * the serialized bytes.
 *
 * <p>One door, in the shape {@code AgentDraftBoundaryTest} fixes for every capability: the only
 * class that may hold the transport for this capability, constructed only by
 * {@link KnowledgeQuestionIntent}, so the organisation gate is checked at the door.
 */
public class KnowledgeQuestionIntentGenerator {

    /** A restatement longer than the question it restates is not a restatement. */
    static final int MAX_INTENT_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionIntentGenerator.class);

    private final AgentLlmTransport transport;
    private final KnowledgeQuestionIntentProperties properties;

    /** The vendor's spelling of the call (shared model connection, 2026-10-29); prompt and reading stay here. */
    private final LlmWire wire;

    /** The AI meter (2026-10-31): set by {@link KnowledgeQuestionIntent} when the context has one. */
    final SemanticMeter meter = new SemanticMeter();

    KnowledgeQuestionIntentGenerator(AgentLlmTransport transport,
                                     KnowledgeQuestionIntentProperties properties) {
        this.transport = transport;
        this.properties = properties;
        this.wire = LlmWire.of(properties == null ? LlmVendor.OPENAI : properties.vendor());
    }

    /**
     * What this sentence needs to be answered, as a sentence to embed beside it.
     *
     * @return null when the vendor refused, answered a shape this does not recognise, or said the
     *         sentence asks for nothing. <b>Null is a real answer</b>: the search then runs on the
     *         customer's own words exactly as it did before this capability existed.
     */
    String intentOf(UUID orgId, String question) {
        AgentLlmTransport.Response response = meter.post(orgId, SemanticMeter.INTENT_STEP,
                () -> transport.post(wire.endpoint(), wire.headers(properties.apiKey()), requestBody(question)));
        if (response == null) {
            // The day's AI ceiling refused the call: no restatement, the customer's own words are searched.
            return null;
        }
        // Metadata only, in the shape the agent_* lines already use: which organisation, whether
        // there was an answer, how long it took, how many tokens. Retrieval Runtime Closure v1 §5 —
        // three capabilities were reaching a vendor on every search and leaving no trace at all, so
        // "what does semantic retrieval cost this seller" was a question the deployment could not
        // answer from its own logs. Never the sentence, never the restatement.
        log.info("knowledge_intent orgId={} restated={} {}", orgId, response.ok(),
                AgentLlmCallMetrics.of(response).toLogFields());
        LlmReply reply = wire.read(response);
        if (!reply.answered()) {
            return null;
        }
        try {
            String content = reply.content();
            String intent = MAPPER.readTree(content).path("intent").asText("").strip();
            if (intent.isEmpty() || intent.length() > MAX_INTENT_CHARS) {
                return null;
            }
            return intent;
        } catch (Exception e) {
            return null;
        }
    }

    /** Package-visible so the payload floor test can assert the exact bytes that leave. */
    String requestBody(String question) {
        return wire.body(LlmRequest.of(properties.model(), KnowledgeQuestionIntentPrompt.system(),
                KnowledgeQuestionIntentPrompt.user(question), properties.maxOutputTokens(), properties.reasoningEffort(),
                LlmRequest.Format.jsonObject()));
    }
}
