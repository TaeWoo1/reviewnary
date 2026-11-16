package com.sellerops.product.analysis;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Opportunities and threats written from one exact set of observed evidence; other evidence is another writing. */
@Getter
@Setter
@Entity
@Table(name = "product_outlook_writings")
public class ProductOutlookWriting extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "input_fingerprint", nullable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "writer_version", nullable = false, length = 200)
    private String writerVersion;

    /** JSON {opportunities:[…], threats:[…]} of {title, basis, check, derivedFrom}. */
    @Column(nullable = false, columnDefinition = "text")
    private String cells;
}
