package com.sellerops.inquiry.recheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The one open-inquiry recheck run that holds a seller account now (V185, 2026-11-08). Taken at the start of a run,
 * renewed before each request, deleted at the end. A holder that died without deleting it loses it once
 * {@code lease_until} has passed. Written only through {@link InquiryRecheckRunLockRepository}'s statements.
 */
@Entity
@Table(name = "inquiry_recheck_run_lock")
public class InquiryRecheckRunLock {

    @Id
    @Column(name = "seller_account_id", nullable = false)
    private UUID sellerAccountId;

    @Column(name = "holder", nullable = false)
    private UUID holder;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "lease_until", nullable = false)
    private Instant leaseUntil;

    protected InquiryRecheckRunLock() {
    }

    public UUID getSellerAccountId() {
        return sellerAccountId;
    }

    public UUID getHolder() {
        return holder;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }
}
