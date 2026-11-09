package com.sellerops.inquiry.draft;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>An inquiry draft held for review</b> (owner, 2026-10-29): written, then not checked against its evidence because
 * the day's AI ceiling was reached. Neither shown nor thrown away — the next generate over the same inputs checks this
 * text instead of writing a new one.
 */
@Getter
@Setter
@Entity
@Table(name = "draft_review_pending")
public class DraftReviewPending extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "work_item_id", nullable = false)
    private UUID workItemId;

    @Column(name = "category", length = 40)
    private String category;

    @Column(name = "title", length = 300)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /** The question, the passages and the order sentence it was written over — reused only while they hold. */
    @Column(name = "inputs_hash", nullable = false, length = 64)
    private String inputsHash;

    /**
     * Which try the held text is: 1 the first draft, 2 the one rewrite (2026-11-02, fifth review). A resume carries on
     * from it — a held rewrite is checked, never written a third time.
     */
    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    /** Why the first draft was refused, when the held text is the rewrite — kept so the resumed outcome still says so. */
    @Column(name = "rewrite_because", length = 32)
    private String rewriteBecause;

    /** The first check of the held text has not run (V181). */
    public static final String CHECK = "CHECK";
    /** The first check ran and came back incomplete; only the one second look is left (V181, eleventh review). */
    public static final String RECHECK = "RECHECK";

    /** What the held text is waiting for: {@link #CHECK} or {@link #RECHECK}. */
    @Column(name = "awaiting", nullable = false, length = 16)
    private String awaiting = CHECK;
}
