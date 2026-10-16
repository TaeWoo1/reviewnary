package com.sellerops.vendor.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    @Test
    void tenFailuresInFifteenMinutesStopTheAddressUntilTheOldestAgesOut() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.failed("a@example.com");
            clock.advance(Duration.ofSeconds(10));
        }
        assertThat(throttle.allowed("a@example.com")).isTrue();
        throttle.failed("a@example.com");
        assertThat(throttle.allowed("a@example.com")).isFalse();
        assertThat(throttle.allowed("b@example.com")).as("another address").isTrue();
        clock.advance(LoginThrottle.WINDOW.minus(Duration.ofSeconds(90)));
        assertThat(throttle.allowed("a@example.com")).isTrue();
    }

    @Test
    void aSuccessClearsTheCount() {
        LoginThrottle throttle = new LoginThrottle(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.failed("a@example.com");
        }
        throttle.succeeded("a@example.com");
        assertThat(throttle.allowed("a@example.com")).isTrue();
    }
}
