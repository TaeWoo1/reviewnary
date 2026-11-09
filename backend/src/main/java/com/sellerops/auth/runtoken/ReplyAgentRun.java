package com.sellerops.auth.runtoken;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>One reply-agent run, and the hash of the token it alone may use</b> (owner, 2026-10-28,
 * {@code docs/standing/reply_agent_v1.md} §2). One org, one review or inquiry, a few minutes, closed when the run ends.
 */
@Getter
@Setter
@Entity
@Table(name = "reply_agent_run")
public class ReplyAgentRun extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** REVIEW or INQUIRY. */
    @Column(name = "subject_kind", nullable = false, length = 16)
    private String subjectKind;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    /** SELLER · INGEST · SCHEDULED — what started it. */
    @Column(name = "trigger_kind", nullable = false, length = 16)
    private String triggerKind;

    /** The seller who pressed for this run; null for a run no seller started. */
    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** How it ended: SAVED · HANDOFF · FAILED · EXPIRED; null while it runs. */
    @Column(name = "outcome", length = 24)
    private String outcome;

    /** The judge step's category (S · L · R · Q · D · M · V · X · P). */
    @Column(name = "category", length = 2)
    private String category;

    /**
     * The draft between steps. Held here, not in the runtime, so every model step's input is text the backend wrote —
     * the runtime orders the steps and never supplies their content.
     */
    @Column(name = "working_draft", columnDefinition = "text")
    private String workingDraft;

    /** The rule ids the last critique failed, comma-separated (e.g. {@code G4,N9}). */
    @Column(name = "failed_rules", length = 200)
    private String failedRules;

    /** The critique's notes for one revision. Model prose about our draft; read only by the revision step. */
    @Column(name = "critique_note", columnDefinition = "text")
    private String critiqueNote;

    @Column(name = "revisions", nullable = false)
    private int revisions;

    /** What the first critique failed — kept after a revision passes, for evaluation by rule. */
    @Column(name = "first_failed_rules", length = 200)
    private String firstFailedRules;

    /** Every rule id the deterministic checks failed over the run, comma-separated. */
    @Column(name = "checks_failed", length = 200)
    private String checksFailed;

    /** The draft version the run saved, when it saved one. */
    @Column(name = "draft_version")
    private Integer draftVersion;

    /** Every other category the judge found in the review, comma-separated — kept, not dropped. */
    @Column(name = "also_categories", length = 40)
    private String alsoCategories;

    /** The points a reply must cover, one per line, as the judge read them (G8). */
    @Column(name = "points", columnDefinition = "text")
    private String points;

    /** The fingerprint of the review text the category was judged on. */
    @Column(name = "judged_hash", length = 64)
    private String judgedHash;

    /** The fingerprint of what the working draft was written over: the review, the passages, the company's wording. */
    @Column(name = "inputs_hash", length = 64)
    private String inputsHash;

    /** The fingerprint of the draft that passed its critique together with the inputs it was checked against. */
    @Column(name = "passed_hash", length = 64)
    private String passedHash;

    /**
     * The bundle passages the working draft cites, verified (V167, 2026-11-01): its whole ground for the critique, the
     * save and the recheck. {@code usedRejected}: citations the writer gave that were not in the bundle as quoted.
     */
    @Column(name = "used_passages", length = 200)
    private String usedPassages;

    @Column(name = "used_rejected", nullable = false)
    private int usedRejected;

    /** In-use passages the run's bundle had no room for, by kind (V168): a draft written without all of them. */
    @Column(name = "bundle_policies_left", nullable = false)
    private int bundlePoliciesLeft;

    @Column(name = "bundle_product_left", nullable = false)
    private int bundleProductLeft;

    /** The form rules (F…) still failed after the revision, when they were all that was left (V169): saved to polish. */
    @Column(name = "polish_rules", length = 200)
    private String polishRules;

    /** The questions the writer found no answer for while the rest of the reply stands (V170), one per line; null when none. */
    @Column(name = "open_questions", length = 500)
    private String openQuestions;

    /** Why the run handed the review to a person: CATEGORY_S · CATEGORY_L · CATEGORY_X · STILL_FAILS · …. */
    @Column(name = "handoff_reason", length = 40)
    private String handoffReason;
}
