package com.sellerops.review.triage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.triage.eval.SafetySample.FrameRow;
import com.sellerops.review.triage.eval.SafetySample.Label;
import com.sellerops.review.triage.eval.SafetySample.Labelled;
import com.sellerops.review.triage.eval.SafetySample.Stratum;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The safety set's draw and arithmetic, on synthetic rows — {@code contracts/review-eval/safety/v1}. */
class SafetySampleTest {

    @Test
    void theStrataAreWhatTheSignalDoesToday() {
        assertThat(SafetySample.stratumOf("사용하다 화상을 입었어요")).isEqualTo(Stratum.RAISED);
        assertThat(SafetySample.stratumOf("화상 걱정 없어요")).isEqualTo(Stratum.FILTERED);
        assertThat(SafetySample.stratumOf("배송이 빨라요")).isEqualTo(Stratum.OTHER);
    }

    @Test
    void theDrawIsReproducibleAndHonoursEachQuota() {
        List<FrameRow> frame = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            frame.add(new FrameRow(String.format("%064x", i), 5, i % 2 == 0 ? Stratum.RAISED : Stratum.OTHER));
        }
        List<FrameRow> once = SafetySample.draw(frame);
        List<FrameRow> reversed = new ArrayList<>(frame);
        java.util.Collections.reverse(reversed);
        assertThat(SafetySample.draw(reversed)).isEqualTo(once);
        assertThat(once.stream().filter(r -> r.stratum() == Stratum.RAISED)).hasSize(150);
        assertThat(once.stream().filter(r -> r.stratum() == Stratum.OTHER)).hasSize(200);
    }

    @Test
    void recallIsWeightedByStratumSizeAndUncertainCountsNowhere() {
        // Frame: 100 raised, 1,000 other. Labelled at different rates: 10 raised (8 should rise), 50 other (1 should
        // rise). Unweighted the miss would look like 1 in 9; weighted it is 20 in 100.
        List<FrameRow> frame = new ArrayList<>();
        List<Labelled> labelled = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            FrameRow row = new FrameRow("r" + i, 2, Stratum.RAISED);
            frame.add(row);
            if (i < 10) {
                labelled.add(new Labelled(row, i < 8 ? Label.REPORT : Label.BACKGROUND));
            }
        }
        for (int i = 0; i < 1000; i++) {
            FrameRow row = new FrameRow("o" + i, 5, Stratum.OTHER);
            frame.add(row);
            if (i < 50) {
                labelled.add(new Labelled(row, i < 1 ? Label.WORRY : Label.UNRELATED));
            }
        }
        labelled.add(new Labelled(frame.get(50), Label.UNCERTAIN));

        SafetySample.Report r = SafetySample.report(frame, labelled);

        // Raised: 100 × 8/10 = 80 caught. Other: 1,000 × 1/50 = 20 missed. Recall 80 / 100.
        assertThat(r.recall()).isCloseTo(0.80, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.precision()).isCloseTo(0.80, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.precisionLowerBound()).isLessThan(0.80);
        assertThat(r.uncertain()).isEqualTo(1);
        assertThat(r.positives()).isEqualTo(9);
        assertThat(r.adequate()).as("9 should-rise labels is under the floor of 30").isFalse();
        assertThat(SafetySample.render(r)).contains("verdict: none").doesNotContain("r50").doesNotContain("o1");
    }

    @Test
    void aReportTheFilterCancelledIsCountedAsHidden() {
        FrameRow filtered = new FrameRow("f", 1, Stratum.FILTERED);
        SafetySample.Report r = SafetySample.report(List.of(filtered),
                List.of(new Labelled(filtered, Label.REPORT)));
        assertThat(r.hiddenReports()).isEqualTo(1);
    }

    @Test
    void aHappyCustomerRaisedIsAnAlarmOnTheFourAndFiveStarRate() {
        List<FrameRow> frame = new ArrayList<>();
        List<Labelled> labelled = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            FrameRow row = new FrameRow("h" + i, 5, Stratum.RAISED);
            frame.add(row);
            labelled.add(new Labelled(row, i < 1 ? Label.BACKGROUND : Label.REPORT));
        }
        for (int i = 0; i < 80; i++) {
            FrameRow row = new FrameRow("u" + i, 5, Stratum.OTHER);
            frame.add(row);
            labelled.add(new Labelled(row, Label.UNRELATED));
        }
        // 1 raised happy review out of 81 happy ones.
        assertThat(SafetySample.report(frame, labelled).highRatingAlarmRate())
                .isCloseTo(1.0 / 81, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void theSheetsQuotingRoundTripsCommasQuotesAndLineBreaks() {
        String body = "뜨거워요, \"정말\"\n화상 입었어요";
        String csv = "a,5,RAISED,REPORT," + SafetySignalEvalIT.quote(body) + "\n";
        assertThat(SafetySignalEvalIT.parse(csv)).containsExactly(List.of("a", "5", "RAISED", "REPORT", body));
    }
}
