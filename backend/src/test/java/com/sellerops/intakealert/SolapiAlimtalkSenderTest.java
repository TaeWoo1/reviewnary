package com.sellerops.intakealert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The operating adapter against a local HTTP server standing in for SOLAPI — no request leaves this machine. Checks the
 * request shape, the signature and every result path; that accepted is never "delivered" and that nothing retries.
 */
class SolapiAlimtalkSenderTest {

    HttpServer server;
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    String serve(int status, String response, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(SolapiAlimtalkSender.PATH, ex -> {
            hits.incrementAndGet();
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    SolapiAlimtalkSender sender(String baseUrl, String from) {
        SolapiAlimtalkSender.Settings s = new SolapiAlimtalkSender.Settings(baseUrl, "KEY", "SECRET", "PF1", "TPL1",
                from, Duration.ofSeconds(1), Duration.ofMillis(400));
        return new SolapiAlimtalkSender(s, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                Clock.fixed(Instant.parse("2026-11-16T03:04:05.678Z"), ZoneOffset.UTC), () -> "salt-0123456789ab");
    }

    static OutgoingAlert alert(String phone) {
        return new OutgoingAlert(UUID.randomUUID(), phone, Map.of("제목", "새로 확인된 리뷰가 있어요", "가게", "데모"),
                "[리뷰너리] ...", "https://app.example.test/notice/abc");
    }

    @Test
    @DisplayName("HMAC-SHA256 서명은 RFC 4231 시험값과 같은 소문자 hex — 서명 대상은 date+salt")
    void signatureMatchesRfc4231() {
        assertThat(SolapiAlimtalkSender.signature("Jefe", "what do ya want ", "for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    @DisplayName("요청: ATA · disableSms=true · WL 버튼 · #{변수} · 초 단위 date 서명 헤더, 접수 2000은 ACCEPTED")
    void requestShapeAndAccepted() throws Exception {
        String base = serve(200, """
                {"groupInfo":{"groupId":"G1"},"failedMessageList":[],
                 "messageList":[{"messageId":"M123","statusCode":"2000","statusMessage":"정상 접수"}]}
                """, 0);
        IntakeAlertSender.Outcome o = sender(base, null).send(alert("01012345678"));
        assertThat(o.status()).isEqualTo(IntakeAlertDelivery.Status.ACCEPTED);
        assertThat(o.providerMessageId()).isEqualTo("M123");

        JsonNode root = new ObjectMapper().readTree(body.get());
        JsonNode m = root.path("messages").path(0);
        assertThat(m.path("to").asText()).isEqualTo("01012345678");
        assertThat(m.has("from")).as("from is optional for 알림톡 without SMS fallback").isFalse();
        assertThat(m.path("type").asText()).isEqualTo("ATA");
        JsonNode k = m.path("kakaoOptions");
        assertThat(k.path("pfId").asText()).isEqualTo("PF1");
        assertThat(k.path("templateId").asText()).isEqualTo("TPL1");
        assertThat(k.path("disableSms").asBoolean()).isTrue();
        assertThat(k.path("variables").path("#{제목}").asText()).isEqualTo("새로 확인된 리뷰가 있어요");
        JsonNode b = k.path("buttons").path(0);
        assertThat(b.path("buttonType").asText()).isEqualTo("WL");
        assertThat(b.path("linkMo").asText()).isEqualTo("https://app.example.test/notice/abc");
        assertThat(b.path("linkPc").asText()).isEqualTo("https://app.example.test/notice/abc");
        assertThat(root.path("showMessageList").asBoolean()).isTrue();

        String date = "2026-11-16T03:04:05Z";
        assertThat(auth.get()).isEqualTo("HMAC-SHA256 apiKey=KEY, date=" + date + ", salt=salt-0123456789ab, signature="
                + SolapiAlimtalkSender.signature("SECRET", date, "salt-0123456789ab"));
    }

    @Test
    @DisplayName("발신번호를 설정하면 from을 함께 보낸다")
    void fromWhenConfigured() throws Exception {
        String base = serve(200, "{\"messageList\":[{\"messageId\":\"M\",\"statusCode\":\"2000\"}]}", 0);
        sender(base, "0212345678").send(alert("01012345678"));
        assertThat(new ObjectMapper().readTree(body.get()).path("messages").path(0).path("from").asText())
                .isEqualTo("0212345678");
    }

    @Test
    @DisplayName("failedMessageList는 FAILED(공급자 코드), 4xx는 FAILED")
    void failures() throws Exception {
        String base = serve(200, """
                {"failedMessageList":[{"statusCode":"3040","statusMessage":"등록되지 않은 발신번호입니다."}]}
                """, 0);
        IntakeAlertSender.Outcome o = sender(base, null).send(alert("01012345678"));
        assertThat(o.status()).isEqualTo(IntakeAlertDelivery.Status.FAILED);
        assertThat(o.failureCode()).isEqualTo("SOLAPI_3040");
        stop();
        base = serve(401, "{\"errorCode\":\"x\"}", 0);
        assertThat(sender(base, null).send(alert("01012345678")).failureCode()).isEqualTo("HTTP_401");
    }

    @Test
    @DisplayName("시간 초과·5xx·읽을 수 없는 응답은 UNKNOWN이고 한 번만 요청한다(자동 재전송 없음)")
    void unknownNeverRetried() throws Exception {
        String base = serve(200, "{}", 1500);
        IntakeAlertSender.Outcome o = sender(base, null).send(alert("01012345678"));
        assertThat(o.status()).isEqualTo(IntakeAlertDelivery.Status.UNKNOWN);
        assertThat(o.failureCode()).isEqualTo("TIMEOUT");
        assertThat(hits.get()).isEqualTo(1);
        stop();
        base = serve(503, "", 0);
        assertThat(sender(base, null).send(alert("01012345678")).status()).isEqualTo(IntakeAlertDelivery.Status.UNKNOWN);
        stop();
        base = serve(200, "not json", 0);
        assertThat(sender(base, null).send(alert("01012345678")).failureCode()).isEqualTo("UNREADABLE");
        stop();
        base = serve(200, "{\"messageList\":[]}", 0);
        assertThat(sender(base, null).send(alert("01012345678")).failureCode()).isEqualTo("NO_RESULT");
    }

    @Test
    @DisplayName("연결 거부는 FAILED(CONNECT), 번호가 없으면 요청하지 않고 BLOCKED")
    void connectRefusedAndNoNumber() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        IntakeAlertSender.Outcome o = sender("http://127.0.0.1:" + port, null).send(alert("01012345678"));
        assertThat(o.status()).isEqualTo(IntakeAlertDelivery.Status.FAILED);
        assertThat(o.failureCode()).isEqualTo("CONNECT");
        String base = serve(200, "{}", 0);
        assertThat(sender(base, null).send(alert(null)).failureCode()).isEqualTo("NO_RECIPIENT_NUMBER");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("판매자 PC(무장된 OutboundGate)에서는 요청 전에 막고 BLOCKED")
    void sellerPcNeverSends() {
        com.sellerops.outbound.OutboundGate.arm(true);
        try {
            IntakeAlertSender.Outcome o = sender("https://api.solapi.com", null).send(alert("01012345678"));
            assertThat(o.status()).isEqualTo(IntakeAlertDelivery.Status.BLOCKED);
            assertThat(o.failureCode()).isEqualTo("OUTBOUND_REFUSED");
        } finally {
            com.sellerops.outbound.OutboundGate.arm(false);
        }
    }

    @Test
    @DisplayName("설정 출력과 알림 객체 출력에 키·번호가 없다")
    void toStringsHideSecrets() {
        SolapiAlimtalkSender.Settings s = new SolapiAlimtalkSender.Settings("https://api.solapi.com", "KEY-123",
                "SECRET-456", "PF1", "TPL1", null, Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(s.toString()).doesNotContain("KEY-123", "SECRET-456");
        assertThat(alert("01012345678").toString()).doesNotContain("01012345678");
    }
}
