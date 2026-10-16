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
        route("/v1/installs/me", 204, "");
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
        client.unlink("rvi_x");
        assertThat(seen).containsExactly(
                "POST /v1/installs/code auth=false {\"deviceName\":\"macOS (aarch64)\",\"publicKey\":\"AQID\"}",
                "POST /v1/installs/token auth=false {\"deviceCode\":\"dc\"}",
                "GET /v1/ip auth=true ",
                "DELETE /v1/installs/me auth=true ");
    }

    @Test
    void aConfiguredHostOtherThanTheVendorsIsRefusedBeforeAnythingIsSent() {
        OutboundGate.arm(true);
        JdkVendorServerClient client = new JdkVendorServerClient(URI.create("https://vendor.invalid"));
        assertThatThrownBy(() -> client.publicIp("rvi_x")).isInstanceOf(OutboundGate.OutboundRefusedException.class);
    }

    @Test
    void aClosedPortIsUnreachableNotAnAnswer() {
        JdkVendorServerClient client = new JdkVendorServerClient(URI.create("http://127.0.0.1:1"));
        assertThatThrownBy(() -> client.publicIp("rvi_x")).isInstanceOf(VendorServerClient.Unreachable.class);
    }
}
