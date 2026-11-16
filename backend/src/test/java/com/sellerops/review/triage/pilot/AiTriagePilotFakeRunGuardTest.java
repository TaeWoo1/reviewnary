package com.sellerops.review.triage.pilot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The triage classifier has its own connection, so it refuses a fake-model run (outside review, 2026-10-29). */
class AiTriagePilotFakeRunGuardTest {

    @Test
    @DisplayName("on with the fake model refuses to start; either alone starts")
    void theClassifierRefusesAFakeRun() {
        assertThatThrownBy(() -> AiTriagePilotProperties.refuseAFakeRun(true, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fake");
        assertThatCode(() -> AiTriagePilotProperties.refuseAFakeRun(true, false)).doesNotThrowAnyException();
        assertThatCode(() -> AiTriagePilotProperties.refuseAFakeRun(false, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a Runyour pilot can be configured without sending anything, while the org/key gate stays in place")
    void theRouterUsesTheSameOrgAndKeyGate() {
        java.util.UUID org = java.util.UUID.randomUUID();
        AiTriagePilotProperties on = new AiTriagePilotProperties(true, org.toString(), "RUNYOUR", "model", "test-key",
                true, 300, null, 1);
        assertThatCode(on::refuseAFakeRun).doesNotThrowAnyException();
        assertThat(on.isEnabledFor(org)).isTrue();
        assertThat(on.isEnabledFor(java.util.UUID.randomUUID())).isFalse();
    }
}
