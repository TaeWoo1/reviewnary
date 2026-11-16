package com.sellerops.talktalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The official send API, README 「보내기 API 작성 방법」 · 「오류 명세」. <b>Not run against TalkTalk in this unit.</b>
 *
 * <p><b>The target is pinned, not configured.</b> The request carries the organization's send key, so it may go to one
 * place only: {@link #ENDPOINT}, over HTTPS, on the default port, with no user-info, query or fragment, and redirects
 * are never followed. There is no property, constructor or profile that names another URL — the test seam replaces the
 * exchange, never the destination, and {@link #requireTarget} refuses every other URI before a connection is opened.
 *
 * <p><b>The body decides, not the status line.</b> Only a JSON boolean {@code success: true} with the string
 * {@code resultCode: "00"} is accepted; {@code success: false} is a refusal (01 인증, 02 형식, 99 기타); anything else —
 * a string {@code "true"}, a missing field, an unreadable body, a 5xx, a timeout — is UNKNOWN, never success.
 * Nothing here logs a key, a user value or a text.
 */
public class HttpTalkTalkTransport implements TalkTalkTransport {

    public static final URI ENDPOINT = URI.create("https://gw.talk.naver.com/chatbot/v1/event");

    private static final Logger log = LoggerFactory.getLogger(HttpTalkTalkTransport.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One request/response exchange. Production: the JDK client. Tests: a recorder — the URI is still checked. */
    interface Exchange {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Exchange exchange;

    public HttpTalkTalkTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.exchange = r -> client.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    HttpTalkTalkTransport(Exchange exchange) {
        this.exchange = exchange;
    }

    /** The only destination a send key may travel to. Throws for anything else. */
    static URI requireTarget(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || !"gw.talk.naver.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"/chatbot/v1/event".equals(uri.getRawPath())) {
            throw new IllegalArgumentException("TalkTalk send target refused");
        }
        return uri;
    }

    @Override
    public TalkTalkConnection.Mode mode() {
        return TalkTalkConnection.Mode.LIVE;
    }

    @Override
    public Outcome send(String sendKey, String jsonBody) {
        return send(ENDPOINT, sendKey, jsonBody);
    }

    /** Package-visible so a test can show a foreign target is refused before any exchange happens. */
    Outcome send(URI target, String sendKey, String jsonBody) {
        URI uri;
        try {
            uri = requireTarget(target);
        } catch (IllegalArgumentException e) {
            return new Outcome(TalkTalkOutbound.Status.BLOCKED, null, "TARGET_REFUSED");
        }
        if (sendKey == null || sendKey.isBlank() || sendKey.contains("\n") || sendKey.contains("\r")) {
            return new Outcome(TalkTalkOutbound.Status.BLOCKED, null, "KEY_UNUSABLE");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Authorization", sendKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            // The backend's outbound check (a no-op unless a seller-PC install armed it). The pinned target above is
            // this transport's own boundary; the gate is the install's, and neither replaces the other.
            com.sellerops.outbound.OutboundGate.check(request.uri(), com.sellerops.outbound.OutboundGate.Destination.TALKTALK);
        } catch (com.sellerops.outbound.OutboundGate.OutboundRefusedException e) {
            return new Outcome(TalkTalkOutbound.Status.BLOCKED, null, "OUTBOUND_REFUSED");
        }
        HttpResponse<String> response;
        try {
            response = exchange.send(request);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            // The connection was never made: nothing was sent. Still not retried automatically.
            log.info("talktalk_send outcome=FAILED code=CONNECT_FAILED");
            return new Outcome(TalkTalkOutbound.Status.FAILED, null, "CONNECT_FAILED");
        } catch (HttpTimeoutException e) {
            log.info("talktalk_send outcome=UNKNOWN code=TIMEOUT");
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "TIMEOUT");
        } catch (IOException e) {
            log.info("talktalk_send outcome=UNKNOWN code=IO");
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "IO_ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "INTERRUPTED");
        }
        if (response == null) {
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "NO_RESPONSE");
        }
        Outcome outcome = read(response.statusCode(), response.body());
        log.info("talktalk_send http={} outcome={} resultCode={}", response.statusCode(), outcome.status(),
                outcome.resultCode());
        return outcome;
    }

    /** The documented reading of one response. */
    static Outcome read(int status, String body) {
        if (status >= 300 && status < 400) {
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "REDIRECT_NOT_FOLLOWED");
        }
        if (status >= 500) {
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "HTTP_" + status);
        }
        if (status != 200) {
            return new Outcome(TalkTalkOutbound.Status.FAILED, null, "HTTP_" + status);
        }
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (IOException e) {
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "BODY_UNREADABLE");
        }
        if (root == null || !root.isObject()) {
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "BODY_UNREADABLE");
        }
        JsonNode success = root.get("success");
        JsonNode resultCode = root.get("resultCode");
        String code = resultCode != null && resultCode.isTextual() ? resultCode.asText() : null;
        if (code != null && code.length() > 16) {
            code = code.substring(0, 16);
        }
        if (success == null || !success.isBoolean()) {
            return new Outcome(TalkTalkOutbound.Status.UNKNOWN, code, "SUCCESS_NOT_BOOLEAN");
        }
        if (success.booleanValue()) {
            return "00".equals(code) ? new Outcome(TalkTalkOutbound.Status.API_ACCEPTED, code, null)
                    : new Outcome(TalkTalkOutbound.Status.UNKNOWN, code, "SUCCESS_WITHOUT_00");
        }
        return new Outcome(TalkTalkOutbound.Status.FAILED, code, "RESULT_" + (code == null ? "NONE" : code));
    }
}
