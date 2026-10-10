package com.sellerops.review.decision.dto;

/**
 * The closed set of things a Decision Workspace entry can be.
 *
 * <p>One value per <b>source trail</b>, not one per source table: {@code SELLER_JUDGMENT_SET} and
 * {@code SELLER_JUDGMENT_WITHDRAWN} both come from the correction audit and are two different
 * statements, while a reply approval and its withdrawal are likewise two. The screen maps each to a
 * sentence; nothing downstream parses the Korean back out.
 */
public enum ReviewDecisionLogKind {

    /** The seller stated their own tier for this review (T-07). */
    SELLER_JUDGMENT_SET,

    /** The seller took that judgment back. The review reads as the system's alone again. */
    SELLER_JUDGMENT_WITHDRAWN,

    /** The seller chose what to do — the response decision ({@code TriageDisposition}). */
    ACTION_CHOSEN,

    /** The seller recorded an act they performed off this screen ({@code TriageActionKind}). */
    ACTION_RECORDED,

    /** A reply version was approved, or an approval was withdrawn ({@code ReviewReplyApprovalState}). */
    REPLY_APPROVAL,

    /** The seller reported what happened to the approved reply ({@code OperatorOutcome}). */
    REPLY_OUTCOME,

    /**
     * What reviewnary itself did with the approved reply, and what it could confirm —
     * {@code review_reply_execution} (Review Delivery Truth Spine v1).
     *
     * <p><b>Not a duplicate of {@link #REPLY_OUTCOME}.</b> That one is the seller's report about their own manual
     * post; this one is reviewnary's record of its own act — the Cafe24 POST and its hash read-back, the guided
     * composer fill, the collector's sighting of the seller's submit. The log carried only the first, so a reply
     * reviewnary posted and verified left no entry at all on the one screen that answers «what has already been
     * decided about this review».
     *
     * <p><b>{@code from} is what was done and {@code to} is what was confirmed</b> —
     * {@code ReviewExecutionStatus} then {@code ReviewExecutionVerification}. That is a real progression and not
     * an abuse of the pair: «POSTED, and the read-back hashes» is two different facts and the screen must be able
     * to say the first without the second. An execution with nothing to confirm (a {@code REFUSED} row — a gate
     * stopped it, or the channel rejected the body) carries {@code from} null and the status in {@code to}, so
     * that a reader who only ever looks at {@code to} can never mistake a refusal for a delivery.
     */
    REPLY_EXECUTION
}
