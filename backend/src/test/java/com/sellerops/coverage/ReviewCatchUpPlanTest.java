package com.sellerops.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.coverage.CoveredWindow.CoverageSource;
import com.sellerops.coverage.ReviewCatchUpPlan.Limits;
import com.sellerops.coverage.ReviewCatchUpPlan.Stop;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>오래된 쪽부터, 그리고 한 번의 누름이 어디서 멈추는지.</b>
 *
 * <p>이 계획이 「최근 7일」부터 시작하면 안 되는 이유는 간단하다 — 최근 7일은 이미 읽었다. 공백은 뒤에 있고,
 * 뒤에서부터 메우지 않으면 영원히 같은 일주일만 다시 읽는다.
 */
class ReviewCatchUpPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private static CoveredWindow w(String start, String end) {
        return new CoveredWindow(LocalDate.parse(start), LocalDate.parse(end), CoverageSource.GUIDED_IMPORT);
    }

    private static ReviewCoverage thisStore() {
        return ReviewCoverage.of(List.of(w("2026-07-01", "2026-09-02"),
                new CoveredWindow(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-08"),
                        CoverageSource.SCREEN_READ)), TODAY);
    }

    @Test
    @DisplayName("이 조직의 실제 계획: 9/3부터 7일씩, 10-01에서 이미 읽은 섬에 닿아 멈춘다")
    void theDryPlanForThisStore() {
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(thisStore(), TODAY, Limits.OPERATOR_PRESS);

        assertThat(plan.windows()).extracting(c -> c.start() + "~" + c.end()).containsExactly(
                "2026-09-03~2026-09-09",
                "2026-09-10~2026-09-16",
                "2026-09-17~2026-09-23",
                "2026-09-24~2026-09-30",
                // 마지막 창은 하루다 — 10-02부터는 이미 읽었으므로, 그 안으로 넘어가지 않게 깎인다.
                "2026-10-01~2026-10-01");
        assertThat(plan.stopped()).isEqualTo(Stop.COMPLETE);
        assertThat(plan.remaining()).isZero();
    }

    @Test
    @DisplayName("이미 읽은 기간은 다시 읽지 않는다")
    void verifiedDaysAreSteppedOver() {
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(thisStore(), TODAY, Limits.OPERATOR_PRESS);
        for (CoveredWindow window : plan.windows()) {
            for (LocalDate d = window.start(); !d.isAfter(window.end()); d = d.plusDays(1)) {
                assertThat(thisStore().covers(d)).as("%s", d).isFalse();
            }
        }
    }

    @Test
    @DisplayName("한 번의 누름은 유한하다 — 창 수 상한에서 멈추고, 남은 날수를 말한다")
    void onePressIsBounded() {
        ReviewCoverage wideGap = ReviewCoverage.of(List.of(w("2026-01-01", "2026-01-31")), TODAY);
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(wideGap, TODAY, new Limits(3, 2_000, Duration.ofMinutes(4), 7));

        assertThat(plan.windows()).hasSize(3);
        assertThat(plan.windows().get(0).start()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(plan.stopped()).isEqualTo(Stop.MAX_WINDOWS);
        // 2/22부터 10/8까지 — 끝나지 않았다는 사실이 숫자로 남는다.
        assertThat(plan.remaining()).isEqualTo(LocalDate.of(2026, 10, 8).toEpochDay()
                - LocalDate.of(2026, 2, 22).toEpochDay() + 1);
    }

    @Test
    @DisplayName("경계가 없으면 계획하지 않는다 — 추측한 시작은 계획이 아니다")
    void noBoundaryIsNoPlan() {
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(ReviewCoverage.NONE, TODAY, Limits.OPERATOR_PRESS);
        assertThat(plan.windows()).isEmpty();
        assertThat(plan.stopped()).isEqualTo(Stop.NO_BOUNDARY);
    }

    @Test
    @DisplayName("오늘까지 메워져 있으면 할 일이 없다")
    void nothingToDo() {
        ReviewCoverage full = ReviewCoverage.of(List.of(w("2026-09-01", "2026-10-08")), TODAY);
        assertThat(ReviewCatchUpPlan.from(full, TODAY, Limits.OPERATOR_PRESS).stopped())
                .isEqualTo(Stop.NOTHING_TO_DO);
    }

    @Test
    @DisplayName("포화된 창은 반으로 쪼개고, 하루는 더 쪼갤 수 없다")
    void adaptiveSplit() {
        CoveredWindow week = w("2026-09-03", "2026-09-09");
        assertThat(ReviewCatchUpPlan.split(week)).extracting(c -> c.start() + "~" + c.end())
                .containsExactly("2026-09-03~2026-09-05", "2026-09-06~2026-09-09");
        // 쪼갠 두 조각은 원본을 정확히 덮는다 — 쪼개다 하루를 잃으면 그게 바로 조용한 데이터 손실이다.
        assertThat(ReviewCatchUpPlan.split(week).stream().mapToLong(CoveredWindow::days).sum())
                .isEqualTo(week.days());
        // 하루가 포화됐다는 것은 recipe의 한계이고, 같은 하루를 다시 쪼개는 루프가 아니다.
        assertThat(ReviewCatchUpPlan.split(w("2026-09-03", "2026-09-03"))).isEmpty();
    }

    @Test
    @DisplayName("상한이 아닌 값은 상한이 아니다")
    void limitsRefuseNonsense() {
        assertThatThrownBy(() -> new Limits(0, 10, Duration.ofMinutes(1), 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(1, 10, Duration.ZERO, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(1, 10, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
