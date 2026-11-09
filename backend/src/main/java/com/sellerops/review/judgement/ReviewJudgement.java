package com.sellerops.review.judgement;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>The one judgement of a review</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md} §3-4) — what the customer
 * said (categories, the points a reply must cover) and what the seller should do (a proposal), read by an AI from the
 * review's words. The seller's own response rule and the review's actual state are not here: the program applies the
 * first and reads the second from its records, so a judgement can never claim an exchange was done.
 *
 * <p>One row per review, the latest. Written beside the five readers it is meant to replace, so the two can be
 * compared before any screen switches to it.
 */
@Getter
@Setter
@Entity
@Table(name = "review_judgement")
public class ReviewJudgement extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "judged_at", nullable = false)
    private Instant judgedAt;

    /** Which judge wrote it: REPLY_AGENT. */
    @Column(name = "source", nullable = false, length = 24)
    private String source;

    @Column(name = "model_version", length = 200)
    private String modelVersion;

    /** The first category of the reply standard; null when the judge could not tell or did not answer. */
    @Column(name = "category", length = 2)
    private String category;

    @Column(name = "also_categories", length = 40)
    private String alsoCategories;

    /** One point per line — model words over the redacted review, never the review. */
    @Column(name = "points", columnDefinition = "text")
    private String points;

    /**
     * What the judge proposes the seller do, comma-separated — more than one at once (an order to check AND a public
     * reply): PUBLIC_REPLY · CHECK_ORDER · ASK_MORE_INFO · PERSON_REVIEW · NO_RESPONSE.
     */
    @Column(name = "actions", length = 80)
    private String actions;

    /** The fingerprint of the redacted review the reading was made of, so an unchanged review is not read twice. */
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    /**
     * Whether the seller owes this review something, exactly as {@link ReviewJudgeService#decide} computed it — stored,
     * so a reader never recomputes it a second, different way.
     */
    @Column(name = "needs_response", nullable = false)
    private boolean needsResponse;

    /** The model answered; a row with false records a failed reading and is read again next time. */
    @Column(name = "answered", nullable = false)
    private boolean answered;

    /** AMBIGUOUS_TEXT · PRODUCT_UNCLEAR · MODEL_FAILED — why it could not tell, when it could not. */
    @Column(name = "unsure_reason", length = 24)
    private String unsureReason;

    /** INJURY · BODY_REACTION · PRODUCT_HAZARD · LEGAL — the word net's signal, beside the reading, not instead of it. */
    @Column(name = "safety_signal", length = 24)
    private String safetySignal;

    /** Why the review is a person's, when it is. */
    @Column(name = "handoff_reason", length = 40)
    private String handoffReason;
}
