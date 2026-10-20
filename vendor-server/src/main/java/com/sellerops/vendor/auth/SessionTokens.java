package com.sellerops.vendor.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Session tokens — contract §2: a seller's (e-mail and password) and a delegate's (password and authenticator code),
 * each a session JWT (HS256, same library as the backend's {@code JwtTokenProvider}) whose {@code typ} claim names
 * which of the two it is. A JWT alone cannot be revoked, so {@link BearerAuthFilter} also reads the account's status
 * on every request: suspension ends every session at the next request.
 *
 * <p>Refuses to start without a key of at least 32 characters (HS256 wants 256 bits). There is no default key.
 */
@Component
public class SessionTokens {

    public static final Duration LIFETIME = Duration.ofHours(12);
    static final int MIN_SECRET_LENGTH = 32;
    private static final String TYPE_CLAIM = "typ";

    /** Whose session it is. The claim value is fixed: a token of one kind never verifies as the other. */
    public enum Kind {
        SELLER("seller"), DELEGATE("delegate");

        private final String claim;

        Kind(String claim) {
            this.claim = claim;
        }
    }

    public record Issued(String token, Instant expiresAt) {}

    /** A verified session: its kind and the id it names (a seller account or a delegate). */
    public record Session(Kind kind, UUID subject) {}

    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public SessionTokens(@Value("${vendor.session.secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    SessionTokens(String secret, Clock clock) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("VENDOR_SESSION_SECRET must be set to at least " + MIN_SECRET_LENGTH
                    + " characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** A seller session. */
    public Issued issue(UUID accountId) {
        return issue(Kind.SELLER, accountId);
    }

    public Issued issue(Kind kind, UUID subject) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(LIFETIME);
        String token = Jwts.builder()
                .subject(subject.toString())
                .claim(TYPE_CLAIM, kind.claim)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new Issued(token, expiresAt);
    }

    /** The account a valid, unexpired seller session names; empty for anything else. */
    public Optional<UUID> verify(String token) {
        return verifyAny(token).filter(s -> s.kind() == Kind.SELLER).map(Session::subject);
    }

    /** A valid, unexpired session of either kind; empty for anything else, including an unknown {@code typ}. */
    public Optional<Session> verifyAny(String token) {
        try {
            var claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            String type = claims.get(TYPE_CLAIM, String.class);
            for (Kind kind : Kind.values()) {
                if (kind.claim.equals(type)) {
                    return Optional.of(new Session(kind, UUID.fromString(claims.getSubject())));
                }
            }
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
