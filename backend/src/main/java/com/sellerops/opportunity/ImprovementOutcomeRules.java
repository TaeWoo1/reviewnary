package com.sellerops.opportunity;

import com.sellerops.reviewissue.ReviewIssueThresholds;
import java.time.LocalDate;

/**
 * Whether what the seller did showed up in the reviews — pure, and built out of numbers that already existed.
 *
 * <p><b>Not one new threshold.</b> Every constant below is {@link ReviewIssueThresholds}', because 개선 already
 * has a definition in this product ({@code IssueChangeRules.isImproved}) and a second one would let the
 * repeated-problem screen and the outcome line disagree about the same four weeks. What this class adds is not
 * a rule but an <b>anchor</b>: the baseline is the window that ended on the day of 적용 and was frozen there,
 * instead of the window that happens to precede today.
 *
 * <p><b>Three refusals come before any verdict, and they are the point.</b> A declining count is improvement
 * only if reviews were still arriving to decline in. In order:
 * <ol>
 *   <li>no reviews at all in the window ⇒ {@link OutcomeReason#NO_COVERAGE}. Zero complaints and zero reviews
 *       are the same number;</li>
 *   <li>reviews arriving at less than {@link ReviewIssueThresholds#IMPROVE_MAX_RATIO} of the earlier rate ⇒
 *       {@link OutcomeReason#COVERAGE_DROPPED}. A 60% fall in evidence is already explained by a 60% fall in
 *       reviews, so the remediation is credited with nothing;</li>
 *   <li>a baseline below {@link ReviewIssueThresholds#IMPROVE_MIN_BASELINE_WEEKLY} per week ⇒
 *       {@link OutcomeReason#BASELINE_TOO_SMALL}. «Below this there was never enough of a problem for a decline
 *       to mean anything» — that constant's own words.</li>
 * </ol>
 *
 * <p>The counts are still reported in all three cases. 판단 보류 beside 「전 5건 → 후 1건」 is informative and
 * honest; 판단 보류 beside nothing reads as a product that lost the measurement.
 *
 * <p><b>Why the worsened side reuses the surge ratio.</b> There is no «it got worse» constant in the product, so
 * rather than inventing one this uses {@link ReviewIssueThresholds#SURGE_RATIO} — the number that already means
 * «this rose enough to tell somebody about». Everything between the two ratios is
 * {@link OutcomeVerdict#UNCHANGED}, which is a wide band on purpose: a measurement that called a 30% move a
 * result would produce a result for every remediation ever made.
 */
public final class ImprovementOutcomeRules {

    /** Both windows are the improvement judgement's own, so the two readings span the same amount of time. */
    public static final int BASELINE_DAYS = ReviewIssueThresholds.IMPROVE_BASELINE_WEEKS * 7;
    public static final int OBSERVE_DAYS = ReviewIssueThresholds.IMPROVE_WINDOW_WEEKS * 7;

    private ImprovementOutcomeRules() {
    }

    /**
     * The baseline window for an application on {@code appliedOn}: the {@value #BASELINE_DAYS} days ending the
     * day BEFORE it.
     *
     * <p>Excluding the day itself is deliberate. A review that arrived on the morning of the fix is evidence of
     * the problem, not of the fix, and counting it on the after side would make every remediation look slightly
     * better than it was.
     */
    public static LocalDate baselineFrom(LocalDate appliedOn) {
        return appliedOn.minusDays(BASELINE_DAYS);
    }

    public static LocalDate baselineTo(LocalDate appliedOn) {
        return appliedOn.minusDays(1);
    }

    /** The last day of the observation window — inclusive, so the window is exactly {@value #OBSERVE_DAYS} days. */
    public static LocalDate observeThrough(LocalDate appliedOn) {
        return appliedOn.plusDays(OBSERVE_DAYS - 1);
    }

    /** Whether the window has closed as of {@code referenceDate}. */
    public static boolean windowClosed(LocalDate appliedOn, LocalDate referenceDate) {
        return !referenceDate.isBefore(observeThrough(appliedOn));
    }

    /** A verdict and the rule that produced it. */
    public record Reading(OutcomeVerdict verdict, OutcomeReason reason) {
    }

    /**
     * Read one closed window.
     *
     * @param baselineEvidence evidence rows in the frozen baseline window
     * @param baselineReviews  reviews received in that window, in the same scope — the denominator
     * @param observedEvidence evidence rows in the observation window
     * @param observedReviews  reviews received in the observation window, in the same scope
     */
    public static Reading read(long baselineEvidence, long baselineReviews,
                               long observedEvidence, long observedReviews) {
        if (observedReviews == 0) {
            return new Reading(OutcomeVerdict.INCONCLUSIVE, OutcomeReason.NO_COVERAGE);
        }
        if (baselineReviews > 0
                && observedReviews < baselineReviews * ReviewIssueThresholds.IMPROVE_MAX_RATIO) {
            return new Reading(OutcomeVerdict.INCONCLUSIVE, OutcomeReason.COVERAGE_DROPPED);
        }
        double baselineWeekly = baselineEvidence / (double) ReviewIssueThresholds.IMPROVE_BASELINE_WEEKS;
        if (baselineWeekly < ReviewIssueThresholds.IMPROVE_MIN_BASELINE_WEEKLY) {
            return new Reading(OutcomeVerdict.INCONCLUSIVE, OutcomeReason.BASELINE_TOO_SMALL);
        }
        if (observedEvidence <= baselineEvidence * ReviewIssueThresholds.IMPROVE_MAX_RATIO) {
            return new Reading(OutcomeVerdict.IMPROVED, OutcomeReason.EVIDENCE_DOWN);
        }
        if (observedEvidence >= baselineEvidence * ReviewIssueThresholds.SURGE_RATIO) {
            return new Reading(OutcomeVerdict.WORSENED, OutcomeReason.EVIDENCE_UP);
        }
        return new Reading(OutcomeVerdict.UNCHANGED, OutcomeReason.EVIDENCE_FLAT);
    }
}
