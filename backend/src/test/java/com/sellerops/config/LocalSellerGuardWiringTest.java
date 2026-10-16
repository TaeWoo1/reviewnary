package com.sellerops.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>The guard is actually in front of the application when local-seller mode is on</b>, not only correct
 * as a function. A real embedded server boots with the flag and a clean local configuration (H2 is loopback
 * by construction, bound to 127.0.0.1), and raw HTTP is written to its socket — so the {@code Host} header is
 * exactly what a rebinding browser would send, which no HTTP client library lets a test set.
 *
 * <p>The same unauthenticated route answers 403 for a rebinding {@code Host} or a foreign {@code Origin}, and
 * reaches Spring Security — 401, the JWT still required — for a loopback request. The 401 half is what keeps
 * a guard that refused everything from passing.
 *
 * <p><b>A 403 alone is not the guard's.</b> Spring's CORS processing also answers a foreign {@code Origin}
 * with 403 ("Invalid CORS request"), so with the guard switched off the foreign-origin case would still pass
 * on status. The guard's refusal is the one with an empty body; asserting that is what makes this test fail
 * when the guard is missing (proven by disabling it: both refusal cases go red).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sellerops.local-seller.enabled=true",
        "server.address=127.0.0.1",
        "sellerops.jwt.secret=test-only-local-seller-secret-of-sufficient-length",
        "sellerops.cors.origin=http://localhost:5173",
        // Its own in-memory database, like every context with its own properties: sharing the default
        // `sellerops_test` lets this context's create-drop and seed collide with the cached default context.
        "spring.datasource.url=jdbc:h2:mem:local_seller_guard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
// Closed after this class: the context arms the process-wide OutboundGate, and a cached context would leave it
// armed for every later test class in the JVM.
@DirtiesContext
class LocalSellerGuardWiringTest {

    @LocalServerPort
    private int port;

    @Test
    void aLoopbackRequestReachesSecurityAndStillNeedsItsToken() throws Exception {
        assertThat(status("127.0.0.1:" + port, null)).isEqualTo(401);
        assertThat(status("localhost:" + port, "http://localhost:5173")).isEqualTo(401);
    }

    @Test
    void aRebindingHostIsRefusedBeforeAnythingElse() throws Exception {
        assertThat(response("evil.example.com:" + port, null)).isEqualTo(GUARD_REFUSAL);
    }

    @Test
    void aForeignOriginIsRefusedEvenOnALoopbackHost() throws Exception {
        assertThat(response("127.0.0.1:" + port, "https://evil.example.com")).isEqualTo(GUARD_REFUSAL);
    }

    /** What {@link LoopbackRequestGuard} answers: 403 with an empty body. */
    private static final String GUARD_REFUSAL = "403 body=0";

    private int status(String host, String origin) throws Exception {
        return Integer.parseInt(response(host, origin).split(" ")[0]);
    }

    /** Writes one HTTP/1.1 request by hand; returns "{status} body={content-length}". */
    private String response(String host, String origin) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            StringBuilder request = new StringBuilder()
                    .append("GET /api/attention/items HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n");
            if (origin != null) {
                request.append("Origin: ").append(origin).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String code = in.readLine().split(" ")[1];
            String length = "?";
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    length = line.substring(15).trim();
                }
            }
            return code + " body=" + length;
        }
    }
}
