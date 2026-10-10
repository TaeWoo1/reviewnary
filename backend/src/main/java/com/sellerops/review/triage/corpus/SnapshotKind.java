package com.sellerops.review.triage.corpus;

/**
 * The two strengths of evidence a cut can be made of, and the one thing they may never do.
 *
 * <p>{@code docs/slices/production-triage-feedback-draft-v1.md} §7.4, as a type: «a silver snapshot may never be
 * merged into a correction snapshot. They answer different questions with different evidential weight, and a
 * single combined file is how the weaker one stops being visible.»
 *
 * <p>There is deliberately no {@code MIXED} member and no way to cut both at once. A caller that wants both cuts
 * makes two calls, gets two version strings, and anyone later reading either one is told which kind it is.
 */
public enum SnapshotKind {

    /**
     * Corrections a human dispositioned {@code CLASSIFIER_ERROR} — the seller answered a question, and a reviewer
     * holding the rubric read that answer as the classifier having been wrong. Strong, and still not gold (§3).
     */
    CORRECTION("교정 스냅샷"),

    /**
     * Actions and navigation traces. Weighted silver: it may rank a human-QA queue or trigger a drift audit, and
     * it may never be a label, a gate, a precision figure or a recall figure (§7.1).
     */
    SILVER("행동 스냅샷");

    private final String labelKo;

    SnapshotKind(String labelKo) {
        this.labelKo = labelKo;
    }

    public String labelKo() {
        return labelKo;
    }
}
