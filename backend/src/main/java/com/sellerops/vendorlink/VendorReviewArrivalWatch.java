package com.sellerops.vendorlink;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <b>An upload brings the reviews, not a timer</b> (owner decision 2026-10-26; contract §3.2).
 *
 * <p>For each organisation with its own registration, one thread keeps a question open with the vendor server —
 * "anything after these cursors?" — asked with that organisation's token and naming only its stores, and the server
 * answers it the moment a delegate's upload to one of them commits. Then that organisation's ordinary pull runs
 * ({@link VendorReviewPullService#pullBound(UUID)}), and the next question goes out at once, so a backlog bigger than
 * one pull drains without waiting either. Nothing came within the hold: ask again. A supervisor starts a thread when an
 * organisation links and the thread ends when it unlinks. On a seller PC that is one thread, as before; a shared server
 * with many organisations needs a different arrival signal ({@code docs/standing/server_operation_boundary_v1.md} §1).
 *
 * <p>The timed pull ({@link VendorReviewPullScheduler}) stays as it was. It is what still follows reply tasks, and what
 * carries on when the server cannot hold a question — an older server, a proxy that cuts it, no network — because here
 * every failure only rests {@link #REST} and asks again.
 */
@Component
@ConditionalOnProperty(name = "sellerops.vendor-link.review-pull.enabled", havingValue = "true")
public class VendorReviewArrivalWatch {

    private static final Logger log = LoggerFactory.getLogger(VendorReviewArrivalWatch.class);

    /** An empty answer sooner than this was not a held question; asking again at once would only spin. */
    static final Duration QUICK = Duration.ofSeconds(5);
    /** The rest after anything that is not a held question answered or run out. */
    static final Duration REST = Duration.ofSeconds(30);

    private final VendorReviewPullService pulls;
    private final Clock clock;
    private final Map<UUID, Thread> watchers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread supervisor;

    @Autowired
    public VendorReviewArrivalWatch(VendorReviewPullService pulls) {
        this(pulls, Clock.systemUTC());
    }

    VendorReviewArrivalWatch(VendorReviewPullService pulls, Clock clock) {
        this.pulls = pulls;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        supervisor = new Thread(this::supervise, "vendor-review-wait-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (supervisor != null) {
            supervisor.interrupt();
        }
        watchers.values().forEach(Thread::interrupt);
    }

    private void supervise() {
        while (running) {
            try {
                ensureWatchers();
            } catch (RuntimeException e) {
                log.warn("vendor review wait supervisor: {}", e.getClass().getSimpleName());
            }
            if (!rest(REST)) {
                return;
            }
        }
    }

    /** A watcher for each linked organisation that has none. Returns the organisations it started one for. */
    List<UUID> ensureWatchers() {
        List<UUID> started = new java.util.ArrayList<>();
        for (UUID orgId : pulls.linkedOrganisations()) {
            watchers.computeIfAbsent(orgId, id -> {
                Thread t = new Thread(() -> watch(id), "vendor-review-wait");
                t.setDaemon(true);
                t.start();
                started.add(id);
                return t;
            });
        }
        return started;
    }

    private void watch(UUID orgId) {
        try {
            while (running && pulls.linkedOrganisations().contains(orgId)) {
                Duration rest;
                try {
                    rest = step(orgId);
                } catch (RuntimeException e) {
                    log.warn("vendor review wait: {}", e.getClass().getSimpleName());
                    rest = REST;
                }
                if (!rest.isZero() && !rest(rest)) {
                    return;
                }
            }
        } finally {
            watchers.remove(orgId);
        }
    }

    private static boolean rest(Duration rest) {
        try {
            Thread.sleep(rest.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * One question for one organisation and what follows it. Returns how long to rest before the next; zero is "ask
     * again now".
     */
    Duration step(UUID orgId) {
        Map<UUID, Long> before = pulls.receivingCursors(orgId);
        if (before.isEmpty()) {
            return REST;
        }
        Instant asked = clock.instant();
        List<UUID> ready;
        try {
            ready = pulls.waitForNews(orgId, before);
        } catch (VendorServerClient.Unreachable | VendorServerClient.Refused e) {
            return REST;
        }
        if (ready.isEmpty()) {
            return Duration.between(asked, clock.instant()).compareTo(QUICK) < 0 ? REST : Duration.ZERO;
        }
        pulls.pullBound(orgId);
        // A pull that moved no cursor — an event it could not store, the server gone mid-pull — would otherwise turn
        // into a run of instant answers: the server keeps saying "ready" for events this backend is not taking.
        return before.equals(pulls.receivingCursors(orgId)) ? REST : Duration.ZERO;
    }
}
