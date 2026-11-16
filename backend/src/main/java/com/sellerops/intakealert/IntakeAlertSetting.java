package com.sellerops.intakealert;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One seller user's choice to be told about new reviews and inquiries in one org (unique per org + user).
 *
 * <p>{@code watchFrom} is the durable watermark: a review or inquiry that arrived in SellerOps before it is never alerted,
 * so turning alerts on does not flood the seller with a year of history. It is reset to "now" every time the setting
 * goes from off to on. The phone number is sealed with the vault key ring ({@code phoneSealed}); only
 * {@code phoneLast4} is ever shown.
 */
@Getter
@Setter
@Entity
@Table(name = "intake_alert_settings", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uq_intake_alert_settings_user", columnNames = {"org_id", "user_id"}))
public class IntakeAlertSetting extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "notify_reviews", nullable = false)
    private boolean notifyReviews = true;

    @Column(name = "notify_inquiries", nullable = false)
    private boolean notifyInquiries = true;

    /**
     * EACH (default) — one message per new review/inquiry, at once (owner, 2026-11-16 night: 「리뷰/문의가 올 때마다」).
     * DIGEST — opt-in: new items gathered and told at most once per {@link #digestMinutes}.
     */
    public enum DeliveryMode { EACH, DIGEST }

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 16)
    private DeliveryMode deliveryMode = DeliveryMode.EACH;

    @Column(name = "digest_minutes", nullable = false)
    private int digestMinutes = 60;

    /**
     * Opt-in receiving window (off by default — no time limit). When on, ordinary items found outside the window wait and
     * are told once, together, when it opens (the earlier Q71/Q72 shape); a safety report is still sent at once.
     */
    @Column(name = "quiet_hours_enabled", nullable = false)
    private boolean quietHoursEnabled;

    /** Mon=1, Tue=2, … Sun=64; read only while {@link #quietHoursEnabled}. */
    @Column(name = "days_mask", nullable = false)
    private int daysMask = 127;

    /** Minutes after local midnight, Asia/Seoul; read only while {@link #quietHoursEnabled}. */
    @Column(name = "start_minute", nullable = false)
    private int startMinute = 540;

    @Column(name = "end_minute", nullable = false)
    private int endMinute = 1080;

    @Column(name = "watch_from")
    private Instant watchFrom;

    /** When reviews (inquiries) were last turned on: a kind turned back on is never told what arrived while it was off. */
    @Column(name = "reviews_from")
    private Instant reviewsFrom;

    @Column(name = "inquiries_from")
    private Instant inquiriesFrom;

    /** The effective start point for one kind: the later of {@link #watchFrom} and that kind's own. */
    public Instant fromFor(IntakeAlertItem.Kind kind) {
        Instant k = kind == IntakeAlertItem.Kind.REVIEW ? reviewsFrom : inquiriesFrom;
        if (watchFrom == null) {
            return null;
        }
        return k != null && k.isAfter(watchFrom) ? k : watchFrom;
    }

    /**
     * Bumped whenever the recipient or the permission changes — turned off or on, the number changed, consent withdrawn.
     * An item or a delivery carries the generation it was made under and is never sent under another.
     */
    @Column(nullable = false)
    private int generation;

    @Column(name = "last_digest_at")
    private Instant lastDigestAt;

    @Column(name = "phone_last4", length = 4)
    private String phoneLast4;

    @Column(name = "phone_sealed")
    private byte[] phoneSealed;

    @Column(name = "phone_key_id", length = 80)
    private String phoneKeyId;

    @Column(name = "phone_consent_at")
    private Instant phoneConsentAt;
}
