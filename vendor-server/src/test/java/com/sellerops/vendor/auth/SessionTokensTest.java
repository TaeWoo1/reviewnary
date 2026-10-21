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

    @Test
    void eachSessionHasItsOwnIdAndMillisecondIssueTimeAndATokenWithoutThemIsNotASession() {
        MutableClock clock = Clock.at();
        clock.advance(Duration.ofMillis(123));
        SessionTokens tokens = new SessionTokens(SECRET, clock);
        UUID account = UUID.randomUUID();
        SessionTokens.Session one = tokens.verifyAny(tokens.issue(account).token()).orElseThrow();
        SessionTokens.Session two = tokens.verifyAny(tokens.issue(account).token()).orElseThrow();
        assertThat(one.id()).isNotEqualTo(two.id()).hasSizeLessThanOrEqualTo(SessionTokens.MAX_ID_LENGTH);
        assertThat(one.issuedAt()).isEqualTo(clock.instant());
        assertThat(one.expiresAt()).isEqualTo(clock.instant().plus(SessionTokens.LIFETIME).truncatedTo(
                java.time.temporal.ChronoUnit.SECONDS));

        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date expiry = Date.from(clock.instant().plusSeconds(60));
        String noId = Jwts.builder().subject(account.toString()).claim("typ", "seller")
                .claim("iat_ms", clock.instant().toEpochMilli()).expiration(expiry).signWith(key).compact();
        String noTime = Jwts.builder().id("x").subject(account.toString()).claim("typ", "seller")
                .expiration(expiry).signWith(key).compact();
        assertThat(tokens.verifyAny(noId)).as("no id: it could never be logged out").isEmpty();
        assertThat(tokens.verifyAny(noTime)).as("no issue time: it could never be ended").isEmpty();
    }

    private static final class Clock {
        static MutableClock at() {
            return new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        }
    }
}
