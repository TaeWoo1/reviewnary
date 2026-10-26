package com.sellerops.vendor.review;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * <b>PCs waiting to hear that a store has new reviews</b> — contract §3.2 (owner decision 2026-10-26: an upload, not a
 * timer, is what brings reviews to the PC).
 *
 * <p>The server cannot reach a seller's PC; the PC asks ({@code POST /v1/reviews/wait}) and the question is held here
 * for up to {@link #HOLD}. An upload that wrote events wakes every question naming its store, and each re-reads the
 * feed position for itself, so a wake is only ever a reason to look, never the answer.
 *
 * <p>In this process's memory only: a second server process would not wake a question held by the first, and that
 * question would then end empty at {@link #HOLD} — the PC asks again, and its timed pull still runs. Slower, never
 * wrong.
 */
@Component
public class ReviewArrivals {

    /** How long a question is held: under the 30 s idle limit common to proxies; the PC waits longer for it. */
    public static final Duration HOLD = Duration.ofSeconds(25);

    /** One held question. {@link #wake} may run on any thread, any number of times. */
    public interface Waiter {
        void wake();
    }

    private final Map<UUID, Set<Waiter>> waiting = new ConcurrentHashMap<>();

    public void listen(Collection<UUID> storeIds, Waiter waiter) {
        for (UUID store : storeIds) {
            waiting.compute(store, (k, set) -> {
                Set<Waiter> s = set == null ? ConcurrentHashMap.newKeySet() : set;
                s.add(waiter);
                return s;
            });
        }
    }

    public void forget(Collection<UUID> storeIds, Waiter waiter) {
        for (UUID store : storeIds) {
            waiting.computeIfPresent(store, (k, set) -> {
                set.remove(waiter);
                return set.isEmpty() ? null : set;
            });
        }
    }

    /** New events for this store are committed: every question naming it looks again. */
    public void arrived(UUID storeId) {
        Set<Waiter> set = waiting.get(storeId);
        if (set != null) {
            for (Waiter waiter : List.copyOf(set)) {
                waiter.wake();
            }
        }
    }

    /** Questions held for this store now — for tests. */
    int waitingFor(UUID storeId) {
        Set<Waiter> set = waiting.get(storeId);
        return set == null ? 0 : set.size();
    }
}
