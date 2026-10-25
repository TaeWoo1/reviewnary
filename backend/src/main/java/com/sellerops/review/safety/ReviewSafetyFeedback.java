package com.sellerops.review.safety;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>A seller saying a review's safety mark is wrong</b> (mockup ②, 2026-10-25). The mark itself never changes on it —
 * the signal is read from the body on every read — and this row is kept as test material for the signal, beside the
 * mark the seller saw when they pressed.
 */
@Getter
@Setter
@Entity
@Table(name = "review_safety_feedback")
public class ReviewSafetyFeedback extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(length = 20)
    private String kind;

    @Column(length = 12)
    private String reading;

    @Column(length = 40)
    private String term;

    @Column(name = "created_by", nullable = false, length = 120)
    private String createdBy;
}
