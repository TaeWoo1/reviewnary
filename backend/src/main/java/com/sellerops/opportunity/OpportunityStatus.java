package com.sellerops.opportunity;

/**
 * Where the seller's decision stands. {@code OPEN} is the absence of a row — the derived opportunity
 * as the rules produce it, nothing decided yet. The other three are stored.
 *
 * <p><b>{@link #APPLIED} is a fourth state and not a fourth outcome.</b> It says the seller carried the prepared
 * action out — saved the draft into their own library, or told us they acted on it. Until Learning &amp; Outcome
 * Loop v1 this was recorded nowhere at all: {@code OpportunityCard} saved the text straight into the knowledge
 * library from the browser and this row never learned it, so «채택했다» and «실제로 했다» were the same word. What
 * happened to the PROBLEM afterwards is {@code ImprovementOutcome}, deliberately a different table with a
 * different vocabulary.
 */
public enum OpportunityStatus {
    OPEN("검토 전"),
    ACCEPTED("초안 준비됨"),
    /** The seller carried it out. Terminal among the decisions — see {@code OpportunityService.apply}. */
    APPLIED("적용했습니다"),
    DISMISSED("보류");

    private final String labelKo;

    OpportunityStatus(String labelKo) {
        this.labelKo = labelKo;
    }

    public String labelKo() {
        return labelKo;
    }
}
