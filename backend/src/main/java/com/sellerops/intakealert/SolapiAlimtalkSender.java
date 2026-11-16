package com.sellerops.intakealert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The operating KakaoTalk 알림톡 adapter, over SOLAPI</b> — built and tested offline, <b>never run against SOLAPI</b>
 * (night handoff 2026-11-16: no account, no template approval, no real request). It is only constructed when
 * {@code sellerops.intake-alert.mode=solapi}, {@code live-enabled=true} and every required setting is present
 * ({@link IntakeAlertSenderConfiguration}); otherwise the server records BLOCKED.
 *
 * <p>Shape, from SOLAPI's public documents (read 2026-11-16, see docs/pilot/demo_seller_validation_v1.md §7.63):
 * {@code POST /messages/v4/send-many/detail}; {@code Authorization: HMAC-SHA256 apiKey=…, date=…, salt=…, signature=…}
 * where the signature is lowercase hex of HMAC-SHA256(secret, date + salt); a message with {@code type=ATA},
 * {@code kakaoOptions.pfId/templateId/variables}, one web-link button ({@code WL}); and {@code disableSms=true}, always —
 * SOLAPI falls back to SMS by default and this service never sends a text message.
 *
 * <p><b>Results never overstate.</b> statusCode 2000 (「정상 접수」) is ACCEPTED — the provider took the request, not the
 * phone showed it. A {@code failedMessageList} entry or a 4xx is FAILED. A timeout, a broken read, a 5xx or a 2xx with no
 * readable result is UNKNOWN, and nothing here retries: SOLAPI documents no idempotency key, so a resend can arrive twice.
 * A refused connection is FAILED (the request never left), still without an automatic retry. The log carries the delivery
 * id and a closed code — never the number, the key, the signature or the message body.
 */
public final class SolapiAlimtalkSender implements IntakeAlertSender {

    private static final Logger log = LoggerFactory.getLogger(SolapiAlimtalkSender.class);
    static final String PATH = "/messages/v4/send-many/detail";
    static final String ACCEPTED_CODE = "2000";
    static final String BUTTON_NAME = "확인하기";

    public record Settings(String baseUrl, String apiKey, String apiSecret, String pfId, String templateId,
                           String senderNumber, Duration connectTimeout, Duration requestTimeout) {

        @Override
        public String toString() {
            return "SolapiSettings[baseUrl=" + baseUrl + ", apiKey=(set), apiSecret=(set), pfId=(set), templateId=(set)]";
        }
    }

    private final Settings settings;
    private final HttpClient http;
    private final Clock clock;
    private final Supplier<String> salts;
    private final ObjectMapper json = new ObjectMapper();

