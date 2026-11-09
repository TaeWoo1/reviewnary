package com.sellerops.inquiry.decision;

/** Test-only: the judge's system turns, for tests outside this package (the builders are package-private). */
public final class InquiryDecisionPromptAccess {

    private InquiryDecisionPromptAccess() {
    }

    public static String v5() {
        return InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V5);
    }

    public static String v6() {
        return InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V6);
    }
}
