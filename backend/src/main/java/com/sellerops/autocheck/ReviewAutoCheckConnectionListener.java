package com.sellerops.autocheck;

import com.sellerops.selleraccount.SellerAccountConnectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <b>연결을 마치면 자동 확인이 켜져 있다 — 그 한 줄이 여기에서 참이 된다.</b>
 *
 * <p>Connecting a channel IS the instruction to keep it current, so the setting is created with the connection
 * rather than by anything asking later. One listener, one call, and it is the only place a row appears without a
 * person looking at a screen.
 *
 * <p><b>Why not a background sweep.</b> The first version had the scheduler adopt any connected account it
 * found. That made a loop the thing that decides whose stores get read — and on a backend that holds more than
 * one organisation it picked up every one of them, including a benchmark fixture org (measured, 2026-10-09).
 * The only way back from that would have been a list of organisations for product code to skip, which is not a
 * thing this product may contain. A connection is an act inside one organisation; so is this.
 *
 * <p>Accounts connected before this feature existed are not this listener's business — they are switched on by
 * their own organisation asking, once ({@link ReviewAutoCheckService#backfillForOrg}).
 *
 * <p>Never fails the connection. A seller whose channel just went green must not see an error because something
 * downstream of that fact could not be written; the setting is reachable from their own settings screen, which
 * creates it if it is missing.
 */
@Component
public class ReviewAutoCheckConnectionListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewAutoCheckConnectionListener.class);

    private final ReviewAutoCheckService settings;

    public ReviewAutoCheckConnectionListener(ReviewAutoCheckService settings) {
        this.settings = settings;
    }

    @EventListener
    public void onConnected(SellerAccountConnectedEvent event) {
        try {
            settings.ensure(event.orgId(), event.sellerAccountId())
                    .ifPresent(row -> log.info("review auto-check: on by default for a newly connected account"));
        } catch (RuntimeException e) {
            log.warn("review auto-check: could not switch on for a newly connected account type={}",
                    e.getClass().getSimpleName());
        }
    }
}
