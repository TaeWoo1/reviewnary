package com.sellerops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>The seller's PC serves its own screen, and the screen never becomes a way around the API's tokens</b>
 * ({@link LocalSellerScreenConfiguration}). A real embedded server in local-seller mode over a made-up built screen;
 * raw HTTP on the socket, as in {@link LocalSellerGuardWiringTest}, so the {@code Host} header is exactly what a
 * browser — or a rebinding page — sends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sellerops.local-seller.enabled=true",
        "server.address=127.0.0.1",
        "sellerops.jwt.secret=test-only-local-seller-secret-of-sufficient-length",
        "sellerops.cors.origin=http://127.0.0.1:5173",
        "spring.datasource.url=jdbc:h2:mem:local_seller_screen;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
// Closed after this class: the context arms the process-wide OutboundGate (see LocalSellerGuardWiringTest).
@DirtiesContext
class LocalSellerScreenServingTest {

    private static final Path ROOT;
    private static final Path SCREEN;

    static {
        try {
            ROOT = Files.createTempDirectory("screen-test");
            SCREEN = Files.createDirectories(ROOT.resolve("dist"));
            Files.writeString(SCREEN.resolve("index.html"), "<!doctype html><title>ENTRY-PAGE</title>");
            Files.createDirectories(SCREEN.resolve("assets"));
            Files.writeString(SCREEN.resolve("assets/app-1a2b.js"), "console.log('BUNDLE')");
            // Beside the screen, not in it: what a traversal would be after.
            Files.writeString(ROOT.resolve("keys.env"), "OUTSIDE-THE-SCREEN");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void screenDir(DynamicPropertyRegistry registry) {
        registry.add("sellerops.local-seller.screen-dir", SCREEN::toString);
    }

    @LocalServerPort
    private int port;

    @Test
    void theEntryPageIsServedWithTheScreensHeadersNotTheApis() throws Exception {
        String r = get("GET", "/", null);
        assertThat(status(r)).isEqualTo(200);
        assertThat(r).contains("ENTRY-PAGE").contains("X-Frame-Options: DENY")
                .contains("X-Content-Type-Options: nosniff").contains("Cache-Control: no-store");
        // The API's `default-src 'none'` would forbid the screen's own scripts; the screen carries its CSP as <meta>.
        assertThat(r).doesNotContain("Content-Security-Policy");
    }

    @Test
    void aScreenRouteSurvivesAReload() throws Exception {
        assertThat(get("GET", "/reviews/reply/abc?from=work", null)).contains("ENTRY-PAGE");
        assertThat(get("GET", "/link", null)).contains("ENTRY-PAGE");
    }

    @Test
    void bundlesAreCachedForGoodAndAMissingOneIsNotAPage() throws Exception {
        String bundle = get("GET", "/assets/app-1a2b.js", null);
        assertThat(status(bundle)).isEqualTo(200);
        assertThat(bundle).contains("BUNDLE").contains("immutable");
        String missing = get("GET", "/assets/gone-9z.js", null);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(missing).doesNotContain("ENTRY-PAGE");
    }

    @Test
    void theApiStillNeedsItsTokenAndIsNeverAnsweredWithAPage() throws Exception {
        for (String path : new String[] {"/api/attention/items", "/api/no-such-route", "/actuator/env"}) {
            String r = get("GET", path, null);
            assertThat(status(r)).as(path).isEqualTo(401);
            assertThat(r).as(path).doesNotContain("ENTRY-PAGE");
        }
        assertThat(status(get("POST", "/", null))).as("only GET and HEAD are screen requests").isEqualTo(401);
    }

    @Test
    void nothingOutsideTheScreenDirectory() throws Exception {
        for (String path : new String[] {"/../keys.env", "/%2e%2e/keys.env", "/assets/..%2f..%2fkeys.env",
                "/..%5ckeys.env"}) {
            assertThat(get("GET", path, null)).as(path).doesNotContain("OUTSIDE-THE-SCREEN");
        }
    }

    @Test
    void aRebindingHostGetsNoPage() throws Exception {
        String r = get("GET", "/", "evil.example.com:" + port);
        assertThat(status(r)).isEqualTo(403);
        assertThat(r).doesNotContain("ENTRY-PAGE");
    }

    @Test
    void aDirectoryWithoutABuiltScreenRefusesToBoot() {
        assertThatThrownBy(() -> new LocalSellerScreenConfiguration(ROOT.toString()))
                .hasMessageContaining("index.html").hasMessageNotContaining(ROOT.toString());
    }

    private static int status(String response) {
        return Integer.parseInt(response.substring(9, 12));
    }

    /** One HTTP/1.1 request written by hand; the whole response, headers and body. */
    private String get(String method, String path, String host) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            String request = method + " " + path + " HTTP/1.1\r\nHost: " + (host == null ? "127.0.0.1:" + port : host)
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            return all.toString(StandardCharsets.UTF_8);
        }
    }
}
