package com.sellerops.review.triage.eval;

import com.sellerops.itemanalysis.eval.EvalMetrics;
import com.sellerops.review.triage.ReviewSafetySignal;
import com.sellerops.review.triage.ReviewSafetySignal.Outcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The safety evaluation set's draws and arithmetic</b> — {@code contracts/review-eval/safety/v1/RUBRIC.md}. Pure,
 * so the numbers the gated runs print are the numbers {@code SafetySampleTest} checks.
 *
 * <p>Three sets with three jobs, never mixed (owner decision 2026-10-23, the third from the external review the same
 * day): the <b>random</b> set keeps the real mix of reviews and says how often an alarm is needless; the
 * <b>challenge</b> set gathers every review that mentions a harm word and says what is missed <i>among those</i>; the
 * <b>low-star</b> set is drawn by rating alone, without the signal's word list, and says what is missed outside it. A
 * precision measured on the challenge set would say nothing about operation, and a detection rate measured on it says
 * nothing about a report written in words the signal does not know.
 */
final class SafetySample {

    /** The labels of RUBRIC §1. {@code UNKNOWN} is excluded from every rate and reported on its own. */
    enum Label { REPORT, WORRY, REASSURANCE, UNRELATED, UNKNOWN }

    static final Set<Label> SHOULD_RISE = Set.of(Label.REPORT, Label.WORRY);
    static final Set<Label> NOT_HARM = Set.of(Label.REASSURANCE, Label.UNRELATED);

    static final int RANDOM_SIZE = 400;
    static final int CHALLENGE_SIZE = 300;
    static final int LOW_STAR_SIZE = 200;

    /** The pilot bars of RUBRIC §5, fixed by the owner on 2026-10-23 before any label was read. */
    static final double MIN_CHALLENGE_RECALL = 0.90;
    static final int MAX_HIDDEN_REPORTS = 0;
    static final double MIN_PRECISION = 0.50;
    static final double TARGET_PRECISION = 0.70;
    static final double MAX_HIGH_RATING_FALSE_ALARM = 0.05;

    /** Below these, a measure says 「판단 보류」, not pass or fail (RUBRIC §5). */
    static final int MIN_RAISED_FOR_PRECISION = 20;
    static final int MIN_HIGH_RATING_NOT_HARM = 100;
    static final int MIN_CHALLENGE_POSITIVES = 30;
    /** The detection rate outside the word list has no bar yet (owner decision pending); below this it is 판단 보류. */
    static final int MIN_OUTSIDE_POSITIVES = 30;

    record FrameRow(String fingerprint, Integer rating, Outcome outcome) {

        boolean raised() {
            return outcome == Outcome.RAISED;
        }

        boolean challenge() {
            return outcome != Outcome.NONE;
        }
    }

    record Labelled(FrameRow row, Label label) {}

    static FrameRow row(String fingerprint, Integer rating, String body) {
        return new FrameRow(fingerprint, rating, ReviewSafetySignal.explain(body));
    }

    static boolean highRating(Integer rating) {
        return rating != null && rating >= 4;
    }

    static boolean lowRating(Integer rating) {
        return rating != null && rating <= 2;
    }

    private static List<FrameRow> first(List<FrameRow> rows, int n) {
        return rows.stream().sorted(Comparator.comparing(r -> CalibrationSample.sampleOrderKey(r.fingerprint())))
                .limit(n).toList();
    }

    /** The random set: the first {@value #RANDOM_SIZE} of every review by sample order — the real mix. */
    static List<FrameRow> randomSet(List<FrameRow> frame) {
        return first(frame, RANDOM_SIZE);
    }

    /** The challenge set: the first {@value #CHALLENGE_SIZE} reviews that mention any harm word at all. */
    static List<FrameRow> challengeSet(List<FrameRow> frame) {
        return first(frame.stream().filter(FrameRow::challenge).toList(), CHALLENGE_SIZE);
    }

    /**
     * The low-star set: the first {@value #LOW_STAR_SIZE} 1–2★ reviews by sample order, whatever the signal makes of
     * them — the one draw that does not depend on its word list.
     */
    static List<FrameRow> lowStarSet(List<FrameRow> frame) {
        return first(frame.stream().filter(r -> lowRating(r.rating())).toList(), LOW_STAR_SIZE);
    }

