package com.sellerops.openquestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One act on an {@link OpenQuestion} — opened, handled, re-judged away. Written once, never updated: it is the history. */
@Getter
@Setter
@Entity
@Table(name = "open_question_event")
public class OpenQuestionEvent {

    public static final String OPENED = "OPENED";
    public static final String HANDLED = "HANDLED";
    public static final String SUPERSEDED = "SUPERSEDED";
    /** Closed by the system because the seller's completed action made it moot (2026-11-06). */
    public static final String SETTLED = "SETTLED";

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "resolution", length = 24)
    private String resolution;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "actor", nullable = false, length = 120)
    private String actor;

    @Column(name = "at", nullable = false)
    private Instant at;

    /** The record a system close rests on (V182) — see {@link OpenQuestion#getBasisRef()}. */
    @Column(name = "basis_ref", length = 120)
    private String basisRef;
}
