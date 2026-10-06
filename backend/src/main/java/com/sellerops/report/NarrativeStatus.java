package com.sellerops.report;

/**
 * What happened to the narrative when the snapshot was taken. Stored, so a reopened report explains
 * itself without re-asking the capability.
 */
public enum NarrativeStatus {
    /** The model wrote lines and at least one survived {@link NarrativeClaimGuard}. */
    READY(null),
    /**
     * The report no longer asks for one (2026-10-06, product-owner decision).
     *
     * <p>The screen stopped printing a narrative when the report became a workspace of values with their
     * read range, so generating one meant a synchronous vendor call — measured at 25.5s on the first
     * open of a period — producing an artifact nothing rendered. The three statuses above it stay
     * because stored rows carry them; this is what a row written from here on says.
     */
    NOT_GENERATED(null),
    /** The capability is off for this org — the deterministic summary is the whole reading. */
    UNAVAILABLE("AI 요약 기능이 이 계정에서 꺼져 있어, 정리된 사실만 보여 드립니다."),
    /** The call failed, or nothing the model wrote could be traced to a fact. */
    FAILED("AI 요약을 만들지 못해, 정리된 사실만 보여 드립니다.");

    private final String noteKo;

    NarrativeStatus(String noteKo) {
        this.noteKo = noteKo;
    }

    /** The one sentence the screen prints beside a report without a narrative, or null. */
    public String noteKo() {
        return noteKo;
    }
}
