package com.sellerops.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * <b>On the Reviewnary server the model-send check is on unless a profile turns it off</b> —
 * {@code docs/standing/server_operation_boundary_v1.md} §4.1-1 (2026-10-31). Not a seller PC here: the outbound gate is
 * never armed, and the check still runs.
 */
class LlmSendGateServerTest {

    private static final String BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"연락처 010-1234-5678 로 주세요\"}]}";

    @AfterEach
    void disarm() {
        LlmSendGate.arm(false);
        OutboundGate.arm(false);
    }

    @Test
    void withNoSettingTheServerChecksWhatLeavesForAModel() {
        new ApplicationContextRunner().withUserConfiguration(LlmSendGateConfiguration.class).run(context -> {
            assertThat(OutboundGate.armed()).as("not a seller PC").isFalse();
            assertThat(LlmSendGate.armed()).isTrue();
            LlmSendGate.Prepared out = LlmSendGate.prepare(BODY, LlmSendGate.Images.NONE);
            assertThat(out.body()).doesNotContain("010-1234-5678");
            assertThat(out.removed()).contains(LlmSendGate.Finding.PHONE);
        });
        assertThat(LlmSendGate.armed()).as("disarmed when the context closes").isFalse();
    }

    @Test
    void onlyAnExplicitFalseTurnsItOff() {
        new ApplicationContextRunner().withUserConfiguration(LlmSendGateConfiguration.class)
                .withPropertyValues("sellerops.llm.send-gate.enabled=false").run(context -> {
                    assertThat(LlmSendGate.armed()).isFalse();
                    assertThat(LlmSendGate.prepare(BODY, LlmSendGate.Images.NONE).body()).isEqualTo(BODY);
                });
    }
}
