package com.sellerops.vendor.retention;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * <b>When {@link Retention} runs</b> — contract §6: every 15 minutes, and once before the web server opens, so a
 * restored backup is cleaned before anyone can read it. An operator command process ({@code OpsCommands}) does not
 * run it on start; it has the {@code purge} command for that.
 *
 * <p>{@code vendor.retention.enabled=false} turns both off — the tests, whose clocks are their own, do.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "vendor.retention.enabled", havingValue = "true", matchIfMissing = true)
public class PurgeSchedule implements SmartInitializingSingleton {

    static final long EVERY_MS = 15 * 60 * 1000L;

    private final Retention retention;
    private final ApplicationContext context;

    public PurgeSchedule(Retention retention, ApplicationContext context) {
        this.retention = retention;
        this.context = context;
    }

    /** Runs after every bean exists and before the web server starts taking requests. */
    @Override
    public void afterSingletonsInstantiated() {
        if (context instanceof WebServerApplicationContext) {
            retention.purge();
        }
    }

    @Scheduled(initialDelay = EVERY_MS, fixedDelay = EVERY_MS)
    void every15Minutes() {
        retention.purge();
    }
}
