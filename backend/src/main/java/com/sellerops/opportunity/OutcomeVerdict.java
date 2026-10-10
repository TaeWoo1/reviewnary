package com.sellerops.opportunity;

/**
 * What the evidence did in a named window after the seller applied one improvement — <b>and never why</b>.
 *
 * <p>Five words, and the hard one is {@link #INCONCLUSIVE}. It is the answer whenever the measurement's own
 * premise failed: too few reviews came in to see anything, or there was never enough of a problem for a decline
 * to mean something. A product that reported 개선됨 in those cases would be reading a collection gap as a
 * success, which is the failure {@code docs/slices/attention-coverage-false-calm-v1.md} exists about — and it
 * would do it in the direction nobody checks, because good news is not investigated.
 *
 * <p><b>None of these says the remediation caused anything.</b> The labels are deliberately about the evidence
 * («근거가 줄었습니다») rather than about the act («해결했습니다»), the same discipline
 * {@code IssueLifecycleState.RESOLVED} already holds: «Not a claim that the problem is gone».
 */
public enum OutcomeVerdict {

    /** The window is still open. Not an outcome — the absence of one, with a date when there will be. */
    OBSERVING("확인 중"),
    /** Evidence in the window fell far enough below the frozen baseline to be worth saying. */
    IMPROVED("근거 줄었습니다"),
    /** The window closed and the evidence moved less than that. */
    UNCHANGED("변화 없습니다"),
    /** Evidence rose far enough above the baseline to be worth saying. */
    WORSENED("근거 늘었습니다"),
    /** The measurement could not be made. {@link OutcomeReason} says which premise failed. */
    INCONCLUSIVE("판단 보류");

    private final String labelKo;

    OutcomeVerdict(String labelKo) {
        this.labelKo = labelKo;
    }

    public String labelKo() {
        return labelKo;
    }

    /** Whether the window has closed and this verdict is final. {@link #OBSERVING} is the only one that is not. */
    public boolean settled() {
        return this != OBSERVING;
    }
}
