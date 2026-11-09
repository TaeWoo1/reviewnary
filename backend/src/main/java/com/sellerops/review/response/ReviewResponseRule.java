package com.sellerops.review.response;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>리뷰 응대 기준 — one seller's answer to 「which reviews get a draft before I ask」</b> (product-owner, 2026-10-28).
 *
 * <p>Three rating bands, because the band is the one signal every channel sends and every seller already sorts by. What
 * a draft SAYS is not decided here — a ★4–5 that only praises is written as a thank-you and one that names a problem
 * as a reply to it ({@code ReviewDraftComposer}) — only whether it is written before the seller opens the review:
 * always, only when the review names a problem or asks something, or never.
 * No row means the defaults in {@link ReviewResponseRules#DEFAULT}.
 */
@Getter
@Setter
@Entity
@Table(name = "review_response_rule")
public class ReviewResponseRule {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** ★1–2. */
    @Enumerated(EnumType.STRING)
    @Column(name = "low_mode", nullable = false, length = 16)
    private ReviewResponseMode lowMode = ReviewResponseMode.AHEAD;

    /** ★3, and a review with no rating. */
    @Enumerated(EnumType.STRING)
    @Column(name = "mid_mode", nullable = false, length = 16)
    private ReviewResponseMode midMode = ReviewResponseMode.AHEAD;

    /** ★4–5. */
    @Enumerated(EnumType.STRING)
    @Column(name = "high_mode", nullable = false, length = 16)
    private ReviewResponseMode highMode = ReviewResponseMode.ISSUES_ONLY;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
