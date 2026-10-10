package com.sellerops.opportunity;

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
 * One anchored measurement: what the reviews did in a named window after the seller applied one improvement.
 *
 * <p><b>The anchor is frozen and the database enforces it</b> (V131's trigger). {@code appliedOn}, both baseline
 * dates, both baseline counts and {@code observeDays} are the measurement's premise; a baseline edited after the
 * fact makes any number whatever the editor wanted and nothing downstream could tell.
 *
 * <p><b>Why {@code baselineReviews} is here beside the evidence.</b> It is the denominator, and without it a
 * later silence is unreadable: «0 complaints» and «0 reviews» are the same number. Every refusal in
 * {@link ImprovementOutcomeRules} is computed from this column.
 */
@Getter
@Setter
@Entity
@Table(name = "improvement_outcome")
public class ImprovementOutcome extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "opportunity_id", nullable = false)
    private UUID opportunityId;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    /**
     * Which population the evidence is counted in — the remediation's own reach.
     *
     * <p>Reuses {@code OpportunityRules.Scope} rather than declaring a third ORG/PRODUCT enum, so the scope a
     * suggestion was derived for and the scope its result is measured in are the same word.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OpportunityRules.Scope scope;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "applied_on", nullable = false)
    private LocalDate appliedOn;

    @Column(name = "baseline_from", nullable = false)
    private LocalDate baselineFrom;

    @Column(name = "baseline_to", nullable = false)
    private LocalDate baselineTo;

    @Column(name = "baseline_evidence", nullable = false)
    private int baselineEvidence;

    @Column(name = "baseline_reviews", nullable = false)
    private int baselineReviews;

    @Column(name = "observe_days", nullable = false)
    private int observeDays;

    @Column(name = "observed_evidence")
    private Integer observedEvidence;

    @Column(name = "observed_reviews")
    private Integer observedReviews;

    @Column(name = "observed_through")
    private LocalDate observedThrough;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutcomeVerdict verdict = OutcomeVerdict.OBSERVING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OutcomeReason reason = OutcomeReason.WINDOW_OPEN;

    /** Null while OBSERVING; set once, when the window closed and the verdict was read. */
    @Column(name = "evaluated_at")
    private Instant evaluatedAt;
}
