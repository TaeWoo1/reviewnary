package com.sellerops.knowledge.currency;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>A document's 적용일, kept beside it, not on it</b> (2026-11-15). The document row is not touched: its save time is how
 * a cited document is read as edited, and a date the seller adds says nothing about the text.
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_source_currency")
public class KnowledgeSourceCurrency extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** A product or a company document — ids are unique across both. */
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "updated_by_name", length = 100)
    private String updatedByName;
}
