package com.sellerops.agent.reply;

import com.sellerops.agent.llm.operator.AgentOperatorProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code sellerops.reply-agent.*} — the reply agent's switch (2026-10-28, {@code docs/standing/reply_agent_v1.md}).
 *
 * <p>Its own flag, off by default, opt-in per organisation. The vendor, model and key default to the draft capability's
 * environment values in {@code application.yml} so a deployment that already drafts can turn this on without a second
 * key; set {@code SELLEROPS_AGENT_REPLY_*} to give it its own.
 */
@Component
public class ReplyAgentProperties extends AgentOperatorProperties {

    public ReplyAgentProperties(
            @Value("${sellerops.reply-agent.enabled:false}") boolean enabled,
            @Value("${sellerops.reply-agent.enabled-org-ids:}") String enabledOrgIds,
            @Value("${sellerops.reply-agent.vendor:OPENAI}") String vendor,
            @Value("${sellerops.reply-agent.model:gpt-5-2025-08-07}") String model,
            @Value("${sellerops.reply-agent.api-key:}") String apiKey,
            @Value("${sellerops.reply-agent.max-output-tokens:4000}") int maxOutputTokens,
            @Value("${sellerops.reply-agent.reasoning-effort:low}") String reasoningEffort) {
        super(enabled, enabledOrgIds, vendor, model, apiKey, maxOutputTokens, reasoningEffort);
    }

    @Override
    public String capabilityName() {
        return "SELLEROPS_AGENT_REPLY";
    }
}
