package com.sellerops.opportunity;

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
 * The seller's decision about one derived opportunity, plus the draft that decision prepared.
 *
 * <p><b>Annotation, not authority.</b> The opportunity is re-derived from the issue on every read
 * ({@link OpportunityRules}); this row only remembers what the seller said about it. When the issue
 * stops qualifying, this row is simply never joined again.
 */
@Getter
@Setter
@Entity
@Table(name = "improvement_opportunity")
public class ImprovementOpportunity extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OpportunityKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OpportunityStatus status;

    @Column(name = "draft_title", length = 200)
    private String draftTitle;

    @Column(name = "draft_body", columnDefinition = "text")
    private String draftBody;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /**
     * When the seller carried it out. Set iff {@link #status} is {@link OpportunityStatus#APPLIED} — V131's
     * {@code ck_improvement_opportunity_applied} makes the two inseparable, so a claim with no moment and a
     * leftover moment under a withdrawn decision are both impossible.
     */
    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "applied_by")
    private UUID appliedBy;

    /**
     * The row the applied text landed in, when this product can name one — the org rule or the product source
     * the seller saved. Null when the seller carried the draft away themselves.
     *
     * <p>No foreign key, deliberately: the memory of what was applied has to outlive the thing being retired,
     * the same reason {@code answer_memory.origin_review_id} carries none.
     */
    @Column(name = "applied_ref")
    private UUID appliedRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "applied_ref_kind", length = 24)
    private AppliedArtifact appliedRefKind;
}
