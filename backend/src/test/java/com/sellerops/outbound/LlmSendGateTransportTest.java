package com.sellerops.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.JdkAgentLlmTransport;
import com.sellerops.review.triage.llm.JdkLlmHttpClient;
import com.sellerops.review.triage.llm.LlmHttpClient;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A held body never leaves the real transports.</b> The target is a closed loopback port, which the armed
 * {@link OutboundGate} admits: if the send gate ever stops holding, the request fails to connect and the marker is an
 * exception type instead of {@code held:…} — red, with nothing having left the machine. (A model host here would turn
 * a regression into a real request; the first draft of this test did exactly that during its own break-proof.)
 */
class LlmSendGateTransportTest {

    private static final URI CLOSED_LOOPBACK = URI.create("http://127.0.0.1:1/v1/chat/completions");
    private static final String ADDRESS_BODY =
            "{\"messages\":[{\"role\":\"user\",\"content\":\"서울시 강남구 테헤란로 123으로 보내 주세요\"}]}";

    @AfterEach
    void disarm() {
        OutboundGate.arm(false);
    }

    @Test
    void theAgentTransportReturnsTheHeldMarkerWithoutSending() {
        OutboundGate.arm(true);
        AgentLlmTransport.Response r = new JdkAgentLlmTransport().post(CLOSED_LOOPBACK, Map.of(), ADDRESS_BODY);
        assertThat(r.status()).isZero();
        assertThat(r.body()).isEqualTo("held:ADDRESS");
        assertThat(r.elapsedMs()).as("nothing was timed because nothing was sent").isZero();
    }

    @Test
    void theTriageTransportReturnsTheHeldMarkerWithoutSending() {
        OutboundGate.arm(true);
        LlmHttpClient.Response r = new JdkLlmHttpClient().post(CLOSED_LOOPBACK, Map.of(), ADDRESS_BODY);
        assertThat(r.status()).isZero();
        assertThat(r.body()).isEqualTo("held:ADDRESS");
    }

    @Test
    void aBuyerPhotoIsHeldOnTheTransportReviewMediaUses() {
        OutboundGate.arm(true);
        String photo = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\","
                + "\"image_url\":{\"url\":\"data:image/jpeg;base64,/9j/AAAA\"}}]}]}";
        AgentLlmTransport.Response held = new JdkAgentLlmTransport().post(CLOSED_LOOPBACK, Map.of(), photo);
        assertThat(held.body()).isEqualTo("held:IMAGE");
        AgentLlmTransport.Response product =
                new JdkAgentLlmTransport().postProductImages(CLOSED_LOOPBACK, Map.of(), photo);
        assertThat(product.body()).as("a product image is sent — here, to a closed port")
                .isNotEqualTo("held:IMAGE").doesNotStartWith("held:");
    }
}
