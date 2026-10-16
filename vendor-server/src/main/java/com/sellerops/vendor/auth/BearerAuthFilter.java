package com.sellerops.vendor.auth;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.install.InstallTokens;
import com.sellerops.vendor.install.Installs;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * <b>One bearer, one kind of caller, no fallback</b> — contract §2.
 *
 * <ol>
 *   <li>A bearer starting with {@link InstallTokens#PREFIX} is an install token, looked up by hash. Anything else
 *       is treated as a seller session JWT. The kind is decided by the prefix, never by trying one and then the
 *       other.</li>
 *   <li>A bearer that does not authenticate ends the request with 401 here. It does not fall through to see
 *       whether some other rule admits it — that is how a revoked credential quietly becomes a working one.</li>
 *   <li>The two kinds carry different authorities ({@link Principal#INSTALL}, {@link Principal#SELLER}), and the
 *       route rules in {@code SecurityConfig} name one or the other — an install token never opens a seller
 *       route, and a seller session never opens an install route.</li>
 * </ol>
 *
 * <p>Not a Spring bean: {@code SecurityConfig} builds it, so it is not also registered as a plain servlet filter.
 */
public class BearerAuthFilter extends OncePerRequestFilter {

    /** Who is asking. {@code installId} is null for a seller session. */
    public record Principal(UUID accountId, UUID installId) {
        public static final String SELLER = "ROLE_SELLER";
        public static final String INSTALL = "ROLE_INSTALL";
    }

    private final Installs installs;
    private final SessionTokens sessions;
    private final SellerAccounts accounts;

    public BearerAuthFilter(Installs installs, SessionTokens sessions, SellerAccounts accounts) {
        this.installs = installs;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String bearer = header.substring(7).trim();
        Optional<UsernamePasswordAuthenticationToken> auth = InstallTokens.looksLikeInstallToken(bearer)
                ? installs.authenticate(bearer).map(i -> token(new Principal(i.accountId(), i.installId()),
                        Principal.INSTALL))
                : sessions.verify(bearer).filter(accounts::isActive)
                        .map(id -> token(new Principal(id, null), Principal.SELLER));
        if (auth.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(auth.get());
        chain.doFilter(request, response);
    }

    private static UsernamePasswordAuthenticationToken token(Principal principal, String authority) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(authority)));
    }
}
