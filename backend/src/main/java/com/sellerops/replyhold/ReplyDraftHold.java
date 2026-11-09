package com.sellerops.replyhold;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One reply draft that did not stand when checked again against the seller's current knowledge, policy and wording
 * (V165). The draft's text is not here and is never changed: this row says which version must be looked at again, and
 * why. A row whose {@link #draftVersion} is not the draft's head belongs to an older version and is not read.
 */
@Getter
@Setter
@Entity
@Table(name = "reply_draft_hold",
        uniqueConstraints = @UniqueConstraint(name = "uq_reply_draft_hold_subject", columnNames = {"lane", "subject_id"}))
public class ReplyDraftHold extends BaseEntity {

    public enum Lane { REVIEW, INQUIRY }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Lane lane;

    /** The review, or the inquiry work item. */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "draft_version", nullable = false)
    private int draftVersion;

    /** An approved reply was held: its registration waits for a new approval. */
    @Column(nullable = false)
    private boolean approved;

    @Column(nullable = false, length = 200)
    private String codes;

    /** {@link ReplyHoldService.Finding} as JSON — titles, words and phrases, never a customer's text. */
    @Column(nullable = false, columnDefinition = "text")
    private String note;

    @Column(nullable = false, length = 300)
    private String basis;
}
