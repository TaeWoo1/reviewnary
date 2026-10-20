package com.sellerops.vendorlink;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wall-clock entry point for {@link VendorReviewPullService#pullBound()}. Thin, like {@code ReviewAutoCheckScheduler}:
 * the cursor is in the database, so a restart loses nothing.
 *
 * <p><b>Off by default</b>; the {@code local-seller} profile turns it on. It does nothing on a PC that is not linked
 * or whose seller has not pulled once (no binding), and it never binds a store itself.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sellerops.vendor-link.review-pull.enabled", havingValue = "true")
public class VendorReviewPullScheduler {

    private final VendorReviewPullService service;

    public VendorReviewPullScheduler(VendorReviewPullService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${sellerops.vendor-link.review-pull.poll-interval-ms:300000}",
            initialDelayString = "${sellerops.vendor-link.review-pull.initial-delay-ms:30000}")
    public void tick() {
        service.pullBound();
    }
}
