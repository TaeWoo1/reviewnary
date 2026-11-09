package com.sellerops.replyhold;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sweeps every org's waiting drafts when what they were written from has changed ({@link ReplyHoldService#sweep}).
 *
 * <p><b>On unless turned off</b> ({@code sellerops.reply-hold.sweep-enabled}), unlike the schedulers that reach a
 * channel or a model: this one reads this server's own rows and calls nothing. It catches every writer of knowledge,
 * policy and wording — the settings screens, the workbook, document import, candidate acceptance — because it compares
 * the basis rather than listening to each. The approval and registration gates do not wait for it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sellerops.reply-hold.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class ReplyHoldSweepScheduler {

    private final ReplyHoldService holds;

    public ReplyHoldSweepScheduler(ReplyHoldService holds) {
        this.holds = holds;
    }

    @Scheduled(fixedDelayString = "${sellerops.reply-hold.sweep-interval-ms:300000}",
            initialDelayString = "${sellerops.reply-hold.sweep-initial-delay-ms:60000}")
    public void tick() {
        holds.sweepAll();
    }
}
