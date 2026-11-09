package com.sellerops.knowledge.semantic;

import com.sellerops.agent.llm.AgentLlmCallMetrics;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageKind;
import com.sellerops.agent.quota.AgentUsageScope;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>The search helpers' calls on the AI meter</b> (2026-10-31, {@code docs/standing/reply_agent_v1.md} §8 ②: question
 * intent, evidence eligibility and embeddings reached a vendor on every search and were on no meter and under no
 * ceiling). Each real call is one {@link AgentUsageKind#RETRIEVE} row, charged to whoever the surrounding work names
 * ({@link AgentUsageScope}), with its time and tokens; a remembered answer is no call and no row. A call the ceiling
 * refuses is never made, and the helper answers as it does when its vendor refuses — the search runs on the customer's
 * own words, exactly as with the helper off.
 *
 * <p>Without a quota service (a unit test, a context without one) every call passes through unmetered.
 */
final class SemanticMeter {

    static final String INTENT_STEP = "INTENT";
    static final String ELIGIBILITY_STEP = "ELIGIBILITY";
    static final String EMBED_QUESTION_STEP = "EMBED_QUESTION";
    static final String EMBED_PASSAGE_STEP = "EMBED_PASSAGE";

    private volatile AgentQuotaService quota;

    void use(AgentQuotaService quota) {
        this.quota = quota;
    }

    /** The vendor's response, or null when the ceiling refused the call — which was then never made. */
    AgentLlmTransport.Response post(UUID orgId, String step, Supplier<AgentLlmTransport.Response> call) {
        AgentQuotaService meter = quota;
        AgentQuotaService.Charge charge = meter == null ? null
                : meter.chargeCheck(orgId, AgentUsageKind.RETRIEVE, step, AgentUsageScope.current());
        if (charge != null && charge.decision() != null && !charge.decision().allowed()) {
            return null;
        }
        AgentLlmTransport.Response response = call.get();
        if (charge != null) {
            AgentLlmCallMetrics m = AgentLlmCallMetrics.of(response);
            meter.recordOutcome(charge.usageId(), m.elapsedMs(), reported(m.promptTokens()),
                    reported(m.completionTokens()));
        }
        return response;
    }

    private static Integer reported(int tokens) {
        return tokens == AgentLlmCallMetrics.UNREPORTED ? null : tokens;
    }
}
