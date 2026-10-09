package com.sellerops.autocheck;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>「새 리뷰를 자동으로 확인합니다」 — one seller's setting for one of their stores.</b>
 *
 * <p>A <b>product setting</b>, not a grant. The difference is not wording: a grant is something a seller is
 * asked for, that expires, that a device holds, and that has to be asked for again. This is a switch on their
 * own account that is on because they connected the channel, and off when they turn it off. Nothing else turns
 * it off — not a helper token reaching its 180th day, not a re-install, not a new Mac, not a backend restart.
 *
 * <p><b>What it is not scoped by, deliberately.</b> No device: a desk is how the reading happens, resolved at
 * dispatch time, and a setting pinned to one would have cost the seller their setting every time their helper
 * was re-paired ({@code HelperDeviceService#redeem} mints a new row each time). No channel: the account already
 * knows its channel. No period, no cadence the seller picks, no consent record beyond who switched it on and
 * when — a product that asked a seller to choose an interval would be asking them to own a decision they have
 * no way to make.
 *
 * <p><b>READ_ONLY is an invariant, not a column anyone sets.</b> Every published recipe reads and closes; there
 * is no WRITE recipe and a structural test refuses one. The field exists so the row states the fact it is
 * relying on, and so a future write recipe cannot reach this lane by being added to an enum.
 */
@Getter
@Setter
@Entity
@Table(name = "review_auto_check", uniqueConstraints = @UniqueConstraint(
        name = "uq_review_auto_check_account_data_type", columnNames = {"seller_account_id", "data_type"}))
public class ReviewAutoCheck extends BaseEntity {

    /** The cadence, in minutes. 60 — not a seller-facing choice, and not per-account. */
    public static final int INTERVAL_MINUTES = 60;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "seller_account_id", nullable = false)
    private UUID sellerAccountId;

    /** REVIEW today. The column exists because the lane is written per data type and the row says which. */
    @Column(name = "data_type", nullable = false, length = 16)
    private String dataType;

    /** READ_ONLY, always. Stored so the row carries the invariant it depends on. */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode = "READ_ONLY";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Who switched it on — the connecting seller, for a row that is on by default. */
    @Column(name = "consented_by")
    private UUID consentedBy;

    @Column(name = "consented_at")
    private Instant consentedAt;

    /**
     * When the seller turned it off.
     *
     * <p>A tombstone as much as a timestamp: the row stays, and nothing recreates it as enabled. Without it, the
     * reconciler's «create a row for every eligible account» would switch the setting back on by itself on the
     * next tick, which is the one thing a seller turning something off must never see.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "interval_minutes", nullable = false)
    private int intervalMinutes = INTERVAL_MINUTES;

    /** When this account is next due. Advanced provisionally at claim, so two ticks cannot take one row. */
    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;

    /**
     * Why the automatic check is not getting anywhere right now — never why it is not allowed.
     *
     * <p>{@link AutoCheckPause#PAUSED_DEVICE} clears itself on the next tick that finds a helper;
     * {@link AutoCheckPause#PAUSED_AUTH} clears when the seller signs in and the walk continues. Both leave
     * {@link #enabled} exactly as it was, because neither is the seller changing their mind.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "paused_reason", length = 24)
    private AutoCheckPause pausedReason;

    /** Whether the seller currently wants this. The only question that is about permission. */
    public boolean on() {
        return enabled && revokedAt == null;
    }
}
