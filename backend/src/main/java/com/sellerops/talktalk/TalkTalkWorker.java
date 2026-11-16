package com.sellerops.talktalk;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the lane's background work on one thread: process accepted events, dispatch queued sends, sweep SENDING rows
 * that never came back to UNKNOWN, and purge text past the retention period. Woken right after a commit that made
 * work, and on a fixed tick as the fallback. {@code sellerops.talktalk.worker-enabled=false} turns it off (tests drive
 * the processor and dispatcher themselves).
 */
@Component
@EnableScheduling
public class TalkTalkWorker {

    private static final Logger log = LoggerFactory.getLogger(TalkTalkWorker.class);

    private final TalkTalkEventProcessor processor;
    private final TalkTalkDispatcher dispatcher;
    private final TalkTalkEventRepository events;
    private final TalkTalkMessageRepository messages;
    private final TalkTalkDraftRepository drafts;
    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkSettings settings;
    private final TalkTalkClock clock;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "talktalk-worker");
        t.setDaemon(true);
        return t;
    });
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Instant lastPurge = Instant.EPOCH;

    public TalkTalkWorker(TalkTalkEventProcessor processor, TalkTalkDispatcher dispatcher,
                          TalkTalkEventRepository events, TalkTalkMessageRepository messages,
                          TalkTalkDraftRepository drafts, TalkTalkOutboundRepository outbound,
                          TalkTalkSettings settings, TalkTalkClock clock, PlatformTransactionManager txManager,
                          @Value("${sellerops.talktalk.worker-enabled:true}") boolean enabled) {
        this.processor = processor;
        this.dispatcher = dispatcher;
        this.events = events;
        this.messages = messages;
        this.drafts = drafts;
        this.outbound = outbound;
        this.settings = settings;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWork(TalkTalkWorkSignal signal) {
        if (enabled) {
            executor.submit(this::runOnce);
        }
    }

    @Scheduled(initialDelayString = "${sellerops.talktalk.initial-delay-ms:15000}",
            fixedDelayString = "${sellerops.talktalk.tick-ms:15000}")
    public void tick() {
        if (enabled) {
            executor.submit(this::runOnce);
        }
    }

    void runOnce() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            for (int round = 0; round < 5; round++) {
                int processed = processor.processPending(50);
                int sent = dispatcher.dispatchPending(20);
                if (processed == 0 && sent == 0) {
                    break;
                }
            }
            purgeIfDue();
        } catch (RuntimeException e) {
            log.warn("talktalk_worker_failed error={}", e.getClass().getSimpleName());
        } finally {
            lock.unlock();
        }
    }

    /** Text older than the retention period is removed; rows, states and fingerprints stay (lane contract §2). */
    int purgeIfDue() {
        Instant now = clock.now();
        if (lastPurge.isAfter(now.minus(Duration.ofHours(1)))) {
            return 0;
        }
        lastPurge = now;
        Instant before = now.minus(Duration.ofDays(settings.retentionDays()));
        Integer n = tx.execute(s -> events.purgeBefore(before, now) + messages.purgeBefore(before, now)
                + drafts.purgeBefore(before, now) + outbound.purgeBefore(before, now));
        if (n != null && n > 0) {
            log.info("talktalk_retention_purged rows={}", n);
        }
        return n == null ? 0 : n;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
