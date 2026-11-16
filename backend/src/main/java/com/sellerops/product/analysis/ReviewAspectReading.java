package com.sellerops.product.analysis;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One review text read into aspect opinions by the review-aspect model; shared by every review with that text. */
@Getter
@Setter
@Entity
@Table(name = "review_aspect_readings")
public class ReviewAspectReading extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "body_sha256", nullable = false, length = 64)
    private String bodySha256;

    @Column(name = "reader_version", nullable = false, length = 200)
    private String readerVersion;

    /** JSON array of {aspect, polarity, problem, evidence}; an empty array is a reading that found no opinion. */
    @Column(nullable = false, columnDefinition = "text")
    private String opinions;
}
