package com.sellerops.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.coverage.CoveredWindow.CoverageSource;
import com.sellerops.coverage.ReviewCatchUpPlan.Limits;
import com.sellerops.coverage.ReviewCatchUpPlan.Stop;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>이력 계획 — 기록된 시작일까지, 최신부터, 31일씩 (2026-10-13 제품 책임자 결정).</b>
 *
 * <p>앞쪽 공백(경계 뒤, 오래된 것부터)이 먼저 오고, 그 뒤에 이력(경계 앞, 최신부터)이 온다. 이력 시작일이 없으면
 * 계획은 예전과 바이트 하나 다르지 않다.
 */
class ReviewHistoryPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final Limits WIDE = new Limits(24, 12_000, Duration.ofMinutes(20), 7);

    private static CoveredWindow w(String start, String end) {
        return new CoveredWindow(LocalDate.parse(start), LocalDate.parse(end), CoverageSource.SCREEN_READ);
    }

    private static List<String> shown(ReviewCatchUpPlan plan) {
        return plan.windows().stream().map(c -> c.start() + "~" + c.end()).toList();
    }

    @Test
    @DisplayName("경계가 없어도 기록된 시작일이 있으면 계획이 있다 — 어제부터 거꾸로, 31일씩, 시작일에서 멈춘다")
    void noBoundaryWithHistoryPlansNewestFirst() {
        LocalDate from = TODAY.minusYears(1);
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(ReviewCoverage.NONE, TODAY, WIDE, from, 31);

        assertThat(plan.stopped()).isEqualTo(Stop.COMPLETE);
        assertThat(plan.remaining()).isZero();
        List<CoveredWindow> ws = plan.windows();
        // 최신부터: 첫 창은 어제로 끝나고, 31일이다.
        assertThat(ws.get(0).end()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(ws.get(0).days()).isEqualTo(31);
        // 마지막 창은 정확히 기록된 시작일에서 시작한다(짧을 수 있다).
        assertThat(ws.get(ws.size() - 1).start()).isEqualTo(from);
        // 창들이 빈틈도 겹침도 없이 이어진다 — 각 창은 다음(더 오래된) 창 바로 뒷날에 시작한다.
        for (int i = 1; i < ws.size(); i++) {
            assertThat(ws.get(i).end()).isEqualTo(ws.get(i - 1).start().minusDays(1));
            assertThat(ws.get(i).days()).isLessThanOrEqualTo(31);
        }
        long total = ws.stream().mapToLong(CoveredWindow::days).sum();
        assertThat(total).isEqualTo(LocalDate.parse("2026-10-12").toEpochDay() - from.toEpochDay() + 1);
        assertThat(ws).hasSize(12);
    }

    @Test
    @DisplayName("시작일이 없으면 예전 계획 그대로 — NO_BOUNDARY")
    void noHistoryIsTheOldPlan() {
        assertThat(ReviewCatchUpPlan.from(ReviewCoverage.NONE, TODAY, WIDE, null, 31).stopped())
                .isEqualTo(Stop.NO_BOUNDARY);
        ReviewCoverage held = ReviewCoverage.of(List.of(w("2026-09-01", "2026-10-01")), TODAY);
        assertThat(ReviewCatchUpPlan.from(held, TODAY, WIDE, null, 31))
                .isEqualTo(ReviewCatchUpPlan.from(held, TODAY, WIDE));
    }

    @Test
    @DisplayName("앞쪽 공백이 먼저(오래된 것부터), 그다음 경계 앞 이력(최신부터) — 덮인 날은 다시 읽지 않는다")
    void forwardGapsThenHistory() {
        // 덮인 구간 09-01 … 10-05 → 앞쪽 공백 10-06 … 10-12, 이력 2026-07-01 … 08-31.
        ReviewCoverage held = ReviewCoverage.of(List.of(w("2026-09-01", "2026-10-05")), TODAY);
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(held, TODAY, WIDE, LocalDate.parse("2026-07-01"), 31);

        assertThat(shown(plan)).containsExactly(
                "2026-10-06~2026-10-12",
                "2026-08-01~2026-08-31",
                "2026-07-01~2026-07-31");
        for (CoveredWindow c : plan.windows()) {
            for (LocalDate d = c.start(); !d.isAfter(c.end()); d = d.plusDays(1)) {
                assertThat(held.covers(d)).as("덮인 날 %s 를 다시 계획했다", d).isFalse();
            }
        }
        assertThat(ReviewCatchUpPlan.historySpan(held, TODAY, LocalDate.parse("2026-07-01")))
                .contains(w("2026-07-01", "2026-08-31"));
    }

    @Test
    @DisplayName("시작일이 덮인 구간 안이나 뒤라면 이력 구간은 없다")
    void historyFromAfterCoverageFromIsNoHistory() {
        ReviewCoverage held = ReviewCoverage.of(List.of(w("2026-09-01", "2026-10-12")), TODAY);
        assertThat(ReviewCatchUpPlan.historySpan(held, TODAY, LocalDate.parse("2026-09-01"))).isEmpty();
        assertThat(ReviewCatchUpPlan.historySpan(held, TODAY, LocalDate.parse("2026-09-20"))).isEmpty();
        assertThat(ReviewCatchUpPlan.from(held, TODAY, WIDE, LocalDate.parse("2026-09-20"), 31).stopped())
                .isEqualTo(Stop.NOTHING_TO_DO);
        // 하루 앞이면 그 하루가 이력이다.
        assertThat(ReviewCatchUpPlan.historySpan(held, TODAY, LocalDate.parse("2026-08-31")))
                .contains(w("2026-08-31", "2026-08-31"));
        // 오늘 이후의 시작일은 이력이 아니다.
        assertThat(ReviewCatchUpPlan.historySpan(ReviewCoverage.NONE, TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("창 상한은 두 구간이 함께 쓴다 — 남은 날은 남았다고 말한다")
    void windowBudgetIsShared() {
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(ReviewCoverage.NONE, TODAY,
                new Limits(3, 12_000, Duration.ofMinutes(20), 7), TODAY.minusYears(1), 31);
        assertThat(plan.windows()).hasSize(3);
        assertThat(plan.stopped()).isEqualTo(Stop.MAX_WINDOWS);
        long planned = plan.windows().stream().mapToLong(CoveredWindow::days).sum();
        long span = LocalDate.parse("2026-10-12").toEpochDay() - TODAY.minusYears(1).toEpochDay() + 1;
        assertThat(plan.remaining()).isEqualTo(span - planned);
    }
}
