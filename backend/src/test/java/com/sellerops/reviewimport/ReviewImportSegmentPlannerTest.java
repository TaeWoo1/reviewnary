package com.sellerops.reviewimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.reviewimport.ReviewImportSegmentPlanner.DateRange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One window per requested period, at most a year, is the plan shape (2026-10-13), so its edges are pinned:
 * a period of a year or less is ONE export whatever months it crosses, a longer one tiles into consecutive
 * one-year windows with a partial last one, adjacent windows never overlap by a day, and a missing/inverted
 * range fails closed rather than planning an unbounded sweep. No NAVER row cap appears here — whether one
 * export came back whole is measured at ingest, not presumed from a small window.
 */
class ReviewImportSegmentPlannerTest {

    private static DateRange r(String start, String end) {
        return new DateRange(LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    void aPeriodCrossingSeveralMonthsIsOneExport() {
        assertThat(ReviewImportSegmentPlanner.segments(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30")))
                .containsExactly(r("2026-04-01", "2026-06-30"));
        assertThat(ReviewImportSegmentPlanner.segments(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-03-10")))
                .containsExactly(r("2026-01-15", "2026-03-10"));
    }

    @Test
    void exactlyOneYearIsStillOneExport() {
        assertThat(ReviewImportSegmentPlanner.segments(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
                .containsExactly(r("2026-01-01", "2026-12-31"));
        assertThat(ReviewImportSegmentPlanner.segments(LocalDate.parse("2025-10-13"), LocalDate.parse("2026-10-12")))
                .containsExactly(r("2025-10-13", "2026-10-12"));
    }

    @Test
    void oneDayMoreThanAYearBecomesTwoWindows() {
        assertThat(ReviewImportSegmentPlanner.segments(LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01")))
                .containsExactly(r("2026-01-01", "2026-12-31"), r("2027-01-01", "2027-01-01"));
    }

    @Test
    void longPeriodsTileIntoYearWindowsThatNeverOverlap() {
        List<DateRange> out = ReviewImportSegmentPlanner.segments(
                LocalDate.parse("2023-07-01"), LocalDate.parse("2026-07-26"));
        assertThat(out).containsExactly(
                r("2023-07-01", "2024-06-30"),
                r("2024-07-01", "2025-06-30"),
                r("2025-07-01", "2026-06-30"),
                r("2026-07-01", "2026-07-26"));
        for (int i = 1; i < out.size(); i++) {
            assertThat(out.get(i).start()).isEqualTo(out.get(i - 1).end().plusDays(1));
        }
    }

    @Test
    void aWindowStartingOnALeapDayStaysUnderAYear() {
        // 2024-02-29 + 1 year is 2025-02-28; the window ends the day before, never reaching past a year.
        assertThat(ReviewImportSegmentPlanner.segments(LocalDate.parse("2024-02-29"), LocalDate.parse("2025-03-31")))
                .containsExactly(r("2024-02-29", "2025-02-27"), r("2025-02-28", "2025-03-31"));
    }

    @Test
    void singleDayRequestYieldsOneOneDaySegment() {
        assertThat(ReviewImportSegmentPlanner.segments(LocalDate.parse("2026-06-07"), LocalDate.parse("2026-06-07")))
                .containsExactly(r("2026-06-07", "2026-06-07"));
    }

    @Test
    void invertedRangeFailsClosed() {
        assertThatThrownBy(() -> ReviewImportSegmentPlanner.segments(
                LocalDate.parse("2026-06-07"), LocalDate.parse("2026-06-01")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void missingBoundFailsClosed() {
        assertThatThrownBy(() -> ReviewImportSegmentPlanner.segments(null, LocalDate.parse("2026-06-01")))
                .isInstanceOf(ApiException.class);
    }
}
