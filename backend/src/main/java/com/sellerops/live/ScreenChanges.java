package com.sellerops.live;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * <b>An open screen changes by itself when its data does</b> (owner decision 2026-10-27).
 *
 * <p>One number per organisation that moves whenever something the seller's screens show arrived from outside this
 * browser — the vendor relay's reviews, a delegate's post, a phone's approval. A screen keeps one question open
 * ({@code GET /api/live/changes?after=N}); the number moving answers it, and the screen reads again what it shows.
 * The number says only "look again": no review, no count, no id rides on it.
 *
 * <p>It starts at this process's start time, so a screen that last saw a number from before a restart is answered at
 * once and reads again — what the restarted PC holds may have changed while it was away. In this process's memory
 * only: losing it costs a screen one extra read, never a wrong one.
 */
@Component
public class ScreenChanges {

    /** How long a question is held: under the 30 s idle limit common to proxies; the screen waits longer for it. */
    public static final Duration HOLD = Duration.ofSeconds(25);

    private final long start = System.currentTimeMillis();
    private final Map<UUID, Long> versions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Runnable>> waiting = new ConcurrentHashMap<>();

    public long version(UUID orgId) {
        return versions.getOrDefault(orgId, start);
    }

    /** Something this organisation's screens show has changed: every question it holds looks again. */
    public void changed(UUID orgId) {
        versions.merge(orgId, start + 1, (current, one) -> current + 1);
        Set<Runnable> set = waiting.get(orgId);
        if (set != null) {
            for (Runnable waiter : List.copyOf(set)) {
                waiter.run();
            }
        }
    }

    public void listen(UUID orgId, Runnable waiter) {
        waiting.compute(orgId, (k, set) -> {
            Set<Runnable> s = set == null ? ConcurrentHashMap.newKeySet() : set;
            s.add(waiter);
            return s;
        });
    }

    public void forget(UUID orgId, Runnable waiter) {
        waiting.computeIfPresent(orgId, (k, set) -> {
            set.remove(waiter);
            return set.isEmpty() ? null : set;
        });
    }

    /** Questions held for this organisation now — for tests. */
    int waitingFor(UUID orgId) {
        Set<Runnable> set = waiting.get(orgId);
        return set == null ? 0 : set.size();
    }
}
