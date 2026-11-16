package com.sellerops.intakealert;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One message to one recipient and what the sender said about it.
 *
 * <p><b>Statuses never overstate.</b> {@code MOCK_RECORDED} is a message written into the mock inbox — nothing left this
 * server. {@code ACCEPTED} is the provider accepting the request — not the phone receiving it. {@code UNKNOWN} is a
 * request whose result we could not read (timeout, a crash mid-send): it is never retried automatically, because without
 * a provider idempotency guarantee a retry can send twice. {@code BLOCKED} is a live send the configuration does not
 * allow.
 */
@Getter
@Setter
@Entity
@Table(name = "intake_alert_deliveries")
public class IntakeAlertDelivery extends BaseEntity {

    /** SINGLE — one review or inquiry (the default); DIGEST — several, only when the seller chose bundling or a window. */
    public enum Kind { SINGLE, DIGEST }

    public enum Mode { MOCK, LIVE, BLOCKED }

    /** CANCELLED — closed before sending because the settings changed ({@code failureCode} names the reason). */
    public enum Status { PREPARED, SENDING, MOCK_RECORDED, ACCEPTED, FAILED, UNKNOWN, BLOCKED, CANCELLED }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "setting_id", nullable = false)
    private UUID settingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(name = "setting_generation", nullable = false)
    private int settingGeneration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "inquiry_count", nullable = false)
    private int inquiryCount;

    @Column(name = "urgent_count", nullable = false)
    private int urgentCount;

    /** The rendered template text — counts, the store name and fixed sentences only; never customer text. */
    @Column(name = "message_text", nullable = false, length = 1000)
    private String messageText;

    /** The in-service path the button opens ({@code /notice/<id>}); the host is added at send time. */
    @Column(name = "link_path", nullable = false, length = 200)
    private String linkPath;

    @Column(name = "provider_message_id", length = 100)
    private String providerMessageId;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
