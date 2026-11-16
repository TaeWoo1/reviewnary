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
 * The outbox row: one original review or inquiry, for one recipient, in one org — unique on exactly that
 * ({@code uq_intake_alert_items_subject}), so a re-collection, a repeated event, a restart or two workers at once can
 * never make a second one. It carries no customer text: only the kind, the channel code and a closed reason.
 */
@Getter
@Setter
@Entity
@Table(name = "intake_alert_items", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uq_intake_alert_items_subject", columnNames = {"org_id", "setting_id", "subject_kind", "subject_id"}))
public class IntakeAlertItem extends BaseEntity {

    public enum Kind { REVIEW, INQUIRY }

    /**
     * PENDING → INCLUDED (in a delivery) · DROPPED_HANDLED (answered before it was told) · DROPPED_OLD (the customer
     * wrote it long before it arrived here) · CANCELLED (the settings changed under it — {@link #cancelReason}).
     */
    public enum Status { PENDING, INCLUDED, DROPPED_HANDLED, DROPPED_OLD, CANCELLED }

    /** Why a queued item was closed by a settings change. */
    public enum CancelReason { TURNED_OFF, KIND_OFF, NUMBER_CHANGED, CONSENT_WITHDRAWN, STALE_SETTINGS }

    /** Closed reasons; SAFETY is the only urgent one (owner Q38/Q39: a concrete safety report, even one). */
    public enum Reason { SAFETY, LOW_RATING, NEW_REVIEW, NEW_INQUIRY }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "setting_id", nullable = false)
    private UUID settingId;

    @Column(name = "subject_kind", nullable = false, length = 16)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Kind subjectKind;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "channel_code", length = 40)
    private String channelCode;

    @Column(nullable = false, length = 24)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Reason reason;

    /** The rule-based topic of the row ({@code item_analyses.category}: 배송·교환·품질…), or null before analysis. */
    @Column(name = "issue_label", length = 20)
    private String issueLabel;

    @Column(nullable = false)
    private boolean urgent;

    @Column(nullable = false, length = 24)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(name = "setting_generation", nullable = false)
    private int settingGeneration;

    @Column(name = "cancel_reason", length = 24)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private CancelReason cancelReason;

    @Column(name = "delivery_id")
    private UUID deliveryId;

    /** When the original row arrived in SellerOps (its created_at), not when the customer wrote it. */
    @Column(name = "source_ingested_at", nullable = false)
    private Instant sourceIngestedAt;
}
