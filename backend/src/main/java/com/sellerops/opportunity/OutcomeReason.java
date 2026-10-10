package com.sellerops.opportunity;

/**
 * Which rule produced the verdict — in the seller's words, because this is the sentence they read.
 *
 * <p>Three of the six are refusals, and they exist so that «판단 보류» is never a shrug. A seller who applied a
 * fix and is told 판단 보류 has one question — why can't you tell me — and these answer it: nothing came in, not
 * enough was coming in, or there was never enough of a problem to measure a decline against.
 */
public enum OutcomeReason {

    /** The observation window has not closed yet. */
    WINDOW_OPEN("관찰 기간이 아직 끝나지 않았습니다"),
    /** Evidence in the window fell to a fraction of the baseline. */
    EVIDENCE_DOWN("같은 문제가 적게 들어왔습니다"),
    /** It moved, but not far enough in either direction to be worth a word. */
    EVIDENCE_FLAT("같은 문제가 비슷하게 들어왔습니다"),
    /** Evidence rose far above the baseline. */
    EVIDENCE_UP("같은 문제가 더 들어왔습니다"),
    /**
     * No reviews at all arrived in the window.
     *
     * <p>The one refusal that would otherwise have been reported as success: zero complaints and zero reviews
     * are the same number, and only the denominator tells them apart.
     */
    NO_COVERAGE("그 기간에 들어온 리뷰가 없어 비교할 수 없습니다"),
    /** Reviews kept arriving but at a fraction of the earlier rate, so a drop in evidence explains itself. */
    COVERAGE_DROPPED("들어온 리뷰 자체가 크게 줄어 비교할 수 없습니다"),
    /** There was never enough of a problem before for a decline to mean anything. */
    BASELINE_TOO_SMALL("적용 전 근거가 적어 변화라고 말할 수 없습니다");

    private final String labelKo;

    OutcomeReason(String labelKo) {
        this.labelKo = labelKo;
    }

    public String labelKo() {
        return labelKo;
    }
}
