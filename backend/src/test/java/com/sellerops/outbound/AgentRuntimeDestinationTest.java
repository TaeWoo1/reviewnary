package com.sellerops.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The agent runtime is reached on this machine only (Reply Agent v1, 2026-10-28) — never an outside host. */
class AgentRuntimeDestinationTest {

    @Test
    @DisplayName("loopback is allowed; any outside host, https or not, is refused")
    void loopbackOnly() {
        assertThat(OutboundGate.allows(URI.create("http://127.0.0.1:8787/api/reply-runs"), OutboundGate.Destination.AGENT_RUNTIME)).isTrue();
        assertThat(OutboundGate.allows(URI.create("http://localhost:8787/api/reply-runs"), OutboundGate.Destination.AGENT_RUNTIME)).isTrue();
        assertThat(OutboundGate.allows(URI.create("https://runtime.example.com/api/reply-runs"), OutboundGate.Destination.AGENT_RUNTIME)).isFalse();
        assertThat(OutboundGate.allows(URI.create("https://api.openai.com/api/reply-runs"), OutboundGate.Destination.AGENT_RUNTIME)).isFalse();
    }
}
