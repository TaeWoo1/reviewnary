package com.sellerops.review.triage.pilot;

import static org.assertj.core.api.Assertions.assertThatCode;
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
    @DisplayName("the pilot refuses the eval-only RUNYOUR vendor when on; off, or another vendor, starts")
    void thePilotRefusesTheEvalOnlyRouter() {
        assertThatThrownBy(() -> AiTriagePilotProperties.refuseAnEvalOnlyVendor(true, "RUNYOUR"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("eval-harness only");
        assertThatThrownBy(() -> AiTriagePilotProperties.refuseAnEvalOnlyVendor(true, " runyour "))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> AiTriagePilotProperties.refuseAnEvalOnlyVendor(false, "RUNYOUR")).doesNotThrowAnyException();
        assertThatCode(() -> AiTriagePilotProperties.refuseAnEvalOnlyVendor(true, "OPENAI")).doesNotThrowAnyException();
    }
}
