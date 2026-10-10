package com.sellerops.opportunity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.reviewissue.ReviewIssueThresholds;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reading of one closed window — and the three refusals that come before any verdict.
 *
 * <p>Every number here is {@link ReviewIssueThresholds}', and that is the property worth asserting first: a
 * second definition of 개선 would let the repeated-problem screen and the outcome line disagree about the same
 * four weeks.
 */
class ImprovementOutcomeRulesTest {

    private static final LocalDate APPLIED = LocalDate.of(2026, 9, 1);

    @Test
    @DisplayName("the windows are the improvement judgement's own, and the day of 적용 belongs to neither baseline")
    void theWindowsComeFromTheExistingThresholds() {
        assertThat(ImprovementOutcomeRules.BASELINE_DAYS)
                .isEqualTo(ReviewIssueThresholds.IMPROVE_BASELINE_WEEKS * 7);
        assertThat(ImprovementOutcomeRules.OBSERVE_DAYS)
                .isEqualTo(ReviewIssueThresholds.IMPROVE_WINDOW_WEEKS * 7);

        // The baseline ends the day BEFORE: a review that arrived on the morning of the fix is evidence of the
        // problem, not of the fix.
        assertThat(ImprovementOutcomeRules.baselineTo(APPLIED)).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(ImprovementOutcomeRules.baselineFrom(APPLIED)).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(ImprovementOutcomeRules.observeThrough(APPLIED)).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(ImprovementOutcomeRules.windowClosed(APPLIED, LocalDate.of(2026, 9, 27))).isFalse();
        assertThat(ImprovementOutcomeRules.windowClosed(APPLIED, LocalDate.of(2026, 9, 28))).isTrue();
    }

    // ── the three refusals, in order ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no reviews in the window is never improvement — zero complaints and zero reviews are the same number")
    void silenceWithoutReviewsIsNotASuccess() {
        ImprovementOutcomeRules.Reading reading = ImprovementOutcomeRules.read(20, 200, 0, 0);
        assertThat(reading.verdict()).isEqualTo(OutcomeVerdict.INCONCLUSIVE);
        assertThat(reading.reason()).isEqualTo(OutcomeReason.NO_COVERAGE);
        // And this is the case that would otherwise have read as the best possible result: the SAME zero
        // evidence, with reviews still arriving, is improvement. Only the denominator tells them apart.
        assertThat(ImprovementOutcomeRules.read(20, 200, 0, 180).verdict()).isEqualTo(OutcomeVerdict.IMPROVED);
    }

    @Test
    @DisplayName("a collapse in review volume explains a fall in evidence by itself, so nothing is credited")
    void aCoverageDropWithholdsTheVerdict() {
        // 200 reviews before, 30 after: below IMPROVE_MAX_RATIO of the earlier rate.
        ImprovementOutcomeRules.Reading reading = ImprovementOutcomeRules.read(20, 200, 2, 30);
        assertThat(reading.verdict()).isEqualTo(OutcomeVerdict.INCONCLUSIVE);
        assertThat(reading.reason()).isEqualTo(OutcomeReason.COVERAGE_DROPPED);

        // 100 of 200 is half — enough of the population to still read the evidence.
        assertThat(ImprovementOutcomeRules.read(20, 200, 2, 100).verdict()).isEqualTo(OutcomeVerdict.IMPROVED);
    }

    @Test
    @DisplayName("too small a baseline means a decline says nothing, and the counts are still reported")
    void aThinBaselineWithholdsTheVerdict() {
        // IMPROVE_MIN_BASELINE_WEEKLY = 2.0 over 4 weeks ⇒ 8 rows is the floor.
        ImprovementOutcomeRules.Reading thin = ImprovementOutcomeRules.read(7, 100, 0, 100);
        assertThat(thin.verdict()).isEqualTo(OutcomeVerdict.INCONCLUSIVE);
        assertThat(thin.reason()).isEqualTo(OutcomeReason.BASELINE_TOO_SMALL);
        assertThat(ImprovementOutcomeRules.read(8, 100, 0, 100).verdict()).isEqualTo(OutcomeVerdict.IMPROVED);
    }

    // ── and then the verdict ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("down to a fraction is 개선, doubled is 악화, and everything between is 변화 없음")
    void theThreeSettledVerdicts() {
        // 20 → 8 is exactly IMPROVE_MAX_RATIO (0.40): the boundary is improvement, as the existing rule has it.
        assertThat(ImprovementOutcomeRules.read(20, 100, 8, 100))
                .isEqualTo(new ImprovementOutcomeRules.Reading(OutcomeVerdict.IMPROVED,
                        OutcomeReason.EVIDENCE_DOWN));
        assertThat(ImprovementOutcomeRules.read(20, 100, 9, 100))
                .isEqualTo(new ImprovementOutcomeRules.Reading(OutcomeVerdict.UNCHANGED,
                        OutcomeReason.EVIDENCE_FLAT));
        assertThat(ImprovementOutcomeRules.read(20, 100, 39, 100).verdict())
                .as("a wide 변화 없음 band on purpose — a measurement that called a 30%% move a result would "
                        + "produce a result for every remediation ever made")
                .isEqualTo(OutcomeVerdict.UNCHANGED);
        assertThat(ImprovementOutcomeRules.read(20, 100, 40, 100))
                .isEqualTo(new ImprovementOutcomeRules.Reading(OutcomeVerdict.WORSENED,
                        OutcomeReason.EVIDENCE_UP));
    }

    @Test
    @DisplayName("no verdict word claims the act caused anything, and only OBSERVING is unsettled")
    void thereIsNoCausalWord() {
        for (OutcomeVerdict verdict : OutcomeVerdict.values()) {
            assertThat(verdict.labelKo())
                    .as("%s", verdict)
                    .isNotBlank()
                    .doesNotContain("해결")
                    .doesNotContain("효과")
                    .doesNotContain("덕분");
        }
        assertThat(OutcomeVerdict.OBSERVING.settled()).isFalse();
        assertThat(OutcomeVerdict.INCONCLUSIVE.settled())
                .as("판단 보류 is a final answer about a closed window, not a pending one")
                .isTrue();
        for (OutcomeReason reason : OutcomeReason.values()) {
            assertThat(reason.labelKo()).as("%s", reason).isNotBlank().doesNotContain("_");
        }
    }
}
