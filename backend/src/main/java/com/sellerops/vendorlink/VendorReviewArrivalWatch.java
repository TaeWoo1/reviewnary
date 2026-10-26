package com.sellerops.vendorlink;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * <p>One thread keeps a question open with the vendor server — "anything after these cursors?" — and the server answers
 * it the moment a delegate's upload to one of this PC's stores commits. Then the ordinary pull runs
 * ({@link VendorReviewPullService#pullBound}), and the next question goes out at once, so a backlog bigger than one pull
 * drains without waiting either. Nothing came within the hold: ask again.
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
    private volatile boolean running;
    private Thread thread;

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
        thread = new Thread(this::run, "vendor-review-wait");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void run() {
        while (running) {
            Duration rest;
            try {
                rest = step();
            } catch (RuntimeException e) {
                log.warn("vendor review wait: {}", e.getClass().getSimpleName());
                rest = REST;
            }
            if (!rest.isZero()) {
                try {
                    Thread.sleep(rest.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One question and what follows it. Returns how long to rest before the next; zero is "ask again now". */
    Duration step() {
        Map<UUID, Long> before = pulls.receivingCursors();
        if (before.isEmpty()) {
            return REST;
        }
        Instant asked = clock.instant();
        List<UUID> ready;
        try {
            ready = pulls.waitForNews(before);
        } catch (VendorServerClient.Unreachable | VendorServerClient.Refused e) {
            return REST;
        }
        if (ready.isEmpty()) {
            return Duration.between(asked, clock.instant()).compareTo(QUICK) < 0 ? REST : Duration.ZERO;
        }
        pulls.pullBound();
        // A pull that moved no cursor — an event it could not store, the server gone mid-pull — would otherwise turn
        // into a run of instant answers: the server keeps saying "ready" for events this PC is not taking.
        return before.equals(pulls.receivingCursors()) ? REST : Duration.ZERO;
    }
}
