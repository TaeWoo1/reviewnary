package com.sellerops.review.triage.eval;

import com.sellerops.itemanalysis.eval.EvalMetrics;
import com.sellerops.review.triage.ReviewSafetySignal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The safety evaluation set's draw and arithmetic</b> — {@code contracts/review-eval/safety/v1/RUBRIC.md}. Pure,
 * so the numbers the gated runs print are the numbers {@code SafetySampleTest} checks.
 */
final class SafetySample {

    enum Stratum {
        /** The signal raises it. */
        RAISED(150),
        /** A term matched, and a negation or reassurance cancelled it. */
        FILTERED(80),
        /** No term matched. */
        OTHER(200);

        final int quota;

        Stratum(int quota) {
            this.quota = quota;
        }
    }

    enum Label { REPORT, WORRY, REASSURANCE, BACKGROUND, UNRELATED, UNCERTAIN }

    static final Set<Label> SHOULD_RISE = Set.of(Label.REPORT, Label.WORRY);

    /** The proposed bars of RUBRIC §5. The owner fixes them before any label is read. */
    static final double MIN_RECALL = 0.80;
    static final int MAX_HIDDEN_REPORTS = 0;
    static final double MIN_PRECISION_LOWER_BOUND = 0.50;
    static final double MAX_HIGH_RATING_ALARM_RATE = 0.05;

    /** The adequacy floor of RUBRIC §5. */
    static final int MIN_POSITIVES = 30;
    static final int MIN_OTHER = 100;
    static final int MIN_HIGH_RATING_NEGATIVES = 60;

    record FrameRow(String fingerprint, Integer rating, Stratum stratum) {}

    record Labelled(FrameRow row, Label label) {}

    static Stratum stratumOf(String body) {
        if (ReviewSafetySignal.in(body)) {
            return Stratum.RAISED;
        }
        return ReviewSafetySignal.mentions(body) ? Stratum.FILTERED : Stratum.OTHER;
    }

    static boolean highRating(Integer rating) {
        return rating != null && rating >= 4;
    }

    /** The reproducible draw: each stratum's first {@code quota} rows by sample order. */
    static List<FrameRow> draw(List<FrameRow> frame) {
        List<FrameRow> out = new ArrayList<>();
        for (Stratum s : Stratum.values()) {
            frame.stream().filter(r -> r.stratum() == s)
                    .sorted(Comparator.comparing(r -> CalibrationSample.sampleOrderKey(r.fingerprint())))
                    .limit(s.quota).forEach(out::add);
        }
        return out;
    }

    record Report(Map<Stratum, Integer> frameSize, Map<Stratum, Integer> labelled, int uncertain, int positives,
                  int highRatingNegatives, double recall, int hiddenReports, int raisedLabelled, int raisedPositive,
                  double precision, double precisionLowerBound, double highRatingAlarmRate, boolean adequate) {

        boolean passes() {
            return adequate && recall >= MIN_RECALL && hiddenReports <= MAX_HIDDEN_REPORTS
                    && precisionLowerBound >= MIN_PRECISION_LOWER_BOUND
                    && highRatingAlarmRate <= MAX_HIGH_RATING_ALARM_RATE;
        }
    }

