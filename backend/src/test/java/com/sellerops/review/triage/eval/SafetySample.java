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
 * <p>Two sets with two jobs, never mixed (owner decision 2026-10-23): the <b>random</b> set keeps the real mix of
 * reviews and says how often an alarm is needless; the <b>challenge</b> set gathers every review that mentions a
 * harm word and says what is missed. A precision measured on the challenge set would say nothing about operation.
 */
final class SafetySample {

    /** The labels of RUBRIC §1. {@code UNKNOWN} is excluded from every rate and reported on its own. */
    enum Label { REPORT, WORRY, REASSURANCE, UNRELATED, UNKNOWN }

    static final Set<Label> SHOULD_RISE = Set.of(Label.REPORT, Label.WORRY);
    static final Set<Label> NOT_HARM = Set.of(Label.REASSURANCE, Label.UNRELATED);

    static final int RANDOM_SIZE = 400;
    static final int CHALLENGE_SIZE = 300;

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

    /** What one measure concluded. */
    enum Verdict { PASS, BELOW, HOLD }

    record Measure(String name, double value, int numerator, int denominator, String bar, Verdict verdict) {}

    record Report(int randomLabelled, int challengeLabelled, int unknown, List<Measure> measures,
                  double alarmsPerHundred, double precisionLow, double precisionHigh, Map<String, Integer> readings) {

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
            readings.merge(l.label().name(), 1, Integer::sum);
        }
        List<Measure> measures = new ArrayList<>();
        double recall = ratio(challengeCaught, challengePositive);
        measures.add(new Measure("위험 리뷰 탐지율 (도전 사례)", recall, challengeCaught, challengePositive,
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
        return new Report(randomN, challengeN, unknown, List.copyOf(measures),
                randomN == 0 ? 0.0 : 100.0 * raisedN / randomN, EvalMetrics.wilsonLowerBound(raisedTrue, raisedN),
                raisedN == 0 ? 1.0 : 1.0 - EvalMetrics.wilsonLowerBound(raisedN - raisedTrue, raisedN), readings);
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 0.0 : (double) part / whole;
    }

    /** Counts and rates only — no body, no id, no fingerprint. */
    static String render(Report r) {
        StringBuilder out = new StringBuilder("safety signal evaluation (contracts/review-eval/safety/v1)\n");
        out.append(String.format("  labelled: random %d · challenge %d · 판단 불가 %d · by label %s%n", r.randomLabelled(),
                r.challengeLabelled(), r.unknown(), r.readings()));
        for (Measure m : r.measures()) {
            out.append(String.format("  %-28s %.3f (%d/%d) bar %s → %s%n", m.name(), m.value(), m.numerator(),
                    m.denominator(), m.bar(), switch (m.verdict()) {
                        case PASS -> "통과";
                        case BELOW -> "미달";
                        case HOLD -> "판단 보류 (표본 부족)";
                    }));
        }
        out.append(String.format("  경보 정밀도 95%% 구간(참고) %.3f – %.3f · 무작위 100건당 경보 %.1f건%n", r.precisionLow(),
                r.precisionHigh(), r.alarmsPerHundred()));
        out.append("  overall: ").append(switch (r.overall()) {
            case PASS -> "통과 — 파일럿 기준 충족";
            case BELOW -> "미달 — 범위를 좁혀 운영하거나 신호를 고친다";
            case HOLD -> "판단 보류 — 표본이 기준에 못 미친다";
        }).append('\n');
        return out.toString();
    }

    private SafetySample() {
    }
}
