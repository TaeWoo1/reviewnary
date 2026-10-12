package com.sellerops.reviewimport;

import com.sellerops.common.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure segmentation of a requested historical period into export windows. Default: <b>one segment per
 * requested period</b>, capped at one year — a longer period becomes consecutive windows of at most one year
 * each (decision, 2026-10-13, superseding the 2026-07-24 calendar-month default).
 *
 * <p>Why the period and not the month. {@code segmentCount} is the number of separate exports the seller
 * sits through, and the month default made that count a function of the calendar alone: a year of history
 * cost twelve seated downloads although the NAVER review search exports a range of up to a year (its own
 * 「1년」 preset) in one. The month split bought no safety in exchange — no export was ever reconciled
 * against anything, so a truncated month and a truncated year were equally invisible. Completeness is now
 * judged per export by row reconciliation ({@code review_import_segment.rows_reconciled}), not presumed
 * from a small window.
 *
 * <p>Why one year. It is the widest window the marketplace's own range control offers as a preset; asking
 * for more in one export would be a guess about the marketplace, which this class never makes. No row cap
 * is assumed either: whether one export came back whole is measured, and an export that did not is split by
 * the operator into shorter child ranges.
 *
 * <p>The window is inclusive on both ends. Fails closed on a missing or inverted range, mirroring
 * {@code BackfillWindow}, so an unbounded sweep can never be planned by mistake.
 */
public final class ReviewImportSegmentPlanner {

    private ReviewImportSegmentPlanner() {
    }

    /** An inclusive [start, end] date window. */
    public record DateRange(LocalDate start, LocalDate end) {
        public DateRange {
            if (start == null || end == null) {
                throw ApiException.badRequest("가져올 기간의 시작일과 종료일을 모두 입력해 주세요.");
            }
            if (start.isAfter(end)) {
                throw ApiException.badRequest("시작일은 종료일보다 늦을 수 없습니다.");
            }
        }
    }

    /**
     * Divide [start, end] into consecutive windows of at most one year, in chronological order. A period of
     * a year or less is one window; 2025-01-15 … 2026-03-10 yields 2025-01-15 … 2026-01-14 and 2026-01-15 …
     * 2026-03-10. Adjacent windows never overlap by a day. A single-day request yields one one-day window.
     */
    public static List<DateRange> segments(LocalDate start, LocalDate end) {
        DateRange window = new DateRange(start, end); // validates
        List<DateRange> out = new ArrayList<>();
        LocalDate cursor = window.start();
        while (!cursor.isAfter(window.end())) {
            LocalDate yearEnd = cursor.plusYears(1).minusDays(1);
            LocalDate segEnd = yearEnd.isAfter(window.end()) ? window.end() : yearEnd;
            out.add(new DateRange(cursor, segEnd));
            cursor = segEnd.plusDays(1);
        }
        return out;
    }
}
