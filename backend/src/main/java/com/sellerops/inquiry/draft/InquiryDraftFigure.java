package com.sellerops.inquiry.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One number a model-written inquiry draft states, and what it was written from (V166; 2026-11-01 review: a recheck that
 * cannot read a figure's ground must say it cannot, not pass it). Written when the draft is saved, from the passages the
 * drafter was shown, by {@link InquiryFigureGrounds}.
 *
 * <p>The ground of a figure the seller's own material carries is the one clause of that material it is written in —
 * enough for a recheck to tell 「the clause is still there」 from 「the number is gone」 from 「neither, so nobody can
 * say」. A figure from the order sentence or the customer's question keeps its value and unit only: neither text is kept
 * here.
 *
 * <p>Append-only, like {@link InquiryDraftEvidence}.
 */
@Getter
@Setter
@Entity
@Table(name = "inquiry_draft_figure")
public class InquiryDraftFigure {

    /** Where the number came from. */
    /** {@code SELLER_CHECK}: the seller's record for this inquiry (2026-11-02) — it holds while that record is current. */
    public enum Ground { ORG_POLICY, PRODUCT_KNOWLEDGE, PRODUCT_FACTS, SELLER_GUIDANCE, ORDER, QUESTION, NONE, SELLER_CHECK }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "work_item_id", nullable = false)
    private UUID workItemId;

    @Column(name = "draft_version", nullable = false)
    private int draftVersion;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = 40)
    private String value;

    @Column(length = 16)
    private String unit;

    @Column(name = "reply_clause", nullable = false, length = 500)
    private String replyClause;

    @Column(name = "ground_kind", nullable = false, length = 24)
    private String groundKind;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "ground_clause", length = 500)
    private String groundClause;

    @Column(name = "source_version")
    private Integer sourceVersion;

    /**
     * The claim the number is in, as the claim check read it at generation (V167): what it is about and the condition it
     * holds under, in the evidence's words; whose word it is — {@code SELLER} (the seller's material), {@code ORDER},
     * {@code CUSTOMER} (said back from the question); and how the ground was chosen — {@code MODEL} when the claim check
     * named the passage, {@code TEXT} when only one clause anywhere states the number. Null on rows before V167.
     */
    @Column(length = 80)
    private String about;

    @Column(name = "condition_text", length = 120)
    private String conditionText;

    @Column(length = 16)
    private String voice;

    @Column(name = "linked_by", length = 16)
    private String linkedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
