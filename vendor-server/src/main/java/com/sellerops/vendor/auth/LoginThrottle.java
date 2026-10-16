package com.sellerops.vendor.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Failed logins per e-mail address: after {@link #MAX_FAILURES} inside {@link #WINDOW}, further attempts for that
 * address are refused until the oldest failure ages out. In memory — one server process (contract §9); a restart
 * forgets, which costs an attacker a restart they cannot trigger.
 */
@Component
public class LoginThrottle {

    public static final int MAX_FAILURES = 10;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    /** Bounds the map itself; beyond this many tracked addresses the oldest entries are dropped. */
    static final int MAX_TRACKED = 10_000;

    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    @Autowired
    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean allowed(String email) {
        Deque<Instant> recent = failures.get(email);
        if (recent == null) {
            return true;
        }
        prune(recent, clock.instant());
        if (recent.isEmpty()) {
            failures.remove(email);
            return true;
        }
        return recent.size() < MAX_FAILURES;
    }

    public synchronized void failed(String email) {
        if (failures.size() >= MAX_TRACKED && !failures.containsKey(email)) {
            failures.keySet().stream().findFirst().ifPresent(failures::remove);
        }
        Deque<Instant> recent = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        prune(recent, clock.instant());
        recent.addLast(clock.instant());
    }

    public synchronized void succeeded(String email) {
        failures.remove(email);
    }

    private static void prune(Deque<Instant> recent, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.removeFirst();
        }
    }
}
