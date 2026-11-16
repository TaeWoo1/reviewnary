package com.sellerops.talktalk;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One version of the organization's holiday and dispatch cut-off guidance (lane contract §6-2). A CONFIRMED version
 * is never edited: a change is a new version, and confirming it retires the old one — which also leaves any
 * delegation bound to the old one pointing at a retired version, i.e. off.
 */
@Getter
@Setter
@Entity
@Table(name = "talktalk_shipping_guidance")
public class TalkTalkGuidance extends BaseEntity {

    public enum Status { DRAFT, CONFIRMED, RETIRED }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    /** Orders paid before this hour (KST) leave the same dispatch day; null means the seller has not said. */
    @Column(name = "cutoff_hour")
    private Integer cutoffHour;

    @Column(name = "cutoff_minute", nullable = false)
    private int cutoffMinute;

    /** Dispatch weekdays, e.g. {@code MON,TUE,WED,THU,FRI}. */
    @Column(name = "dispatch_days", nullable = false, length = 40)
    private String dispatchDays;

    /** Closed dates as {@code yyyy-MM-dd|note} lines — 공휴일 included, because the product does not know them. */
    @Column(name = "closed_dates", nullable = false, columnDefinition = "text")
    private String closedDates;

    /** The seller says the closed-date list is complete through this day; a dispatch day after it is not a fact. */
    @Column(name = "confirmed_through")
    private LocalDate confirmedThrough;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "retired_at")
    private Instant retiredAt;
}
