package com.sellerops.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.outbound.OutboundGate.Destination;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The seller-PC outbound allow-list — scope lock v1.14 ⑤. Each destination admits its own host and refuses
 * another destination's host, so a list that admitted everything could not pass.
 */
class OutboundGateTest {

    @AfterEach
    void disarm() {
        OutboundGate.arm(false);
    }

    private static boolean allows(String uri, Destination d) {
        return OutboundGate.allows(URI.create(uri), d);
    }

    @Test
    void eachChannelReachesOnlyItsOwnOfficialHost() {
        assertThat(allows("https://api.commerce.naver.com/external/v1/x", Destination.NAVER_COMMERCE)).isTrue();
        assertThat(allows("https://api-gateway.coupang.com/v2/x", Destination.COUPANG)).isTrue();
        assertThat(allows("https://mymall.cafe24api.com/api/v2/admin/boards", Destination.CAFE24)).isTrue();

        assertThat(allows("https://api-gateway.coupang.com/v2/x", Destination.NAVER_COMMERCE)).isFalse();
        assertThat(allows("https://api.commerce.naver.com/x", Destination.CAFE24)).isFalse();
        assertThat(allows("https://evil.example.com/x", Destination.COUPANG)).isFalse();
    }

    @Test
    void lookAlikeAndDowngradedHostsAreRefused() {
        assertThat(allows("http://api.commerce.naver.com/x", Destination.NAVER_COMMERCE)).isFalse();
        assertThat(allows("https://api.commerce.naver.com.evil.example.com/x", Destination.NAVER_COMMERCE)).isFalse();
        assertThat(allows("https://user@api.commerce.naver.com/x", Destination.NAVER_COMMERCE)).isFalse();
        assertThat(allows("https://cafe24api.com/x", Destination.CAFE24)).isFalse();
        assertThat(allows("https://a.b.cafe24api.com/x", Destination.CAFE24)).isFalse();
        assertThat(allows("https://mymall.cafe24api.com.evil.example.com/x", Destination.CAFE24)).isFalse();
    }

    @Test
    void modelCallsReachOnlyTheNamedProviders() {
        assertThat(allows("https://api.anthropic.com/v1/messages", Destination.LLM)).isTrue();
        assertThat(allows("https://api.openai.com/v1/chat/completions", Destination.LLM)).isTrue();
        assertThat(allows("https://llm-proxy.example.com/v1/messages", Destination.LLM)).isFalse();
    }

    @Test
    void productImagesMayComeFromAnyHttpsHostButChannelsOutsideTheSetMayNot() {
        assertThat(allows("https://shop-phinf.pstatic.net/a.jpg", Destination.PRODUCT_IMAGE)).isTrue();
        assertThat(allows("http://shop-phinf.pstatic.net/a.jpg", Destination.PRODUCT_IMAGE)).isFalse();
        assertThat(allows("https://sa2.esmplus.com/x", Destination.ESM)).isFalse();
        assertThat(allows("https://api.11st.co.kr/x", Destination.ELEVENST)).isFalse();
        assertThat(allows("https://x.ssg.com/x", Destination.SSG)).isFalse();
    }

    @Test
    void theVendorServerIsReachableOnlyOnLoopbackUntilItsHostIsFixed() {
        assertThat(allows("https://vendor.example.com/v1/ip", Destination.VENDOR)).isFalse();
        assertThat(allows("https://api.commerce.naver.com/v1/ip", Destination.VENDOR)).isFalse();
        assertThat(allows("http://127.0.0.1:8090/v1/ip", Destination.VENDOR)).isTrue();
    }

    @Test
    void loopbackIsAlwaysAllowedForSyntheticServers() {
        for (Destination d : Destination.values()) {
            assertThat(allows("http://127.0.0.1:18080/x", d)).as(d.name()).isTrue();
            assertThat(allows("http://localhost:18080/x", d)).as(d.name()).isTrue();
        }
    }

    @Test
    void checkDoesNothingUntilArmedAndRefusesWithoutNamingTheHostOnceArmed() {
        URI foreign = URI.create("https://evil.example.com/path?seller=secret-seller-id");
        assertThatCode(() -> OutboundGate.check(foreign, Destination.NAVER_COMMERCE)).doesNotThrowAnyException();

        OutboundGate.arm(true);
        assertThatThrownBy(() -> OutboundGate.check(foreign, Destination.NAVER_COMMERCE))
                .isInstanceOf(OutboundGate.OutboundRefusedException.class)
                .hasMessageContaining("NAVER_COMMERCE")
                .hasMessageNotContaining("evil.example.com")
                .hasMessageNotContaining("secret-seller-id");
        assertThatCode(() -> OutboundGate.check(URI.create("https://api.commerce.naver.com/x"),
                Destination.NAVER_COMMERCE)).doesNotThrowAnyException();
    }
}
