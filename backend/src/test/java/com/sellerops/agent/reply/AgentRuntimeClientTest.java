package com.sellerops.agent.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>An install's runtime is required, and a missing one is said</b> (owner, 2026-10-29: 「설치본에서 agent-runtime 누락이
 * 기존 생성기로 조용히 대체되지 않게」). The drafting paths' refusal to fall back is tested where they are
 * ({@code ReviewDraftAheadTest}, {@code ReviewDraftPreparerTest}); this is the client those paths ask.
 */
class AgentRuntimeClientTest {

    @Test
    @DisplayName("required with no address refuses to boot, naming the setting and not a value")
    void requiredWithoutAnAddressRefusesToBoot() {
        assertThatThrownBy(() -> new AgentRuntimeClient("  ", true))
                .hasMessageContaining("sellerops.agent-runtime.base-url");
        assertThat(new AgentRuntimeClient("", false).isConfigured()).as("a deployment without it boots as before").isFalse();
        AgentRuntimeClient install = new AgentRuntimeClient("http://127.0.0.1:8787/", true);
        assertThat(install.isRequired()).isTrue();
        assertThat(install.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("a runtime that is configured but not listening is no report — the caller says so, nothing stands in")
    void aRuntimeThatIsNotListeningIsNoReport() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        AgentRuntimeClient client = new AgentRuntimeClient("http://127.0.0.1:" + port, true);
        assertThat(client.runReply(UUID.randomUUID(), "rrt_synthetic")).isEmpty();
    }
}
