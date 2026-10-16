package com.sellerops.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * <b>On a seller's PC, only the seller's PC may talk to this backend</b> — proposal §17.4, §18.4 test 2.
 *
 * <p>Binding to {@code 127.0.0.1} ({@link LocalSellerConfigValidator}) keeps other machines out. It does
 * not keep out a web page the seller happens to have open: the page runs on this machine, so its requests
 * arrive from loopback too. Two shapes of that are refused here, before any other filter runs:
 *
 * <ul>
 *   <li><b>DNS rebinding.</b> A hostile domain re-points its own name at {@code 127.0.0.1}; the browser then
 *       treats this backend as same-origin with that domain. Its requests still carry the hostile name in
 *       {@code Host}, so a request whose {@code Host} is not a loopback name is not for this install.</li>
 *   <li><b>A cross-site request from another origin.</b> A browser names the page that sent it in
 *       {@code Origin}. An origin that is present and not a loopback http(s) origin — including the opaque
 *       {@code null} a sandboxed frame or a {@code file:} page sends — is refused. A request with no
 *       {@code Origin} (a top-level navigation such as the Cafe24 callback, the collector, the agent
 *       runtime) is judged by {@code Host} alone.</li>
 * </ul>
 *
 * <p>This is a second fence, not the only one: every route still needs its JWT, which a hostile page
 * cannot read from this origin's storage. CORS alone was never a protection here — it governs whether the
 * page may <em>read</em> a response, not whether the request was made.
 *
 * <p>Registered only in local-seller mode ({@link LocalSellerGuardConfiguration}); the response is a bare
 * 403 that names nothing about the install.
 */
public class LoopbackRequestGuard extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!admits(request.getHeader("Host"), request.getHeader("Origin"))) {
            // setStatus, not sendError: sendError makes the container re-dispatch to /error, which Spring
            // Security then answers 401 — the refusal would leave this filter as a 403 and reach the client
            // as "log in". A refused request must end here, with nothing after it.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentLength(0);
            return;
        }
        chain.doFilter(request, response);
    }

    /** The whole decision, as a pure function of the two headers, so a test can read it directly. */
    static boolean admits(String host, String origin) {
        if (!isLoopbackHostHeader(host)) {
            return false;
        }
        return origin == null || LocalSellerConfigValidator.isLoopbackOrigin(origin);
    }

    /** {@code Host} is {@code name[:port]} or {@code [v6]:port}; only the name decides. */
    static boolean isLoopbackHostHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        // The whole header must be exactly a host and an optional numeric port; userinfo, a path or any
        // other character means it is not a host header this install answers, not a host to be extracted.
        return HOST_HEADER.matcher(raw.trim().toLowerCase(Locale.ROOT)).matches();
    }

    private static final java.util.regex.Pattern HOST_HEADER =
            java.util.regex.Pattern.compile("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]{1,5})?");
}
