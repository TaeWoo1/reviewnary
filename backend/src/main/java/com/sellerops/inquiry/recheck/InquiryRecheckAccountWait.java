package com.sellerops.inquiry.recheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A seller account whose open-inquiry rechecks keep stopping on the account's side (V186, 2026-11-08): a 429, a refused
 * token or permission, no token, an outage. Until {@code wait_until} its recheck sends nothing. There only while the
 * failures go on; a run that sent requests and ended without an account-side stop deletes it. Written only through
 * {@link InquiryRecheckAccountWaitRepository}'s statements.
 */
@Entity
@Table(name = "inquiry_recheck_account_wait")
public class InquiryRecheckAccountWait {

    @Id
    @Column(name = "seller_account_id", nullable = false)
    private UUID sellerAccountId;

    @Column(name = "failures", nullable = false)
    private int failures;

    @Column(name = "last_stop", nullable = false, length = 32)
    private String lastStop;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    @Column(name = "wait_until", nullable = false)
    private Instant waitUntil;

    protected InquiryRecheckAccountWait() {
    }

    public UUID getSellerAccountId() {
        return sellerAccountId;
    }

    public int getFailures() {
        return failures;
    }

    public String getLastStop() {
        return lastStop;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public Instant getWaitUntil() {
        return waitUntil;
    }
}
