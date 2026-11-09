package com.sellerops.inquiry.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The development channel (2026-11-05): steerable, verifies only what it accepted, and never beside the real ones. */
class FakeChannelReplyAdapterTest {

    private static ReplyPublishCommand send(String subject) {
        return new ReplyPublishCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.fromString(
                "00000000-0000-4000-8000-000000000001"), "ext-1", Instant.now(), "답변", "본문", subject);
    }

    @Test
    @DisplayName("an outage-marked inquiry is refused once as nothing sent, then accepted; verification confirms only "
            + "what it accepted")
    void steers() {
        FakeChannelReplyAdapter fake = new FakeChannelReplyAdapter("CAFE24");
        assertThat(fake.publish(send("[일시오류] 환불")).kind()).isEqualTo(ReplyPublishResult.Kind.RETRYABLE_FAILURE);
        ReplyPublishResult second = fake.publish(send("[일시오류] 환불"));
        assertThat(second.kind()).isEqualTo(ReplyPublishResult.Kind.CONFIRMED);
        assertThat(fake.verify(new ReplyVerificationCommand(null, null, null, "ext-1", null, "본문", second.providerRef()))
                .kind()).isEqualTo(ReplyVerificationResult.Kind.COMPLETED);
        assertThat(fake.verify(new ReplyVerificationCommand(null, null, null, "ext-1", null, "본문", "other")).kind())
                .isNotEqualTo(ReplyVerificationResult.Kind.COMPLETED);
        assertThat(fake.publish(send("환불")).kind()).isEqualTo(ReplyPublishResult.Kind.CONFIRMED);
    }

    @Test
    @DisplayName("refused on a prod profile and beside the real reply adapters")
    void fenced() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        com.sellerops.connector.FakeChannelBoard board = new com.sellerops.connector.FakeChannelBoard(null);
        assertThatThrownBy(() -> new FakeChannelWiring().fakeChannelReplyAdapter(prod, board))
                .isInstanceOf(IllegalStateException.class);
        MockEnvironment live = new MockEnvironment().withProperty("sellerops.inquiry.publish.execution-enabled", "true");
        assertThatThrownBy(() -> new FakeChannelWiring().fakeChannelReplyAdapter(live, board))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new FakeChannelWiring().fakeChannelReplyAdapter(new MockEnvironment(), board).channelCode())
                .isEqualTo("CAFE24");
        // The board and the collector carry the same refusals — and the collector one more: never beside the real Cafe24
        // connector (2026-11-06).
        for (MockEnvironment refused : java.util.List.of(prod, live)) {
            assertThatThrownBy(() -> new FakeChannelWiring().fakeChannelBoard(refused))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new FakeChannelWiring().fakeChannelInquiryConnector(refused, board))
                    .isInstanceOf(IllegalStateException.class);
        }
        MockEnvironment cafe24On = new MockEnvironment().withProperty("sellerops.connector.cafe24.enabled", "true");
        assertThatThrownBy(() -> new FakeChannelWiring().fakeChannelInquiryConnector(cafe24On, board))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cafe24");
        assertThat(new FakeChannelWiring().fakeChannelInquiryConnector(new MockEnvironment(), board).dedicatedChannels())
                .containsExactly("CAFE24");
    }
}
