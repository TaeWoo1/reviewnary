package com.sellerops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * <b>The workspace server refuses to boot with a local backend's defaults</b> —
 * {@code docs/standing/server_operation_boundary_v1.md} §6. Every other mode boots as before, even with every value
 * the workspace server refuses; the workspace server boots clean, and each one of its settings left at the default
 * stops it alone.
 */
class WorkspaceServerConfigValidatorTest {

    private static WorkspaceServerConfigValidator v(String mode, boolean enforced, boolean sendGate, String token,
                                                    boolean pull) {
        return new WorkspaceServerConfigValidator(mode, enforced, sendGate, token, pull);
    }

    private static WorkspaceServerConfigValidator clean() {
        return v("WORKSPACE", true, true, "rvw_test-only", true);
    }

    @Test
    void aLocalOrInstallBackendChecksNothing() {
        assertThat(v("INSTALL", false, false, "", false).problems()).isEmpty();
        assertThat(v("", false, false, "", false).problems()).isEmpty();
        assertThat(v(null, false, false, null, false).problems()).isEmpty();
        v("INSTALL", false, false, "", false).afterPropertiesSet();
    }

    @Test
    void aCleanWorkspaceServerBoots() {
        assertThat(clean().problems()).isEmpty();
        clean().afterPropertiesSet();
        assertThat(v(" workspace ", true, true, "rvw_x", true).problems()).isEmpty();
    }

    @Test
    void theChannelGateLeftOffStopsTheServer() {
        WorkspaceServerConfigValidator gateOff = v("WORKSPACE", false, true, "rvw_test-only", true);
        assertThat(gateOff.problems()).singleElement().asString()
                .startsWith("SELLEROPS_CHANNEL_ACTIVATION_ENFORCED");
        assertThatThrownBy(gateOff::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SELLEROPS_CHANNEL_ACTIVATION_ENFORCED");
    }

    @Test
    void eachOtherSettingLeftAtItsDefaultStopsTheServerAlone() {
        assertThat(v("WORKSPACE", true, false, "rvw_test-only", true).problems()).singleElement().asString()
                .startsWith("SELLEROPS_LLM_SEND_GATE_ENABLED");
        assertThat(v("WORKSPACE", true, true, " ", true).problems()).singleElement().asString()
                .startsWith("SELLEROPS_VENDOR_SERVICE_TOKEN");
        assertThat(v("WORKSPACE", true, true, "rvw_test-only", false).problems()).singleElement().asString()
                .startsWith("SELLEROPS_VENDOR_REVIEW_PULL_ENABLED");
        assertThat(v("WORKSPACE", false, false, "", false).problems()).hasSize(4);
    }

    @Test
    void theMessageNamesTheSettingNeverItsValue() {
        String token = "rvw_secret-value-that-must-not-print";
        assertThatThrownBy(() -> v("WORKSPACE", false, true, token, true).afterPropertiesSet())
                .hasMessageNotContaining(token);
    }

    /** The property keys as a deployment writes them: a typo in an {@code @Value} key would make every check pass. */
    @Test
    void theRealPropertiesReachTheCheck() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(WorkspaceServerConfigValidator.class);
        runner.withPropertyValues("sellerops.vendor-link.mode=WORKSPACE",
                        "sellerops.vendor-link.service-token=rvw_test-only",
                        "sellerops.vendor-link.review-pull.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("SELLEROPS_CHANNEL_ACTIVATION_ENFORCED"));
        runner.withPropertyValues("sellerops.vendor-link.mode=WORKSPACE",
                        "sellerops.vendor-link.service-token=rvw_test-only",
                        "sellerops.vendor-link.review-pull.enabled=true",
                        "sellerops.channel-activation.enforced=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        runner.run(ctx -> assertThat(ctx).as("a backend that sets nothing boots").hasNotFailed());
    }
}
