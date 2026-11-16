package com.sellerops.talktalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The send API as the README documents it, and the pinned destination (review item 3). No request leaves the JVM. */
class TalkTalkTransportContractTest {

    @Test
    void onlyABooleanTrueWithCode00IsAccepted() {
        assertThat(HttpTalkTalkTransport.read(200, "{\"success\":true,\"resultCode\":\"00\"}").status())
                .isEqualTo(TalkTalkOutbound.Status.API_ACCEPTED);
        assertThat(HttpTalkTalkTransport.read(200, "{\"success\":\"true\",\"resultCode\":\"00\"}").status())
                .isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(HttpTalkTalkTransport.read(200, "{\"success\":true,\"resultCode\":0}").status())
                .isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(HttpTalkTalkTransport.read(200, "{\"success\":true}").status())
                .isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(HttpTalkTalkTransport.read(200, "{\"resultCode\":\"00\"}").status())
                .isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(HttpTalkTalkTransport.read(200, "not json").status()).isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(HttpTalkTalkTransport.read(200, "").status()).isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
    }

    @Test
    void http200WithSuccessFalseIsARefusalNotASuccess() {
        TalkTalkTransport.Outcome auth = HttpTalkTalkTransport.read(200,
                "{\"success\":false,\"resultCode\":\"01\",\"resultMessage\":\"Authorization 정보 오류\"}");
        assertThat(auth.status()).isEqualTo(TalkTalkOutbound.Status.FAILED);
        assertThat(auth.resultCode()).isEqualTo("01");
        assertThat(auth.failureCode()).isEqualTo("RESULT_01");
        assertThat(HttpTalkTalkTransport.read(200, "{\"success\":false,\"resultCode\":\"99\"}").failureCode())
                .isEqualTo("RESULT_99");
    }

