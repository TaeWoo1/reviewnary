package com.sellerops.vendorlink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.outbound.OutboundGate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link VendorServerClient} over the JDK HTTP client. Every request is checked as {@code Destination.VENDOR}
 * before it leaves ({@link OutboundGate}), so in a seller-PC install the configured base URL can only name the
 * vendor server's own host. Failures carry no request material.
 */
public class JdkVendorServerClient implements VendorServerClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public JdkVendorServerClient(URI base) {
        String s = base.toString();
        this.base = URI.create(s.endsWith("/") ? s : s + "/");
    }

    @Override
    public Code requestCode(String deviceName, String appVersion, String publicKeyBase64) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("deviceName", deviceName);
        if (appVersion != null) {
            body.put("appVersion", appVersion);
        }
        body.put("publicKey", publicKeyBase64);
        Answer a = send(post("v1/installs/code", body));
        if (a.status() != 200) {
            throw new Unreachable();
        }
        return new Code(text(a.json(), "deviceCode"), text(a.json(), "userCode"), instant(a.json(), "expiresAt"),
                a.json().path("interval").asInt(3));
    }

    @Override
    public Poll poll(String deviceCode) {
        Answer a = send(post("v1/installs/token", Map.of("deviceCode", deviceCode)));
        if (a.status() == 200) {
            try {
                return new Linked(text(a.json(), "token"), UUID.fromString(text(a.json(), "installId")),
                        instant(a.json(), "expiresAt"));
            } catch (IllegalArgumentException e) {
                throw new Unreachable();
            }
        }
        if (a.status() == 400) {
            String error = a.json().path("error").asText("");
            if (error.equals("authorization_pending")) {
                return new Pending();
            }
            if (error.equals("expired_token")) {
                return new Expired();
            }
        }
        throw new Unreachable();
    }

    @Override
    public PublicIp publicIp(String installToken) {
        Answer a = send(HttpRequest.newBuilder(base.resolve("v1/ip")).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + installToken).header("Accept", "application/json")
                .GET().build());
        return switch (a.status()) {
            case 200 -> new Ipv4(text(a.json(), "ipv4"));
            case 422 -> new NoIpv4();
            case 401, 403 -> new NotAuthorized();
            default -> throw new Unreachable();
        };
    }

    @Override
    public URI approvalPage(String userCode) {
        return base.resolve("link?code=" + java.net.URLEncoder.encode(userCode, StandardCharsets.UTF_8));
    }

    @Override
    public void unlink(String installToken) {
        send(HttpRequest.newBuilder(base.resolve("v1/installs/me")).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + installToken).DELETE().build());
    }

    private HttpRequest post(String path, Map<String, String> body) {
        try {
            return HttpRequest.newBuilder(base.resolve(path)).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new Unreachable();
        }
    }

    private record Answer(int status, JsonNode json) {}

    private Answer send(HttpRequest request) {
        OutboundGate.check(request.uri(), OutboundGate.Destination.VENDOR);
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try {
                json = response.body() == null || response.body().isBlank() ? JSON.createObjectNode()
                        : JSON.readTree(response.body());
            } catch (IOException e) {
                json = JSON.createObjectNode();
            }
            return new Answer(response.statusCode(), json);
        } catch (IOException e) {
            throw new Unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable();
        }
    }

    private static Instant instant(JsonNode json, String field) {
        try {
            return Instant.parse(text(json, field));
        } catch (java.time.format.DateTimeParseException e) {
            throw new Unreachable();
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode v = json.path(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new Unreachable();
        }
        return v.asText();
    }
}
