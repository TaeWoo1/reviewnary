package com.sellerops.knowledge.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentLlmCallMetrics;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.provider.LlmReply;
import com.sellerops.agent.llm.provider.LlmRequest;
import com.sellerops.agent.llm.provider.LlmVendor;
import com.sellerops.agent.llm.provider.LlmWire;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one call the evidence-eligibility capability makes.
 *
 * <p><b>The payload floor.</b> What leaves is the model, the output settings, the fixed instruction,
 * the customer's sentence and the candidate passages — the passages by POSITION, with no source id,
 * no chunk id, no product, no organisation and no seller name, because a judgement about whether a
 * fact is present needs none of them and a request that carries an identifier is a request that can
 * leak one. {@code KnowledgeEligibilityPayloadFloorTest} asserts the serialized bytes.
 *
 * <p>One door: the only class that may hold the transport for this capability, constructed only by
 * {@link KnowledgeEvidenceEligibility}.
 */
public class KnowledgeEligibilityGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEligibilityGenerator.class);

    private final AgentLlmTransport transport;
    private final KnowledgeEligibilityProperties properties;

    /** The vendor's spelling of the call (shared model connection, 2026-10-29); prompt and reading stay here. */
    private final LlmWire wire;

    /** The AI meter (2026-10-31): set by {@link KnowledgeEvidenceEligibility} when the context has one. */
    final SemanticMeter meter = new SemanticMeter();

    KnowledgeEligibilityGenerator(AgentLlmTransport transport,
                                  KnowledgeEligibilityProperties properties) {
        this.transport = transport;
        this.properties = properties;
        this.wire = LlmWire.of(properties == null ? LlmVendor.OPENAI : properties.vendor());
    }

    /**
     * Which of these passages carry a fact for this question, by position.
     *
     * @return an empty map when the vendor refused or answered a shape this does not recognise —
     *         and then nothing is refused, because a judge that did not answer has no opinion
     */
    Map<Integer, Boolean> judge(UUID orgId, String question, List<String> passages) {
        AgentLlmTransport.Response response = meter.post(orgId, SemanticMeter.ELIGIBILITY_STEP,
                () -> transport.post(wire.endpoint(), wire.headers(properties.apiKey()), requestBody(question, passages)));
        if (response == null) {
            // The day's AI ceiling refused the call: no opinion, so nothing is refused.
            return Map.of();
        }
        // Metadata only — see the note in KnowledgeQuestionIntentGenerator. The passage COUNT is a
        // fact about this capability's bound; the passages themselves never appear.
        log.info("knowledge_eligibility orgId={} passages={} answered={} {}", orgId, passages.size(),
                response.ok(), AgentLlmCallMetrics.of(response).toLogFields());
        LlmReply reply = wire.read(response);
        if (!reply.answered()) {
            return Map.of();
        }
        try {
            String content = reply.content();
            Map<Integer, Boolean> out = new HashMap<>();
            for (JsonNode verdict : MAPPER.readTree(content).path("verdicts")) {
                int index = verdict.path("i").asInt(-1);
                if (index >= 0 && index < passages.size() && verdict.has("supports")) {
                    out.put(index, verdict.path("supports").asBoolean());
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Package-visible so the payload floor test can assert the exact bytes that leave. */
    String requestBody(String question, List<String> passages) {
        return wire.body(LlmRequest.of(properties.model(), KnowledgeEligibilityPrompt.system(),
                KnowledgeEligibilityPrompt.user(question, passages), properties.maxOutputTokens(),
                properties.reasoningEffort(), LlmRequest.Format.jsonObject()));
    }
}
