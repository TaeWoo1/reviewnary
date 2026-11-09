package com.sellerops.agent.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The production transport bean.
 *
 * <p>Unconditional, unlike {@code PublishExecutionWiring}: the transport is inert without a key and
 * an org, and {@link AgentDraftService} is the only thing that can reach it. Making the BEAN
 * conditional would move the gate to bean presence and leave two places that decide whether the
 * capability is on — which is how a deployment ends up with the flag off and the capability
 * reachable, or the reverse.
 */
@Configuration
public class AgentLlmConfiguration {

    @Bean
    AgentLlmTransport agentLlmTransport(org.springframework.core.env.Environment env) {
        // The end-to-end check without a model (2026-10-29): development only, never on a prod profile.
        if (env.getProperty("sellerops.dev.fake-llm", Boolean.class, false)) {
            if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))) {
                throw new IllegalStateException("sellerops.dev.fake-llm is for development and cannot run on prod");
            }
            // Every capability on this transport is faked. The review triage classifier is not on it — it builds its
            // own HTTP client — so it refuses a fake run itself (AiTriagePilotProperties): this file reads no other
            // capability's flag (AgentDraftBoundaryTest).
            return new FakeAgentLlmTransport();
        }
        return new JdkAgentLlmTransport();
    }
}
