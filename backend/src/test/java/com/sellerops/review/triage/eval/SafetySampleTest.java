package com.sellerops.review.triage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.triage.ReviewSafetySignal.Outcome;
import com.sellerops.review.triage.eval.SafetySample.FrameRow;
import com.sellerops.review.triage.eval.SafetySample.Label;
import com.sellerops.review.triage.eval.SafetySample.Labelled;
import com.sellerops.review.triage.eval.SafetySample.Measure;
import com.sellerops.review.triage.eval.SafetySample.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** The safety set's draws and arithmetic, on synthetic rows — {@code contracts/review-eval/safety/v1}. */
class SafetySampleTest {

    private static FrameRow row(String id, int rating, Outcome outcome) {
        return new FrameRow(String.format("%064x", id.hashCode() & 0xffffffffL), rating, outcome);
    }

    private static Measure measure(SafetySample.Report r, String prefix) {
        return r.measures().stream().filter(m -> m.name().startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void theOutcomeIsWhatTheSignalDoesToday() {
        assertThat(SafetySample.row("a", 5, "사용하다 화상을 입었어요").outcome()).isEqualTo(Outcome.RAISED);
        assertThat(SafetySample.row("b", 5, "화상 걱정 없어요").outcome()).isEqualTo(Outcome.NEGATED);
        assertThat(SafetySample.row("c", 5, "알레르기 있는 아이도 잘 써요").outcome()).isEqualTo(Outcome.NO_CUE);
        assertThat(SafetySample.row("d", 5, "배송이 빨라요").outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    void theRandomSetKeepsTheRealMixAndTheChallengeSetOnlyHarmWords() {
        List<FrameRow> frame = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            frame.add(row("r" + i, 5, i % 10 == 0 ? Outcome.RAISED : Outcome.NONE));
        }
        List<FrameRow> random = SafetySample.randomSet(frame);
        assertThat(random).hasSize(400);
        List<FrameRow> reversed = new ArrayList<>(frame);
        Collections.reverse(reversed);
        assertThat(SafetySample.randomSet(reversed)).as("reproducible").isEqualTo(random);
        assertThat(SafetySample.challengeSet(frame)).hasSize(100).allMatch(FrameRow::challenge);
    }

    @Test
    void precisionIsMeasuredOnTheRandomSetOnlyAndHeldBelowItsSample() {
        List<FrameRow> frame = new ArrayList<>();
        List<Labelled> labelled = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            FrameRow r = row("p" + i, 2, i < 10 ? Outcome.RAISED : Outcome.NONE);
            frame.add(r);
            labelled.add(new Labelled(r, i < 6 ? Label.REPORT : Label.UNRELATED));
        }
        SafetySample.Report r = SafetySample.report(frame, labelled);
        Measure precision = measure(r, "경보 정밀도");
        assertThat(precision.value()).isCloseTo(0.6, Offset.offset(1e-9));
        assertThat(precision.verdict()).as("10 raised is under the 20 a verdict needs").isEqualTo(Verdict.HOLD);
        assertThat(r.alarmsPerHundred()).isCloseTo(2.5, Offset.offset(1e-9));
        assertThat(r.overall()).isEqualTo(Verdict.HOLD);
    }

    @Test
    void precisionNeverCountsRaisedReviewsThatOnlyTheChallengeSetDrew() {
        List<FrameRow> frame = new ArrayList<>();
        List<Labelled> labelled = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            FrameRow r = row("m" + i, 3, i < 100 ? Outcome.RAISED : Outcome.NONE);
            frame.add(r);
            labelled.add(new Labelled(r, i < 100 && i % 2 == 0 ? Label.REPORT : Label.UNRELATED));
        }
        long raisedInRandom = SafetySample.randomSet(frame).stream().filter(FrameRow::raised).count();
        Measure precision = measure(SafetySample.report(frame, labelled), "경보 정밀도");
        assertThat(raisedInRandom).as("the random set draws only some of the 100").isLessThan(100);
        assertThat(precision.denominator()).isEqualTo((int) raisedInRandom);
    }

    @Test
    void theFourAndFiveStarRateCountsOnlyReviewsThatAreNotHarm() {
        List<FrameRow> frame = new ArrayList<>();
        List<Labelled> labelled = new ArrayList<>();
        // 120 happy non-harm reviews, 3 of them raised; 30 real reports, all raised. The reports must not dilute or
        // inflate the rate: 3 / 120.
        for (int i = 0; i < 120; i++) {
            FrameRow r = row("h" + i, 5, i < 3 ? Outcome.RAISED : Outcome.NONE);
            frame.add(r);
            labelled.add(new Labelled(r, Label.UNRELATED));
        }
        for (int i = 0; i < 30; i++) {
            FrameRow r = row("x" + i, 5, Outcome.RAISED);
            frame.add(r);
            labelled.add(new Labelled(r, Label.REPORT));
        }
        Measure rate = measure(SafetySample.report(frame, labelled), "4–5★");
        assertThat(rate.numerator()).isEqualTo(3);
        assertThat(rate.denominator()).isEqualTo(120);
        assertThat(rate.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void recallAndHiddenReportsComeFromTheChallengeSet() {
        List<FrameRow> frame = new ArrayList<>();
        List<Labelled> labelled = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Outcome o = i < 36 ? Outcome.RAISED : i < 38 ? Outcome.NEGATED : Outcome.NO_CUE;
            FrameRow r = row("c" + i, 1, o);
            frame.add(r);
            labelled.add(new Labelled(r, i < 38 ? Label.REPORT : Label.WORRY));
        }
        FrameRow unsure = row("u", 3, Outcome.RAISED);
        frame.add(unsure);
        labelled.add(new Labelled(unsure, Label.UNKNOWN));
        SafetySample.Report r = SafetySample.report(frame, labelled);
        Measure recall = measure(r, "위험 리뷰 탐지율");
        assertThat(recall.value()).isCloseTo(36.0 / 40, Offset.offset(1e-9));
        assertThat(recall.verdict()).isEqualTo(Verdict.PASS);
        Measure hidden = measure(r, "부정 필터");
        assertThat(hidden.numerator()).isEqualTo(2);
        assertThat(hidden.verdict()).isEqualTo(Verdict.BELOW);
        assertThat(r.unknown()).isEqualTo(1);
        assertThat(SafetySample.render(r)).contains("미달").doesNotContain(unsure.fingerprint());
    }

    @Test
    void theSheetsQuotingRoundTripsCommasQuotesAndLineBreaks() {
        String body = "뜨거워요, \"정말\"\n화상 입었어요";
        String csv = "a,5,RANDOM,REPORT,OWNER,Y," + SafetySignalEvalIT.quote(body) + "\n";
        assertThat(SafetySignalEvalIT.parse(csv))
                .containsExactly(List.of("a", "5", "RANDOM", "REPORT", "OWNER", "Y", body));
    }
}
