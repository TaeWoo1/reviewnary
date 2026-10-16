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
 * Seller session tokens — contract §2: e-mail and password, then a session JWT (HS256, same library as the backend's
 * {@code JwtTokenProvider}). A JWT alone cannot be revoked, so {@link BearerAuthFilter} also reads the account's
 * status on every request: suspension ends every session at the next request.
 *
 * <p>Refuses to start without a key of at least 32 characters (HS256 wants 256 bits). There is no default key.
 */
@Component
public class SessionTokens {

    public static final Duration LIFETIME = Duration.ofHours(12);
    static final int MIN_SECRET_LENGTH = 32;
    private static final String TYPE_CLAIM = "typ";
    private static final String TYPE = "seller";

    public record Issued(String token, Instant expiresAt) {}

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

    public Issued issue(UUID accountId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(LIFETIME);
        String token = Jwts.builder()
                .subject(accountId.toString())
                .claim(TYPE_CLAIM, TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new Issued(token, expiresAt);
    }

    /** The account a valid, unexpired seller session names; empty for anything else. */
    public Optional<UUID> verify(String token) {
        try {
            var claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            if (!TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
