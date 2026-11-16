package com.sellerops.intakealert;

import com.sellerops.ingest.NewWorkIngestedEvent;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs {@link IntakeAlertEngine} on two triggers.
 *
 * <ul>
 *   <li><b>The scheduled pass</b> ({@code sellerops.intake-alert.tick-ms}, 30 s) — the guarantee. It reads the source
 *       tables, so it finds rows whose ingest event was never delivered and resumes after a restart.</li>
 *   <li><b>The ingest event</b> ({@link NewWorkIngestedEvent}) — only a shortcut so a new review is told within
 *       seconds. It is published best-effort after the ingest committed and its publish failure is swallowed
 *       ({@code IngestFollowUp}); nothing here depends on it arriving.</li>
 * </ul>
 * Both run on one background thread under one lock, never on the ingest's thread or transaction.
 */
@Component
public class IntakeAlertWorker {

    private static final Logger log = LoggerFactory.getLogger(IntakeAlertWorker.class);

    private final IntakeAlertEngine engine;
    private final boolean enabled;
    private final ReentrantLock lock = new ReentrantLock();
    private final Executor executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "intake-alert");
        t.setDaemon(true);
        return t;
    });

    public IntakeAlertWorker(IntakeAlertEngine engine,
                             @Value("${sellerops.intake-alert.worker-enabled:true}") boolean enabled) {
        this.engine = engine;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${sellerops.intake-alert.initial-delay-ms:20000}",
            fixedDelayString = "${sellerops.intake-alert.tick-ms:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        guarded(engine::runAll);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNewWork(NewWorkIngestedEvent event) {
        if (!enabled) {
            return;
        }
        try {
            executor.execute(() -> guarded(() -> engine.runOrg(event.orgId())));
        } catch (RuntimeException e) {
            log.warn("intake alert: 빠른 실행 예약 실패 — 정기 확인이 찾아요 org={}", event.orgId());
        }
    }

    private void guarded(Runnable pass) {
        lock.lock();
        try {
            pass.run();
        } catch (RuntimeException e) {
            log.warn("intake alert: pass failed {}", e.getClass().getSimpleName());
        } finally {
            lock.unlock();
        }
    }
}
