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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Override
    public List<Store> stores(String installToken) {
        Answer a = relay(HttpRequest.newBuilder(base.resolve("v1/stores")).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + installToken).header("Accept", "application/json")
                .GET().build());
        if (!a.json().isArray()) {
            throw new Unreachable();
        }
        List<Store> stores = new ArrayList<>();
        for (JsonNode s : a.json()) {
            stores.add(new Store(uuid(s, "id"), text(s, "channel"), text(s, "label"),
                    optionalInstant(s, "lastUploadAt")));
        }
        return stores;
    }

    @Override
    public Optional<String> accountEmail(String installToken) {
        Answer a = relay(HttpRequest.newBuilder(base.resolve("v1/installs/me")).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + installToken).header("Accept", "application/json")
                .GET().build());
        return Optional.ofNullable(optionalText(a.json(), "accountEmail"));
    }

    @Override
    public FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit) {
        Answer a = relay(HttpRequest.newBuilder(base.resolve("v1/stores/" + storeId + "/reviews?cursor=" + cursor
                        + "&limit=" + limit)).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + installToken).header("Accept", "application/json")
                .GET().build());
        JsonNode events = a.json().path("events");
        if (!events.isArray() || !a.json().path("cursor").canConvertToLong()) {
            throw new Unreachable();
        }
        List<FeedEvent> out = new ArrayList<>();
        for (JsonNode e : events) {
            JsonNode r = e.path("review");
            JsonNode attachment = r.path("attachment");
            boolean hasAttachment = attachment.isObject();
            if (!e.path("seq").canConvertToLong() || !r.path("rating").canConvertToInt()
                    || !r.path("body").isTextual() || !r.path("replied").isBoolean()) {
                throw new Unreachable();
            }
            out.add(new FeedEvent(e.path("seq").asLong(), text(e, "kind"), new FeedReview(uuid(r, "id"),
                    text(r, "reviewNo"), optionalText(r, "productNo"), optionalText(r, "productName"),
                    r.path("rating").asInt(), r.path("body").asText(), text(r, "bodyFingerprint"),
                    hasAttachment ? text(attachment, "url") : null, hasAttachment ? text(attachment, "kind") : null,
                    instant(r, "writtenAt"), optionalText(r, "displayState"), r.path("replied").asBoolean(),
                    optionalText(r, "repliedAt") == null ? null : instant(r, "repliedAt"))));
        }
        return new FeedPage(out, a.json().path("cursor").asLong(), a.json().path("more").asBoolean(false));
    }

    @Override
    public void ackReviews(String installToken, UUID storeId, long cursor) {
        try {
            relay(HttpRequest.newBuilder(base.resolve("v1/stores/" + storeId + "/reviews/ack"))
                    .timeout(REQUEST_TIMEOUT).header("Authorization", "Bearer " + installToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("cursor", cursor)),
                            StandardCharsets.UTF_8))
                    .build());
        } catch (IOException e) {
            throw new Unreachable();
        }
    }

    @Override
    public TaskSnapshot submitDraft(String installToken, UUID storeId, UUID reviewId, String body, DraftMeta meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reviewBodyFingerprint", meta.reviewBodyFingerprint());
        m.put("generatedAt", meta.generatedAt().toString());
        m.put("knowledgeFp", meta.knowledgeFp());
        m.put("policyFp", meta.policyFp());
        m.put("voiceFp", meta.voiceFp());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reviewId", reviewId.toString());
        request.put("body", body);
        request.put("meta", m);
        Answer a = taskCall(jsonPost("v1/stores/" + storeId + "/tasks", installToken, request));
        return task(a.json());
    }

    @Override
    public TaskSnapshot approve(String installToken, UUID taskId, int version, String fingerprint, String commandId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", version);
        request.put("fingerprint", fingerprint);
        request.put("commandId", commandId);
        return task(taskCall(jsonPost("v1/tasks/" + taskId + "/approve", installToken, request)).json());
    }

    @Override
    public TaskSnapshot cancel(String installToken, UUID taskId, String commandId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandId", commandId);
        return task(taskCall(jsonPost("v1/tasks/" + taskId + "/cancel", installToken, request)).json());
    }

    @Override
    public TaskChanges taskChanges(String installToken, UUID storeId, long cursor, int limit) {
        Answer a = relay(HttpRequest.newBuilder(base.resolve("v1/stores/" + storeId + "/tasks/changes?cursor="
                        + cursor + "&limit=" + limit)).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + installToken).header("Accept", "application/json")
                .GET().build());
        JsonNode changes = a.json().path("changes");
        if (!changes.isArray() || !a.json().path("cursor").canConvertToLong()) {
            throw new Unreachable();
        }
        List<TaskChange> out = new ArrayList<>();
        for (JsonNode c : changes) {
            if (!c.path("seq").canConvertToLong()) {
                throw new Unreachable();
            }
            out.add(new TaskChange(c.path("seq").asLong(), text(c, "state"), task(c.path("task"))));
        }
        return new TaskChanges(out, a.json().path("cursor").asLong(), a.json().path("more").asBoolean(false));
    }

    @Override
    public void markNoReplyNeeded(String installToken, UUID storeId, UUID reviewId, String commandId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("noReplyNeeded", true);
        request.put("commandId", commandId);
        relay(jsonPost("v1/stores/" + storeId + "/reviews/" + reviewId + "/disposition", installToken, request));
    }

    private HttpRequest jsonPost(String path, String installToken, Map<String, Object> body) {
        try {
            return HttpRequest.newBuilder(base.resolve(path)).timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + installToken).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new Unreachable();
        }
    }

    /** A task call: 400 and 409 carry the server's closed error code; the rest as {@link #relay}. */
    private Answer taskCall(HttpRequest request) {
        Answer a = send(request);
        if (a.status() == 400 || a.status() == 409) {
            String code = a.json().path("error").asText("");
            throw new TaskRefused(code.matches("[a-z_]{1,40}") ? code : "refused");
        }
        if (a.status() == 401 || a.status() == 403 || a.status() == 404) {
            throw new Refused();
        }
        if (a.status() / 100 != 2) {
            throw new Unreachable();
        }
        return a;
    }

    private static TaskSnapshot task(JsonNode t) {
        if (!t.path("stateVersion").canConvertToInt() || !t.path("draftVersion").canConvertToInt()
                || !t.path("draftBody").isTextual() || !t.path("editedOnMobile").isBoolean()) {
            throw new Unreachable();
        }
        return new TaskSnapshot(uuid(t, "id"), uuid(t, "reviewId"), text(t, "state"), t.path("stateVersion").asInt(),
                t.path("draftVersion").asInt(), t.path("draftBody").asText(), text(t, "draftFingerprint"),
                optionalText(t, "approvedFingerprint"), t.path("editedOnMobile").asBoolean());
    }

    /** A relay call: 2xx is the answer, a refused token or store is {@link Refused}, anything else unreachable. */
    private Answer relay(HttpRequest request) {
        Answer a = send(request);
        if (a.status() == 401 || a.status() == 403 || a.status() == 404) {
            throw new Refused();
        }
        if (a.status() / 100 != 2) {
            throw new Unreachable();
        }
        return a;
    }

    private static UUID uuid(JsonNode json, String field) {
        try {
            return UUID.fromString(text(json, field));
        } catch (IllegalArgumentException e) {
            throw new Unreachable();
        }
    }

    private static String optionalText(JsonNode json, String field) {
        JsonNode v = json.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
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

    /** Null when absent; a present value that is not an instant is a broken answer. */
    private static Instant optionalInstant(JsonNode json, String field) {
        return optionalText(json, field) == null ? null : instant(json, field);
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