    /**
     * The metrics of RUBRIC §4 from the frame and the labelled rows. Population estimates weight each stratum by
     * its frame size over its labelled count; {@code UNCERTAIN} is left out of every rate.
     */
    static Report report(List<FrameRow> frame, List<Labelled> labelled) {
        Map<Stratum, Integer> size = new EnumMap<>(Stratum.class);
        Map<Stratum, Integer> sizeHigh = new EnumMap<>(Stratum.class);
        for (Stratum s : Stratum.values()) {
            size.put(s, (int) frame.stream().filter(r -> r.stratum() == s).count());
            sizeHigh.put(s, (int) frame.stream().filter(r -> r.stratum() == s && highRating(r.rating())).count());
        }
        Map<Stratum, Integer> n = new EnumMap<>(Stratum.class);
        Map<Stratum, Integer> pos = new EnumMap<>(Stratum.class);
        Map<Stratum, Integer> nHigh = new EnumMap<>(Stratum.class);
        Map<Stratum, Integer> negHigh = new EnumMap<>(Stratum.class);
        int uncertain = 0;
        int hidden = 0;
        for (Stratum s : Stratum.values()) {
            n.put(s, 0);
            pos.put(s, 0);
            nHigh.put(s, 0);
            negHigh.put(s, 0);
        }
        for (Labelled l : labelled) {
            if (l.label() == Label.UNCERTAIN) {
                uncertain++;
                continue;
            }
            Stratum s = l.row().stratum();
            n.merge(s, 1, Integer::sum);
            boolean positive = SHOULD_RISE.contains(l.label());
            if (positive) {
                pos.merge(s, 1, Integer::sum);
            }
            if (s == Stratum.FILTERED && l.label() == Label.REPORT) {
                hidden++;
            }
            if (highRating(l.row().rating())) {
                nHigh.merge(s, 1, Integer::sum);
                if (!positive) {
                    negHigh.merge(s, 1, Integer::sum);
                }
            }
        }
        double estPositives = 0;
        double estHighNegatives = 0;
        for (Stratum s : Stratum.values()) {
            estPositives += weighted(size.get(s), pos.get(s), n.get(s));
            estHighNegatives += weighted(sizeHigh.get(s), negHigh.get(s), nHigh.get(s));
        }
        double caught = weighted(size.get(Stratum.RAISED), pos.get(Stratum.RAISED), n.get(Stratum.RAISED));
        double raisedHighNegatives = weighted(sizeHigh.get(Stratum.RAISED), negHigh.get(Stratum.RAISED),
                nHigh.get(Stratum.RAISED));
        int positives = pos.values().stream().mapToInt(Integer::intValue).sum();
        int highNegatives = negHigh.values().stream().mapToInt(Integer::intValue).sum();
        int raisedN = n.get(Stratum.RAISED);
        int raisedPos = pos.get(Stratum.RAISED);
        boolean adequate = positives >= MIN_POSITIVES && n.get(Stratum.OTHER) >= MIN_OTHER
                && highNegatives >= MIN_HIGH_RATING_NEGATIVES;
        return new Report(size, n, uncertain, positives, highNegatives,
                estPositives == 0 ? 0.0 : caught / estPositives, hidden, raisedN, raisedPos,
                raisedN == 0 ? 0.0 : (double) raisedPos / raisedN, EvalMetrics.wilsonLowerBound(raisedPos, raisedN),
                estHighNegatives == 0 ? 0.0 : raisedHighNegatives / estHighNegatives, adequate);
    }

    /** A stratum's estimated count of something: its frame size times the labelled share. */
    private static double weighted(int frameSize, int hits, int labelled) {
        return labelled == 0 ? 0.0 : (double) frameSize * hits / labelled;
    }

    /** Counts and rates only — no body, no id, no fingerprint. */
    static String render(Report r) {
        StringBuilder out = new StringBuilder("safety signal evaluation (contracts/review-eval/safety/v1)\n");
        for (Stratum s : Stratum.values()) {
            out.append(String.format("  %-9s frame %6d  labelled %4d%n", s, r.frameSize().get(s), r.labelled().get(s)));
        }
        out.append(String.format("  uncertain %d · should-rise labelled %d · 4–5★ non-harm labelled %d%n",
                r.uncertain(), r.positives(), r.highRatingNegatives()));
        out.append(String.format("  missed harm   recall %.3f (bar ≥ %.2f)%n", r.recall(), MIN_RECALL));
        out.append(String.format("  hidden reports %d (bar ≤ %d)%n", r.hiddenReports(), MAX_HIDDEN_REPORTS));
        out.append(String.format("  needless alarms  raised precision %.3f, Wilson low %.3f (bar ≥ %.2f) on %d%n",
                r.precision(), r.precisionLowerBound(), MIN_PRECISION_LOWER_BOUND, r.raisedLabelled()));
        out.append(String.format("  needless alarms  4–5★ raised share %.3f (bar ≤ %.2f)%n", r.highRatingAlarmRate(),
                MAX_HIGH_RATING_ALARM_RATE));
        out.append(r.adequate() ? (r.passes() ? "  verdict: PASS — the exception may end\n"
                : "  verdict: BELOW A BAR — narrow the operation or fix the signal\n")
                : "  verdict: none — below the adequacy floor of RUBRIC §5\n");
        return out.toString();
    }

    private SafetySample() {
    }
}