    public SolapiAlimtalkSender(Settings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build(), Clock.systemUTC(),
                SolapiAlimtalkSender::randomSalt);
    }

    SolapiAlimtalkSender(Settings settings, HttpClient http, Clock clock, Supplier<String> salts) {
        this.settings = settings;
        this.http = http;
        this.clock = clock;
        this.salts = salts;
    }

    @Override
    public IntakeAlertDelivery.Mode mode() {
        return IntakeAlertDelivery.Mode.LIVE;
    }

    @Override
    public boolean needsNumber() {
        return true;
    }

    @Override
    public Outcome send(OutgoingAlert alert) {
        if (alert.toPhone() == null || alert.toPhone().isBlank()) {
            return Outcome.blocked("NO_RECIPIENT_NUMBER");
        }
        HttpRequest request;
        try {
            String body = json.writeValueAsString(body(alert));
            request = HttpRequest.newBuilder(URI.create(stripSlash(settings.baseUrl()) + PATH))
                    .timeout(settings.requestTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", authorization(clock, salts.get()))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IOException | RuntimeException e) {
            log.warn("intake alert: SOLAPI 요청 준비 실패 delivery={} {}", alert.deliveryId(), e.getClass().getSimpleName());
            return new Outcome(IntakeAlertDelivery.Status.FAILED, null, "REQUEST_BUILD");
        }
        try {
            // A seller's PC never sends this (armed gate → refused before anything leaves).
            com.sellerops.outbound.OutboundGate.check(request.uri(), com.sellerops.outbound.OutboundGate.Destination.ALIMTALK);
        } catch (com.sellerops.outbound.OutboundGate.OutboundRefusedException e) {
            return Outcome.blocked("OUTBOUND_REFUSED");
        }
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            log.warn("intake alert: SOLAPI 연결 실패 delivery={}", alert.deliveryId());
            return new Outcome(IntakeAlertDelivery.Status.FAILED, null, "CONNECT");
        } catch (HttpTimeoutException e) {
            log.warn("intake alert: SOLAPI 응답 시간 초과 — 결과 확인 필요, 자동 재전송 없음 delivery={}", alert.deliveryId());
            return new Outcome(IntakeAlertDelivery.Status.UNKNOWN, null, "TIMEOUT");
        } catch (IOException e) {
            log.warn("intake alert: SOLAPI 응답 읽기 실패 — 결과 확인 필요 delivery={}", alert.deliveryId());
            return new Outcome(IntakeAlertDelivery.Status.UNKNOWN, null, "IO");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(IntakeAlertDelivery.Status.UNKNOWN, null, "INTERRUPTED");
        }
        return read(alert, response.statusCode(), response.body());
    }

    Outcome read(OutgoingAlert alert, int status, String body) {
        if (status >= 500) {
            return new Outcome(IntakeAlertDelivery.Status.UNKNOWN, null, "HTTP_" + status);
        }
        if (status >= 400) {
            return new Outcome(IntakeAlertDelivery.Status.FAILED, null, "HTTP_" + status);
        }
        try {
            JsonNode root = json.readTree(body == null ? "" : body);
            JsonNode failed = root.path("failedMessageList");
            if (failed.isArray() && !failed.isEmpty()) {
                return new Outcome(IntakeAlertDelivery.Status.FAILED, null,
                        "SOLAPI_" + closedCode(failed.get(0).path("statusCode").asText("")));
            }
            JsonNode first = root.path("messageList").path(0);
            if (ACCEPTED_CODE.equals(first.path("statusCode").asText(""))) {
                String id = first.path("messageId").asText(null);
                return new Outcome(IntakeAlertDelivery.Status.ACCEPTED, id == null ? null : clip(id, 100), null);
            }
            return new Outcome(IntakeAlertDelivery.Status.UNKNOWN, null, "NO_RESULT");
        } catch (IOException | RuntimeException e) {
            return new Outcome(IntakeAlertDelivery.Status.UNKNOWN, null, "UNREADABLE");
        }
    }

    ObjectNode body(OutgoingAlert alert) {
        ObjectNode root = json.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        ObjectNode m = messages.addObject();
        m.put("to", alert.toPhone());
        if (settings.senderNumber() != null && !settings.senderNumber().isBlank()) {
            m.put("from", settings.senderNumber());
        }
        m.put("type", "ATA");
        ObjectNode kakao = m.putObject("kakaoOptions");
        kakao.put("pfId", settings.pfId());
        kakao.put("templateId", settings.templateId());
        kakao.put("disableSms", true);
        ObjectNode vars = kakao.putObject("variables");
        for (Map.Entry<String, String> e : alert.variables().entrySet()) {
            vars.put("#{" + e.getKey() + "}", e.getValue());
        }
        ObjectNode button = kakao.putArray("buttons").addObject();
        button.put("buttonName", BUTTON_NAME);
        button.put("buttonType", "WL");
        button.put("linkMo", alert.linkUrl());
        button.put("linkPc", alert.linkUrl());
        root.put("showMessageList", true);
        root.put("allowDuplicates", false);
        return root;
    }

    /** {@code HMAC-SHA256 apiKey=…, date=…, salt=…, signature=…}; date is ISO-8601 UTC to the second. */
    String authorization(Clock at, String salt) {
        String date = at.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        return "HMAC-SHA256 apiKey=" + settings.apiKey() + ", date=" + date + ", salt=" + salt
                + ", signature=" + signature(settings.apiSecret(), date, salt);
    }

    static String signature(String secret, String date, String salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    static String randomSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String closedCode(String s) {
        String digits = s.replaceAll("[^0-9A-Za-z]", "");
        return digits.isEmpty() ? "UNSPECIFIED" : clip(digits, 20);
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
