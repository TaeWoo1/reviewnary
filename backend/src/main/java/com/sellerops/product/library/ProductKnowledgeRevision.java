package com.sellerops.product.library;

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
 * One past revision of a product document, as it stood before its body changed (V190; the company corpus has had the
 * same since V164 — {@code OrgKnowledgeRevision}).
 *
 * <p><b>Append-only and never searched.</b> Retrieval reads chunks of the current revision only: a past revision is a
 * record of what a draft written under it was told, not something an answer may quote today.
 */
@Getter
@Setter
@Entity
@Table(name = "product_knowledge_revisions",
        uniqueConstraints = @UniqueConstraint(name = "uq_product_knowledge_revisions_version",
                columnNames = {"source_id", "version"}))
public class ProductKnowledgeRevision extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 24)
    private KnowledgeSourceType sourceType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "document_name", length = 260)
    private String documentName;

    @Column(name = "replaced_at", nullable = false)
    private Instant replacedAt;

    /** The revision {@code source} is about to stop being. */
    public static ProductKnowledgeRevision of(ProductKnowledgeSource source, Instant replacedAt) {
        ProductKnowledgeRevision row = new ProductKnowledgeRevision();
        row.setOrgId(source.getOrgId());
        row.setSourceId(source.getId());
        row.setProductId(source.getProductId());
        row.setVersion(source.getVersion());
        row.setSourceType(source.getSourceType());
        row.setTitle(source.getTitle());
        row.setBody(source.getBody());
        row.setDocumentName(source.getDocumentName());
        row.setReplacedAt(replacedAt);
        return row;
    }
}
