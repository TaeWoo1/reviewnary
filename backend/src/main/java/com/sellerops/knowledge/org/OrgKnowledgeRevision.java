package com.sellerops.knowledge.org;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One past revision of an operating rule, as it stood before its body changed (V164; seller decision 2026-11-01 — the
 * English exchange policy was corrected from 「unused」 to 「unopened」 and the old text was to be kept as history).
 *
 * <p><b>Append-only and never searched.</b> {@link SellerOperationsKnowledgeService#update} writes one row when the
 * version rises and nothing writes it again. Retrieval reads chunks of the current revision only: a past revision is a
 * record of what a draft written under it was told, not something an answer may quote today.
 */
@Getter
@Setter
@Entity
@Table(name = "org_knowledge_revisions",
        uniqueConstraints = @UniqueConstraint(name = "uq_org_knowledge_revisions_version",
                columnNames = {"source_id", "version"}))
public class OrgKnowledgeRevision extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** The revision this row holds — the version a draft that cited it recorded. */
    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_type", nullable = false, length = 32)
    private OrgKnowledgeType knowledgeType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "customer_phrases", columnDefinition = "text")
    private String customerPhrases;

    /** When the next revision took its place. */
    @Column(name = "replaced_at", nullable = false)
    private Instant replacedAt;

    static OrgKnowledgeRevision of(OrgKnowledgeSource source, Instant replacedAt) {
        OrgKnowledgeRevision row = new OrgKnowledgeRevision();
        row.setOrgId(source.getOrgId());
        row.setSourceId(source.getId());
        row.setVersion(source.getVersion());
        row.setKnowledgeType(source.getKnowledgeType());
        row.setTitle(source.getTitle());
        row.setBody(source.getBody());
        row.setSourceUrl(source.getSourceUrl());
        row.setCustomerPhrases(source.getCustomerPhrases());
        row.setReplacedAt(replacedAt);
        return row;
    }
}