    /** What one measure concluded. {@code NO_BAR}: measured with enough sample, but the owner has not set a bar. */
    enum Verdict { PASS, BELOW, HOLD, NO_BAR }

    record Measure(String name, double value, int numerator, int denominator, String bar, Verdict verdict) {}

    /**
     * {@code outside}: the detection rate on the sets drawn without the word list (random ∪ low-star) — reference
     * only, never in {@link #overall}. {@code missedWithoutKnownWord}: of the reports and worries it missed, how many
     * carry no word the signal knows at all.
     */
    record Report(int randomLabelled, int challengeLabelled, int unknown, List<Measure> measures,
                  double alarmsPerHundred, double precisionLow, double precisionHigh, Map<String, Integer> readings,
                  int lowStarLabelled, Measure outside, int missedWithoutKnownWord) {

        Verdict overall() {
            if (measures.stream().anyMatch(m -> m.verdict() == Verdict.HOLD)) {
                return Verdict.HOLD;
            }
            return measures.stream().allMatch(m -> m.verdict() == Verdict.PASS) ? Verdict.PASS : Verdict.BELOW;
        }
    }

    /**
     * The measures of RUBRIC §4, each on its own denominator. {@code labelled} may hold one label per review; a review
     * in both sets counts in both, by its set membership.
     */
    static Report report(List<FrameRow> frame, List<Labelled> labelled) {
        Set<String> random = new java.util.HashSet<>();
        randomSet(frame).forEach(r -> random.add(r.fingerprint()));
        Set<String> challenge = new java.util.HashSet<>();
        challengeSet(frame).forEach(r -> challenge.add(r.fingerprint()));
        Set<String> lowStar = new java.util.HashSet<>();
        lowStarSet(frame).forEach(r -> lowStar.add(r.fingerprint()));

        int unknown = 0;
        int randomN = 0;
        int raisedN = 0;
        int raisedTrue = 0;
        int highNotHarm = 0;
        int highNotHarmRaised = 0;
        int challengeN = 0;
        int challengePositive = 0;
        int challengeCaught = 0;
        int hidden = 0;
        int lowStarN = 0;
        int outsidePositive = 0;
        int outsideCaught = 0;
        int missedWithoutKnownWord = 0;
        Map<String, Integer> readings = new LinkedHashMap<>();
        for (Labelled l : labelled) {
            if (l.label() == Label.UNKNOWN) {
                unknown++;
                continue;
            }
            FrameRow row = l.row();
            boolean positive = SHOULD_RISE.contains(l.label());
            if (random.contains(row.fingerprint())) {
                randomN++;
                if (row.raised()) {
                    raisedN++;
                    raisedTrue += positive ? 1 : 0;
                }
                if (highRating(row.rating()) && NOT_HARM.contains(l.label())) {
                    highNotHarm++;
                    highNotHarmRaised += row.raised() ? 1 : 0;
                }
            }
            if (challenge.contains(row.fingerprint())) {
                challengeN++;
                if (positive) {
                    challengePositive++;
                    challengeCaught += row.raised() ? 1 : 0;
                }
                if (l.label() == Label.REPORT && row.outcome() == Outcome.NEGATED) {
                    hidden++;
                }
            }
            lowStarN += lowStar.contains(row.fingerprint()) ? 1 : 0;
            if (positive && (random.contains(row.fingerprint()) || lowStar.contains(row.fingerprint()))) {
                outsidePositive++;
                outsideCaught += row.raised() ? 1 : 0;
                missedWithoutKnownWord += row.outcome() == Outcome.NONE ? 1 : 0;
            }
            readings.merge(l.label().name(), 1, Integer::sum);
        }
        List<Measure> measures = new ArrayList<>();
        double recall = ratio(challengeCaught, challengePositive);
        measures.add(new Measure("위험 리뷰 탐지율 (도전 사례 · 아는 낱말이 든 리뷰)", recall, challengeCaught,
                challengePositive,
                "≥ " + MIN_CHALLENGE_RECALL, challengePositive < MIN_CHALLENGE_POSITIVES ? Verdict.HOLD
                        : recall >= MIN_CHALLENGE_RECALL ? Verdict.PASS : Verdict.BELOW));
        measures.add(new Measure("부정 필터가 지운 실제 신고 (도전 사례)", hidden, hidden, challengeN,
                "≤ " + MAX_HIDDEN_REPORTS, challengePositive < MIN_CHALLENGE_POSITIVES ? Verdict.HOLD
                        : hidden <= MAX_HIDDEN_REPORTS ? Verdict.PASS : Verdict.BELOW));
        double precision = ratio(raisedTrue, raisedN);
        measures.add(new Measure("경보 정밀도 (무작위)", precision, raisedTrue, raisedN,
                "≥ " + MIN_PRECISION + " (목표 " + TARGET_PRECISION + ")", raisedN < MIN_RAISED_FOR_PRECISION
                        ? Verdict.HOLD : precision >= MIN_PRECISION ? Verdict.PASS : Verdict.BELOW));
        double falseAlarm = ratio(highNotHarmRaised, highNotHarm);
        measures.add(new Measure("4–5★ 오탐률 (무작위, 위험 아닌 리뷰 중)", falseAlarm, highNotHarmRaised, highNotHarm,
                "≤ " + MAX_HIGH_RATING_FALSE_ALARM, highNotHarm < MIN_HIGH_RATING_NOT_HARM ? Verdict.HOLD
                        : falseAlarm <= MAX_HIGH_RATING_FALSE_ALARM ? Verdict.PASS : Verdict.BELOW));
        Measure outside = new Measure("낱말 목록 밖 탐지율 (무작위 + 낮은 별점)", ratio(outsideCaught, outsidePositive),
                outsideCaught, outsidePositive, "기준 미정 (오너 결정)",
                outsidePositive < MIN_OUTSIDE_POSITIVES ? Verdict.HOLD : Verdict.NO_BAR);
        return new Report(randomN, challengeN, unknown, List.copyOf(measures),
                randomN == 0 ? 0.0 : 100.0 * raisedN / randomN, EvalMetrics.wilsonLowerBound(raisedTrue, raisedN),
                raisedN == 0 ? 1.0 : 1.0 - EvalMetrics.wilsonLowerBound(raisedN - raisedTrue, raisedN), readings,
                lowStarN, outside, missedWithoutKnownWord);
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 0.0 : (double) part / whole;
    }

