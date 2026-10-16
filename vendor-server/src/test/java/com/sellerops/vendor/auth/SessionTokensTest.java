package com.sellerops.vendor.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.vendor.MutableClock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTokensTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-for-hs256";

    @Test
    void noKeyOrAShortKeyRefusesToStart() {
        assertThatThrownBy(() -> new SessionTokens("", Clock.at())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SessionTokens("short", Clock.at())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSessionVerifiesUntilItExpires() {
        MutableClock clock = Clock.at();
        SessionTokens tokens = new SessionTokens(SECRET, clock);
        UUID account = UUID.randomUUID();
        String token = tokens.issue(account).token();
        assertThat(tokens.verify(token)).hasValue(account);
        clock.advance(SessionTokens.LIFETIME.plus(Duration.ofSeconds(1)));
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    void anotherKeyATamperedTokenOrAnotherTypeIsRefused() {
        MutableClock clock = Clock.at();
        SessionTokens tokens = new SessionTokens(SECRET, clock);
        String other = new SessionTokens(SECRET + "-other", clock).issue(UUID.randomUUID()).token();
        assertThat(tokens.verify(other)).isEmpty();
        String token = tokens.issue(UUID.randomUUID()).token();
        assertThat(tokens.verify(token.substring(0, token.length() - 2) + "AA")).isEmpty();
        String untyped = Jwts.builder().subject(UUID.randomUUID().toString())
                .expiration(Date.from(clock.instant().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertThat(tokens.verify(untyped)).as("same key, but not a seller session").isEmpty();
    }

    private static final class Clock {
        static MutableClock at() {
            return new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        }
    }
}
