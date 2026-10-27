package com.sellerops.review.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * What the seller said about one guidance for one review (2026-10-27): it is right (CONFIRMED — it had been attached by
 * the customer's words), they chose it themselves (PICKED), or it is not this review's (REJECTED). One row per review
 * and guidance; see {@code V150__review_guidance_choice.sql}.
 */
@Getter
@Setter
@Entity
@Table(name = "review_guidance_choice")
public class ReviewGuidanceChoice {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String PICKED = "PICKED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    /** {@code PRODUCT} — a product knowledge source; {@code ORG} — a company rule. */
    @Column(name = "source_scope", nullable = false, length = 16)
    private String sourceScope;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "choice", nullable = false, length = 16)
    private String choice;

    /** The customer phrase that had attached it, when it was attached that way — what the seller confirmed or refused. */
    @Column(name = "matched_phrase", length = 200)
    private String matchedPhrase;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_by_name", length = 120)
    private String decidedByName;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /** The seller stands behind it for this review. */
    public boolean chosen() {
        return CONFIRMED.equals(choice) || PICKED.equals(choice);
    }
}
