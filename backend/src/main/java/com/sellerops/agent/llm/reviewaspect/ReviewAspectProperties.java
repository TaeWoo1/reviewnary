package com.sellerops.agent.llm.reviewaspect;

import com.sellerops.agent.llm.operator.AgentOperatorProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code sellerops.review.aspect.*} — reading reviews into aspect · evaluation · evidence for the strengths/weaknesses
 * analysis, and writing opportunities/threats from that evidence (owner decision 2026-11-17, canonical §7 goal A).
 *
 * <p>Off by default in the same three independent ways as every capability (flag, key, org list). Its own flag because
 * its exposure is its own: many customers' review sentences in one request — no names, no ids, no order — which is not
 * what any other capability sends.
 *
 * <p>Corpus-sized, so it states its ceilings: {@link #batchSize} reviews per request, {@link #maxTextChars} per review
 * (truncated, never split), and {@link #maxCallsPerRequest} requests per seller action. A whole store is read over
 * several presses, never by one.
 */
@Component
public class ReviewAspectProperties extends AgentOperatorProperties {

    private final int maxTextChars;
    private final int batchSize;
    private final int maxCallsPerRequest;

    public ReviewAspectProperties(
            @Value("${sellerops.review.aspect.enabled:false}") boolean enabled,
            @Value("${sellerops.review.aspect.enabled-org-ids:}") String enabledOrgIds,
            @Value("${sellerops.review.aspect.vendor:OPENAI}") String vendor,
            @Value("${sellerops.review.aspect.model:gpt-5-2025-08-07}") String model,
            @Value("${sellerops.review.aspect.api-key:}") String apiKey,
            // Sized for the answer of a full batch (about 40 reviews × two opinions) AND a reasoning model's own
            // thinking, which shares the budget — the trap InquirySignalProperties records.
            @Value("${sellerops.review.aspect.max-output-tokens:8000}") int maxOutputTokens,
            @Value("${sellerops.review.aspect.reasoning-effort:low}") String reasoningEffort,
            @Value("${sellerops.review.aspect.max-text-chars:500}") int maxTextChars,
            @Value("${sellerops.review.aspect.batch-size:40}") int batchSize,
            @Value("${sellerops.review.aspect.max-calls-per-request:8}") int maxCallsPerRequest) {
        super(enabled, enabledOrgIds, vendor, model, apiKey, maxOutputTokens, reasoningEffort);
        this.maxTextChars = maxTextChars <= 0 ? 500 : maxTextChars;
        this.batchSize = batchSize <= 0 ? 40 : Math.min(batchSize, 80);
        this.maxCallsPerRequest = maxCallsPerRequest <= 0 ? 8 : maxCallsPerRequest;
    }

    public int maxTextChars() {
        return maxTextChars;
    }

    public int batchSize() {
        return batchSize;
    }

    public int maxCallsPerRequest() {
        return maxCallsPerRequest;
    }

    @Override
    public String capabilityName() {
        return "SELLEROPS_REVIEW_ASPECT";
    }
}
