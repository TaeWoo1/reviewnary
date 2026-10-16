package com.sellerops.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Only the seller's own PC may talk to a local-seller backend</b> — proposal §18.4 test 2.
 *
 * <p>The refusals are the point (DNS rebinding arrives with a hostile {@code Host}; a cross-site page names
 * itself in {@code Origin}), and the admissions are proven alongside them so a guard that refused
 * everything could not pass.
 */
class LoopbackRequestGuardTest {

    @Test
    void loopbackHostsWithAnyPortAreAdmitted() {
        for (String host : List.of("localhost", "localhost:8080", "127.0.0.1:8080", "[::1]:8080", "LOCALHOST:5173")) {
            assertThat(LoopbackRequestGuard.admits(host, null)).as(host).isTrue();
        }
    }

    @Test
    void aRebindingHostIsRefused() {
        for (String host : List.of("evil.example.com", "evil.example.com:8080", "127.0.0.1.evil.example.com:8080",
                "localhost.evil.example.com", "192.168.0.10:8080", "0.0.0.0:8080", "", "user@localhost:8080",
                "localhost/../x")) {
            assertThat(LoopbackRequestGuard.admits(host, null)).as(host).isFalse();
        }
        assertThat(LoopbackRequestGuard.admits(null, null)).isFalse();
    }

    @Test
    void aLoopbackOriginIsAdmittedAndAnyOtherOriginIsRefused() {
        assertThat(LoopbackRequestGuard.admits("127.0.0.1:8080", "http://localhost:5173")).isTrue();
        assertThat(LoopbackRequestGuard.admits("127.0.0.1:8080", "http://127.0.0.1:5173")).isTrue();
        for (String origin : List.of("https://evil.example.com", "http://localhost.evil.example.com",
                "null", "file://", "chrome-extension://abc", "")) {
            assertThat(LoopbackRequestGuard.admits("127.0.0.1:8080", origin)).as(origin).isFalse();
        }
    }
}
