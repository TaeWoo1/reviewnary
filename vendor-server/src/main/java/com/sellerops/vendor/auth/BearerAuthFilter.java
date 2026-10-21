package com.sellerops.vendor.auth;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.Delegates;
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
 *       is treated as a session JWT, whose signed {@code typ} claim says whether it is a seller's or a delegate's.
 *       The kind is decided by the prefix and that claim, never by trying one and then the other.</li>
 *   <li>A bearer that does not authenticate ends the request with 401 here. It does not fall through to see
 *       whether some other rule admits it — that is how a revoked credential quietly becomes a working one.</li>
 *   <li>The kinds carry different authorities ({@link Principal#INSTALL}, {@link Principal#SELLER},
 *       {@link DelegatePrincipal#DELEGATE}), and the route rules in {@code SecurityConfig} name one of them — an
 *       install token never opens a seller route, a seller session never opens an install route, and neither ever
 *       opens a delegate route (nor a delegate session theirs).</li>
 *   <li>The person behind a session is re-read on every request: a suspended seller or delegate gets 401 at the
 *       next one, and so does a session that was logged out or ended ({@link SessionRevocations}).</li>
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

    /** A signed-in delegate (vendor staff). {@code admin} is the role, carried for the routes that will need it. */
    public record DelegatePrincipal(UUID delegateId, boolean admin) {
        public static final String DELEGATE = "ROLE_DELEGATE";
    }

    private final Installs installs;
    private final SessionTokens sessions;
    private final SellerAccounts accounts;
    private final Delegates delegates;
    private final SessionRevocations revocations;

    public BearerAuthFilter(Installs installs, SessionTokens sessions, SellerAccounts accounts, Delegates delegates,
                            SessionRevocations revocations) {
        this.installs = installs;
        this.sessions = sessions;
        this.accounts = accounts;
        this.delegates = delegates;
        this.revocations = revocations;
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
                : sessions.verifyAny(bearer).filter(revocations::live).flatMap(this::session);
        if (auth.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(auth.get());
        chain.doFilter(request, response);
    }

    private Optional<UsernamePasswordAuthenticationToken> session(SessionTokens.Session session) {
        UUID subject = session.subject();
        return switch (session.kind()) {
            case SELLER -> Optional.of(subject).filter(accounts::isActive)
                    .map(id -> token(new Principal(id, null), Principal.SELLER));
            case DELEGATE -> delegates.active(subject)
                    .map(d -> token(new DelegatePrincipal(d.id(), d.role() == Delegates.Role.ADMIN),
                            DelegatePrincipal.DELEGATE));
        };
    }

    private static UsernamePasswordAuthenticationToken token(Object principal, String authority) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(authority)));
    }
}