    @Test
    void statusLinesThatSayNothingAreUnknown() {
        assertThat(HttpTalkTalkTransport.read(500, "").status()).isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(HttpTalkTalkTransport.read(302, "").status()).isEqualTo(TalkTalkOutbound.Status.UNKNOWN);
        assertThat(HttpTalkTalkTransport.read(401, "").status()).isEqualTo(TalkTalkOutbound.Status.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://gw.talk.naver.com/chatbot/v1/event",
            "https://gw.talk.naver.com.evil.example/chatbot/v1/event",
            "https://evil.example/chatbot/v1/event",
            "https://gw.talk.naver.com:8443/chatbot/v1/event",
            "https://gw.talk.naver.com/chatbot/v1/event?x=1",
            "https://user:pw@gw.talk.naver.com/chatbot/v1/event",
            "https://gw.talk.naver.com/chatbot/v1/event#f",
            "https://gw.talk.naver.com/chatbot/v2/event",
            "https://127.0.0.1/chatbot/v1/event"
    })
    void everyOtherTargetIsRefused(String url) {
        assertThatThrownBy(() -> HttpTalkTalkTransport.requireTarget(URI.create(url)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aForeignDestinationReceivesZeroRequests() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] ok = "{\"success\":true,\"resultCode\":\"00\"}".getBytes();
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.start();
        try {
            URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chatbot/v1/event");
            HttpTalkTalkTransport real = new HttpTalkTalkTransport();
            TalkTalkTransport.Outcome o = real.send(local, "ct_key", "{\"event\":\"send\"}");
            assertThat(o.status()).isEqualTo(TalkTalkOutbound.Status.BLOCKED);
            assertThat(o.failureCode()).isEqualTo("TARGET_REFUSED");
            assertThat(hits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theProductionPathTargetsOnlyThePinnedEndpointWithTheKeyHeader() {
        List<HttpRequest> seen = new ArrayList<>();
        HttpTalkTalkTransport t = new HttpTalkTalkTransport(r -> {
            seen.add(r);
            return response(200, "{\"success\":true,\"resultCode\":\"00\"}");
        });
        assertThat(t.send("ct_key", "{\"event\":\"send\"}").status()).isEqualTo(TalkTalkOutbound.Status.API_ACCEPTED);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).uri()).isEqualTo(HttpTalkTalkTransport.ENDPOINT);
        assertThat(seen.get(0).headers().firstValue("Authorization")).contains("ct_key");
        // A key with a line break would split headers: refused, nothing exchanged.
        assertThat(t.send("ct\nkey", "{}").status()).isEqualTo(TalkTalkOutbound.Status.BLOCKED);
        assertThat(seen).hasSize(1);
    }

    @Test
    void anArmedSellerPcGateRefusesTheSendBeforeAnyExchange() {
        List<HttpRequest> seen = new ArrayList<>();
        HttpTalkTalkTransport t = new HttpTalkTalkTransport(r -> {
            seen.add(r);
            return response(200, "{\"success\":true,\"resultCode\":\"00\"}");
        });
        com.sellerops.outbound.OutboundGate.arm(true);
        try {
            TalkTalkTransport.Outcome o = t.send("ct_key", "{}");
            assertThat(o.status()).isEqualTo(TalkTalkOutbound.Status.BLOCKED);
            assertThat(o.failureCode()).isEqualTo("OUTBOUND_REFUSED");
            assertThat(seen).isEmpty();
        } finally {
            com.sellerops.outbound.OutboundGate.arm(false);
        }
    }

    @Test
    void transportChoiceFailsClosed() {
        assertThat(TalkTalkTransportConfiguration.choose(TalkTalkSettings.defaults()))
                .isInstanceOf(FakeTalkTalkTransport.class);
        TalkTalkTransport liveOff = TalkTalkTransportConfiguration.choose(
                new TalkTalkSettings("live", false, false, "", "https://x", 90, 65536, 600, 24, 600, 120));
        assertThat(liveOff).isInstanceOf(BlockedTalkTalkTransport.class);
        assertThat(liveOff.send("k", "{}").status()).isEqualTo(TalkTalkOutbound.Status.BLOCKED);
        assertThat(TalkTalkTransportConfiguration.choose(
                new TalkTalkSettings("other", true, false, "", "https://x", 90, 65536, 600, 24, 600, 120)))
                .isInstanceOf(BlockedTalkTalkTransport.class);
    }

    @Test
    void bodiesFollowTheDocumentedShapes() throws Exception {
        TalkTalkOutbound o = new TalkTalkOutbound();
        o.setKind(TalkTalkOutbound.Kind.MANUAL_REPLY);
        o.setTextContent("안내드려요");
        assertThat(TalkTalkDispatcher.body(o, "U1", "wc1234"))
                .isEqualTo("{\"event\":\"send\",\"user\":\"U1\",\"textContent\":{\"text\":\"안내드려요\"}}");
        o.setKind(TalkTalkOutbound.Kind.HANDOVER_PASS);
        assertThat(TalkTalkDispatcher.body(o, "U1", "wc1234")).isEqualTo(
                "{\"event\":\"handover\",\"user\":\"U1\",\"partner\":\"wc1234\",\"options\":{\"control\":\"passThread\",\"targetId\":1}}");
        o.setKind(TalkTalkOutbound.Kind.HANDOVER_TAKE);
        assertThat(TalkTalkDispatcher.body(o, "U1", "wc1234")).contains("\"control\":\"takeThread\"");
    }

    @Test
    void theWebhookBodyIsReadWithAHardCap() throws Exception {
        byte[] big = new byte[70_000];
        assertThat(TalkTalkWebhookController.readBounded(new java.io.ByteArrayInputStream(big), 65536)).isNull();
        byte[] exact = new byte[65536];
        assertThat(TalkTalkWebhookController.readBounded(new java.io.ByteArrayInputStream(exact), 65536))
                .hasSize(65536);
        // A chunked body that never ends is cut at cap + 1 bytes, not buffered.
        java.io.InputStream endless = new java.io.InputStream() {
            long served;
            @Override public int read() {
                served++;
                return 'a';
            }
            @Override public int read(byte[] b, int off, int len) {
                served += len;
                java.util.Arrays.fill(b, off, off + len, (byte) 'a');
                return len;
            }
        };
        assertThat(TalkTalkWebhookController.readBounded(endless, 1024)).isNull();
    }

    @Test
    void theDocumentedSourceRangesOnly() {
        assertThat(TalkTalkSourceAllowlist.documented("211.249.40.5")).isTrue();
        assertThat(TalkTalkSourceAllowlist.documented("103.6.173.31")).isTrue();
        assertThat(TalkTalkSourceAllowlist.documented("211.249.40.32")).isFalse();
        assertThat(TalkTalkSourceAllowlist.documented("8.8.8.8")).isFalse();
        assertThat(TalkTalkSourceAllowlist.documented("not-an-ip")).isFalse();
        assertThat(TalkTalkSourceAllowlist.documented(null)).isFalse();
    }

    private static HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
            }
            @Override public String body() { return body; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public URI uri() { return HttpTalkTalkTransport.ENDPOINT; }
            @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }
}
