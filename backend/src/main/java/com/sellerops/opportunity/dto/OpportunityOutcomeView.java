package com.sellerops.opportunity.dto;

import java.time.LocalDate;

/**
 * What one applied improvement did, as a surface may say it.
 *
 * <p><b>Two counts and two windows, always — even when the verdict is 판단 보류.</b> 「판단 보류」 beside nothing
 * reads as a product that lost the measurement; beside 「전 4주 11건 → 후 4주 2건 · 들어온 리뷰 자체가 크게 줄어
 * 비교할 수 없습니다」 it reads as a product that measured carefully and refused to overclaim. The refusal is the
 * more valuable output of the two, so it is the one that gets the numbers.
 *
 * <p><b>No causal word anywhere.</b> {@code verdictLabelKo} is about the evidence («근거 줄었습니다»), never about
 * the act («해결했습니다») — the same discipline {@code IssueLifecycleState.RESOLVED} holds.
 *
 * @param reviewsBefore the denominator. Present so a seller can see WHY a verdict was withheld, and so a reader
 *                      can tell a quiet month from a month with no reviews in it
 */
public record OpportunityOutcomeView(
        String kind, String kindLabelKo, String scope,
        LocalDate appliedOn, LocalDate observedThrough,
        int evidenceBefore, int reviewsBefore,
        Integer evidenceAfter, Integer reviewsAfter,
        String verdict, String verdictLabelKo, String reasonLabelKo,
        boolean settled) {
}
