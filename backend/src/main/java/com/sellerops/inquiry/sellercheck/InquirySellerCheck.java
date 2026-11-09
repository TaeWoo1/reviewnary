package com.sellerops.inquiry.sellercheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * What the seller found when they checked this inquiry's order on the marketplace (V173, 2026-11-02 seller-check review).
 *
 * <p>A ground for THIS inquiry's reply and nothing else: never company knowledge, never an order binding, never a claim
 * the channel confirmed it ({@link #confirmedBy} says who did). A correction is a new row; this one is closed with
 * {@link #supersededAt} and kept. {@code length} is pinned on every string because tests build the schema from these
 * annotations while production runs V173.
 */
@Getter
@Setter
@Entity
@Table(name = "inquiry_seller_check")
public class InquirySellerCheck {

    /** Who confirmed what the record says. Only the seller today; the channel once an official read can. */
    public enum ConfirmedBy { SELLER, CHANNEL }

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "work_item_id", nullable = false)
    private UUID workItemId;

    @Column(name = "outcome", nullable = false, length = 24)
    private String outcome;

    @Column(name = "note", nullable = false, length = 300)
    private String note;

    @Column(name = "confirmed_by", nullable = false, length = 16)
    private String confirmedBy;

    @Column(name = "recorded_by", nullable = false, length = 120)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    /**
     * When the seller will look again at an action still in progress (V176) — their own task, never a promise to the
     * customer, and never part of the evidence a draft is written from. Set only on an {@code IN_PROGRESS} record.
     */
    @Column(name = "next_check_on")
    private LocalDate nextCheckOn;

    public SellerCheckOutcome outcomeValue() {
        return SellerCheckOutcome.valueOf(outcome);
    }
}
