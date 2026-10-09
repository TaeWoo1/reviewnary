package com.sellerops.autocheck;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wall-clock entry point for {@link ReviewAutoCheckReconciler}. Deliberately thin, like {@code SyncScheduler}:
 * every decision is re-derived from the database each tick, so restarting the backend loses nothing and moves
 * no cadence.
 *
 * <p><b>Off by default.</b> The bean exists only when {@code sellerops.review-auto-check.enabled=true}. The
 * poll interval is how often the runtime looks; the cadence is the account's own
 * {@code interval_minutes} (60), and the two are not the same number.
 *
 * <p>Separate from the Self-Pilot and Responsibility schedulers on purpose. Those two are deployment postures —
 * an operator deciding what runs unattended for which organisations. This one is a seller's product setting,
 * and the only thing a deployment decides about it is whether the lane runs here at all.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sellerops.review-auto-check.enabled", havingValue = "true")
public class ReviewAutoCheckScheduler {

    private final ReviewAutoCheckReconciler reconciler;

    public ReviewAutoCheckScheduler(ReviewAutoCheckReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${sellerops.review-auto-check.poll-interval-ms:300000}",
            initialDelayString = "${sellerops.review-auto-check.initial-delay-ms:20000}")
    public void tick() {
        reconciler.tick(Instant.now());
    }
}
