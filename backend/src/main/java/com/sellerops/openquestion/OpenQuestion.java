package com.sellerops.openquestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One question a draft could not answer from the store's material (V171, 2026-11-01 tenth review). It belongs to the
 * work — a review, or an inquiry's work item — not to a draft version, so a seller rewording the reply leaves it where it
 * is. It closes in exactly two ways: the seller handles it, with a reason ({@link OpenQuestionService#handle}), or a new
 * judgement of the whole message no longer lists it ({@link OpenQuestionService#replace}) — among them one the seller's
 * completed action made moot, closed with the record named ({@link OpenQuestionService#settle}). All are written to
 * {@link OpenQuestionEvent}. {@code length} is pinned on every string because tests build the schema from these
 * annotations while production runs V171.
 */
@Getter
@Setter
@Entity
@Table(name = "open_question")
public class OpenQuestion {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_HANDLED = "HANDLED";
    public static final String STATE_SUPERSEDED = "SUPERSEDED";

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** {@code REVIEW} or {@code INQUIRY}. */
    @Column(name = "lane", nullable = false, length = 16)
    private String lane;

    /** The review id, or the inquiry's work item id. */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "question", nullable = false, length = 500)
    private String question;

    /** Whether the reply may not leave while this is open — every question a customer asked is, unless said otherwise. */
    @Column(name = "required", nullable = false)
    private boolean required;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "resolution", length = 24)
    private String resolution;

    @Column(name = "note", length = 300)
    private String note;

    /** What opened it: {@code reply-run:<id>} or {@code inquiry-draft:<work item>:<version>}. */
    @Column(name = "origin_ref", nullable = false, length = 120)
    private String originRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 120)
    private String closedBy;

    /**
     * The record a system close rests on (V182, 2026-11-06): {@code seller-check:<id>} for a question the seller's
     * completed action made moot. Null for every other close.
     */
    @Column(name = "basis_ref", length = 120)
    private String basisRef;
}
