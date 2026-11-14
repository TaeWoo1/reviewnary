package com.sellerops.inquiry.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.publish.dto.PublishCapabilityView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the read-only publish-capability surface. On the fail-closed default —
 * execution disabled and no reply adapters registered — it must report exactly that, so
 * an orchestration client can verify the send path is off. (Live-verified against a real
 * backend; this pins the contract in the gate.)
 */
class PublishCapabilityControllerTest {

    @Test
    void reportsFailClosedWhenExecutionDisabledAndNoAdapters() {
        // No adapters registered (the default when execution-enabled is off). channels is
        // unused by registeredChannelCodes(), so a null repository is fine here.
        ChannelReplyAdapterRegistry registry = new ChannelReplyAdapterRegistry(null, List.of());
        PublishCapabilityController controller = new PublishCapabilityController(registry, new InquiryReplyCapabilityRegistry(),
                null, null, null, false, "");

        PublishCapabilityView view = controller.capability();

        assertThat(view.executionEnabled()).isFalse();
        assertThat(view.replyAdapterChannelCodes()).isEmpty();
    }

    @Test
    void registryReportsNoRegisteredChannelsWhenEmpty() {
        ChannelReplyAdapterRegistry registry = new ChannelReplyAdapterRegistry(null, List.of());
        assertThat(registry.registeredChannelCodes()).isEmpty();
    }

    @Test
    void reportsExecutionEnabledWhenFlagOn() {
        // The config the orchestration client's fail-closed guard must reject.
        ChannelReplyAdapterRegistry registry = new ChannelReplyAdapterRegistry(null, List.of());
        PublishCapabilityController controller = new PublishCapabilityController(registry, new InquiryReplyCapabilityRegistry(),
                null, null, null, true, "");

        PublishCapabilityView view = controller.capability();

        assertThat(view.executionEnabled()).isTrue();
    }

    /** A minimal real adapter — publish and verify are never reached by the capability read. */
    private static ChannelReplyAdapter realAdapter(String code) {
        return new ChannelReplyAdapter() {
            @Override public String channelCode() { return code; }
            @Override public ReplyPublishResult publish(ReplyPublishCommand command) { throw new AssertionError(); }
            @Override public ReplyVerificationResult verify(ReplyVerificationCommand command) { throw new AssertionError(); }
        };
    }

    @Test
    void namesTheFakeChannelAsSimulatedWhileExecutionStaysOff() {
        // 2026-11-09 demo §7.10 3A: the screen may offer a MOCK registration, and executionEnabled must still say
        // nothing real can be sent — every orchestration guard reads that field.
        ChannelReplyAdapterRegistry registry = new ChannelReplyAdapterRegistry(null,
                List.of(new FakeChannelReplyAdapter("CAFE24")));
        PublishCapabilityController controller = new PublishCapabilityController(registry, new InquiryReplyCapabilityRegistry(),
                null, null, null, false, "");

        PublishCapabilityView view = controller.capability();

        assertThat(view.executionEnabled()).isFalse();
        assertThat(view.replyAdapterChannelCodes()).containsExactly("CAFE24");
        assertThat(view.simulatedChannelCodes()).containsExactly("CAFE24");
    }

    @Test
    void aRealAdapterIsNeverReportedAsSimulated() {
        ChannelReplyAdapterRegistry registry = new ChannelReplyAdapterRegistry(null,
                List.of(realAdapter("GMARKET"), new FakeChannelReplyAdapter("CAFE24"), realAdapter("CAFE24")));

        // A code with any real adapter reads as real; only an all-fake code is simulated.
        assertThat(registry.simulatedChannelCodes()).isEmpty();

        PublishCapabilityController off = new PublishCapabilityController(registry, new InquiryReplyCapabilityRegistry(),
                null, null, null, false, "");
        assertThat(off.capability().simulatedChannelCodes()).isEmpty();
    }

    @Test
    void realExecutionNeverCarriesASimulatedChannel() {
        // FakeChannelWiring refuses to start beside execution; if the two ever met, the real reading wins.
        ChannelReplyAdapterRegistry registry = new ChannelReplyAdapterRegistry(null,
                List.of(new FakeChannelReplyAdapter("CAFE24")));
        PublishCapabilityController on = new PublishCapabilityController(registry, new InquiryReplyCapabilityRegistry(),
                null, null, null, true, "");

        assertThat(on.capability().executionEnabled()).isTrue();
        assertThat(on.capability().simulatedChannelCodes()).isEmpty();
    }

    @Test
    void theDefaultShapeHasNoSimulatedChannel() {
        ChannelReplyAdapterRegistry registry = new ChannelReplyAdapterRegistry(null, List.of());
        PublishCapabilityController controller = new PublishCapabilityController(registry, new InquiryReplyCapabilityRegistry(),
                null, null, null, false, "");
        assertThat(controller.capability().simulatedChannelCodes()).isEmpty();
    }
}
