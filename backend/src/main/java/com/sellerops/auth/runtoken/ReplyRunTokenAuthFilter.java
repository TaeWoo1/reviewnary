package com.sellerops.auth.runtoken;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.organization.OrganizationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * <b>A run token opens the reply-run routes and nothing else</b> (owner, 2026-10-28, {@code reply_agent_v1.md} §2) —
 * the device-token filter's shape: it owns every bearer with the run prefix, admits it on {@link #ALLOWED} or ends the
 * request 401, and never lets it reach a filter that might read it as something else.
 *
 * <p>The principal has no user: the run is the actor. The run's id rides on the request ({@link #RUN_ID_ATTRIBUTE});
 * the routes read the review or inquiry from the run, never from the request, so a run cannot be pointed elsewhere.
 */
@Component
public class ReplyRunTokenAuthFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "ROLE_REPLY_RUN";
    public static final String RUN_ID_ATTRIBUTE = "sellerops.replyRunId";

    /** The only path a run token reaches. Everything a run may do is under it. */
    public static final List<String> ALLOWED = List.of("/api/agent/reply/run/");

    private final ObjectProvider<ReplyRunTokenService> tokens;
    private final OrganizationRepository organizations;

    public ReplyRunTokenAuthFilter(ObjectProvider<ReplyRunTokenService> tokens, OrganizationRepository organizations) {
        this.tokens = tokens;
        this.organizations = organizations;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String bearer = header.substring(7);
        if (!ReplyRunTokenService.looksLikeRunToken(bearer)) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (ALLOWED.stream().noneMatch(path::startsWith)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        ReplyRunTokenService service = tokens.getIfAvailable();
        Optional<ReplyAgentRun> run = service == null ? Optional.empty() : service.authenticate(bearer);
        if (run.isEmpty() || !organizations.existsById(run.get().getOrgId())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken(new AuthPrincipal(null, run.get().getOrgId(), null), null,
                List.of(new SimpleGrantedAuthority(AUTHORITY)));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.setAttribute(RUN_ID_ATTRIBUTE, run.get().getId());
        chain.doFilter(request, response);
    }
}
