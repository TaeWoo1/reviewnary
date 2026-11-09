package com.sellerops.inquiry.recheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * How many channel HTTP requests the open-inquiry recheck sent for one seller account on one Korean calendar day (V185,
 * 2026-11-08). Counted <b>before</b> each request goes out, by a conditional increment, so a failed request counts and the
 * day's limit is never passed. Written only through {@link InquiryRecheckDailyUsageRepository}'s statements.
 */
@Entity
@Table(name = "inquiry_recheck_daily_usage")
@IdClass(InquiryRecheckDailyUsage.Key.class)
public class InquiryRecheckDailyUsage {

    @Id
    @Column(name = "seller_account_id", nullable = false)
    private UUID sellerAccountId;

    @Id
    @Column(name = "usage_day", nullable = false)
    private LocalDate usageDay;

    @Column(name = "requests", nullable = false)
    private int requests;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InquiryRecheckDailyUsage() {
    }

    public UUID getSellerAccountId() {
        return sellerAccountId;
    }

    public LocalDate getUsageDay() {
        return usageDay;
    }

    public int getRequests() {
        return requests;
    }

    public static final class Key implements Serializable {
        private UUID sellerAccountId;
        private LocalDate usageDay;

        public Key() {
        }

        public Key(UUID sellerAccountId, LocalDate usageDay) {
            this.sellerAccountId = sellerAccountId;
            this.usageDay = usageDay;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(sellerAccountId, k.sellerAccountId)
                    && Objects.equals(usageDay, k.usageDay);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sellerAccountId, usageDay);
        }
    }
}
