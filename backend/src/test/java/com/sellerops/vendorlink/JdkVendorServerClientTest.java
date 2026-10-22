package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.outbound.OutboundGate;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The PC's vendor-server client speaks the server's wire shapes, and only to the vendor host</b> — contract §2 ·
 * §7. A loopback stub answers with the bodies {@code vendor-server/}'s controllers produce; every request stays on
 * this machine. The refusal case targets a non-loopback name with the gate armed, so it is refused before any
 * connection is attempted (FINDINGS: a transport test must never be able to reach a real host).
 */
class JdkVendorServerClientTest {

    private HttpServer stub;
    private final List<String> seen = new ArrayList<>();
    private URI base;

    @BeforeEach
    void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        route("/v1/installs/code", 200, "{\"deviceCode\":\"dc\",\"userCode\":\"BCDFGHJK\","
                + "\"expiresAt\":\"2026-10-17T00:10:00Z\",\"interval\":3}");
        route("/v1/installs/token", 400, "{\"error\":\"authorization_pending\"}");
        route("/v1/ip", 200, "{\"ipv4\":\"203.0.113.7\"}");
        // GET answers with the PC's row and its account (vendor-server InstallController.Me); DELETE with nothing.
        stub.createContext("/v1/installs/me", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            seen.add(exchange.getRequestMethod() + " /v1/installs/me auth=" + (auth != null) + " ");
            byte[] out = "GET".equals(exchange.getRequestMethod())
                    ? "{\"deviceName\":\"Seller Mac\",\"accountEmail\":\"seller@example.com\"}"
                            .getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(out.length == 0 ? 204 : 200, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        stub.start();
        base = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        stub.stop(0);
        OutboundGate.arm(false);
    }

    private void route(String path, int status, String body) {
        stub.createContext(path, exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String in = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + path + " auth=" + (auth != null) + " " + in);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
    }

    @Test
    void theGrantAndTheIpCallsReadTheServersShapes() {
        OutboundGate.arm(true);
        JdkVendorServerClient client = new JdkVendorServerClient(base);
        VendorServerClient.Code code = client.requestCode("macOS (aarch64)", null, "AQID");
        assertThat(code.userCode()).isEqualTo("BCDFGHJK");
        assertThat(code.intervalSeconds()).isEqualTo(3);
        assertThat(client.poll("dc")).isInstanceOf(VendorServerClient.Pending.class);
        assertThat(client.publicIp("rvi_x")).isEqualTo(new VendorServerClient.Ipv4("203.0.113.7"));
        assertThat(client.approvalPage("BCDFGHJK").toString()).isEqualTo(base + "/link?code=BCDFGHJK");
        assertThat(client.accountEmail("rvi_x")).contains("seller@example.com");
        client.unlink("rvi_x");
        assertThat(seen).containsExactly(
                "POST /v1/installs/code auth=false {\"deviceName\":\"macOS (aarch64)\",\"publicKey\":\"AQID\"}",
                "POST /v1/installs/token auth=false {\"deviceCode\":\"dc\"}",
                "GET /v1/ip auth=true ",
                "GET /v1/installs/me auth=true ",
                "DELETE /v1/installs/me auth=true ");
    }

    @Test
    void aConfiguredHostOtherThanTheVendorsIsRefusedBeforeAnythingIsSent() {
        OutboundGate.arm(true);
        JdkVendorServerClient client = new JdkVendorServerClient(URI.create("https://vendor.invalid"));
        assertThatThrownBy(() -> client.publicIp("rvi_x")).isInstanceOf(OutboundGate.OutboundRefusedException.class);
    }

    @Test
    void theReviewRelayReadsTheServersFeedShapes() {
        OutboundGate.arm(true);
        String store = "6f1c2a3b-0000-4000-8000-000000000001";
        List<String> uris = new ArrayList<>();
        stub.createContext("/v1/stores", exchange -> {
            uris.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            // The bodies vendor-server's StoreController and StoreReviewController produce (Boot's ISO instants).
            String body = path.equals("/v1/stores")
                    ? "[{\"id\":\"" + store + "\",\"channel\":\"NAVER\",\"label\":\"데모 스토어\","
                    + "\"lastUploadAt\":\"2026-10-20T01:00:00Z\"},{\"id\":\"" + store.replace("001", "002")
                    + "\",\"channel\":\"NAVER\",\"label\":\"새 스토어\",\"lastUploadAt\":null}]"
                    : path.endsWith("/ack") ? ""
                    : "{\"events\":[{\"seq\":7,\"kind\":\"NEW\",\"review\":{\"id\":\"6f1c2a3b-0000-4000-8000-0000000000aa\","
                    + "\"reviewNo\":\"1000000001\",\"productNo\":\"SKU-1\",\"productName\":\"합성 상품\",\"kind\":\"일반\","
                    + "\"rating\":5,\"body\":\"합성 리뷰\",\"bodyFingerprint\":\"" + "a".repeat(64) + "\","
                    + "\"attachment\":{\"url\":\"https://phinf.pstatic.net/checkout.phinf/x.jpg\",\"kind\":\"PHOTO\"},"
                    + "\"writtenAt\":\"2026-10-01T01:00:00Z\",\"modifiedAt\":null,\"displayState\":\"노출\","
                    + "\"replied\":true,\"repliedAt\":\"2026-10-02T02:00:00Z\",\"relatedReviewNo\":null,"
                    + "\"relatedBody\":null}}],\"cursor\":7,\"more\":true}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body.isEmpty() ? 204 : 200, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        JdkVendorServerClient client = new JdkVendorServerClient(base);

        assertThat(client.stores("rvi_x")).containsExactly(
                new VendorServerClient.Store(java.util.UUID.fromString(store), "NAVER", "데모 스토어",
                        Instant.parse("2026-10-20T01:00:00Z")),
                new VendorServerClient.Store(java.util.UUID.fromString(store.replace("001", "002")), "NAVER",
                        "새 스토어", null));
        VendorServerClient.FeedPage page = client.reviewFeed("rvi_x", java.util.UUID.fromString(store), 6, 200);
        assertThat(page.cursor()).isEqualTo(7);
        assertThat(page.more()).isTrue();
        assertThat(page.events()).singleElement().satisfies(e -> {
            assertThat(e.seq()).isEqualTo(7);
            assertThat(e.kind()).isEqualTo("NEW");
            assertThat(e.review().reviewNo()).isEqualTo("1000000001");
            assertThat(e.review().attachmentKind()).isEqualTo("PHOTO");
            assertThat(e.review().replied()).isTrue();
            assertThat(e.review().repliedAt()).hasToString("2026-10-02T02:00:00Z");
            assertThat(e.review().displayState()).isEqualTo("노출");
        });
        client.ackReviews("rvi_x", java.util.UUID.fromString(store), 7);
        assertThat(uris).containsExactly("GET /v1/stores ",
                "GET /v1/stores/" + store + "/reviews?cursor=6&limit=200 ",
                "POST /v1/stores/" + store + "/reviews/ack {\"cursor\":7}");
    }

    @Test
    void aRefusedTokenOrStoreIsRefusedNotUnreachable() {
        OutboundGate.arm(true);
        route("/v1/stores", 404, "");
        JdkVendorServerClient client = new JdkVendorServerClient(base);
        assertThatThrownBy(() -> client.stores("rvi_x")).isInstanceOf(VendorServerClient.Refused.class);
    }

    @Test
    void aClosedPortIsUnreachableNotAnAnswer() {
        JdkVendorServerClient client = new JdkVendorServerClient(URI.create("http://127.0.0.1:1"));
        assertThatThrownBy(() -> client.publicIp("rvi_x")).isInstanceOf(VendorServerClient.Unreachable.class);
    }
}