    /** Counts and rates only — no body, no id, no fingerprint. */
    static String render(Report r) {
        StringBuilder out = new StringBuilder("safety signal evaluation (contracts/review-eval/safety/v1)\n");
        out.append(String.format("  labelled: random %d · challenge %d · low-star %d · 판단 불가 %d · by label %s%n",
                r.randomLabelled(), r.challengeLabelled(), r.lowStarLabelled(), r.unknown(), r.readings()));
        for (Measure m : r.measures()) {
            out.append(line(m));
        }
        out.append("  참고 (전체 판정에 넣지 않음):\n").append(line(r.outside()));
        out.append(String.format("    놓친 %d건 중 아는 낱말이 전혀 없는 리뷰 %d건%n",
                r.outside().denominator() - r.outside().numerator(), r.missedWithoutKnownWord()));
        out.append(String.format("  경보 정밀도 95%% 구간(참고) %.3f – %.3f · 무작위 100건당 경보 %.1f건%n", r.precisionLow(),
                r.precisionHigh(), r.alarmsPerHundred()));
        out.append("  overall: ").append(switch (r.overall()) {
            case PASS -> "통과 — 파일럿 기준 충족 (탐지율은 아는 낱말이 든 리뷰 기준)";
            case BELOW -> "미달 — 범위를 좁혀 운영하거나 신호를 고친다";
            case HOLD -> "판단 보류 — 표본이 기준에 못 미친다";
            case NO_BAR -> throw new IllegalStateException("every pilot measure has a bar");
        }).append('\n');
        return out.toString();
    }

    private static String line(Measure m) {
        return String.format("  %-28s %.3f (%d/%d) bar %s → %s%n", m.name(), m.value(), m.numerator(), m.denominator(),
                m.bar(), switch (m.verdict()) {
                    case PASS -> "통과";
                    case BELOW -> "미달";
                    case HOLD -> "판단 보류 (표본 부족)";
                    case NO_BAR -> "측정만 — 기준 미정";
                });
    }

    private SafetySample() {
    }
}
