package com.sellerops.coverage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>What this channel's reviews have been read end to end, and where the holes are.</b>
 *
 * <p>Three facts the product kept confusing for each other until 2026-10-08:
 *
 * <ul>
 *   <li><b>마지막 성공</b> — when a read last succeeded ({@code AcquisitionHistory#lastSuccessAt}). 10-08.</li>
 *   <li><b>가장 최근에 들어온 리뷰</b> — {@code newestObservedAt}. 10-08.</li>
 *   <li><b>빠짐없이 확인한 경계</b> — this. 09-02, because the 10-08 read covered 10-02 … 10-08 and the
 *   twenty-nine days before it were never looked at.</li>
 * </ul>
 *
 * <p>The first two were both true and were both read as the third, which is how a channel with a month-wide
 * hole reported itself current. A success time is not a boundary: it says when we looked, not how far the
 * looking reached.
 *
 * @param verified        every stretch actually read, merged and in date order
 * @param coverageThrough the last day of the <b>oldest contiguous stretch</b> — the point up to which there is
 *                        nothing missing. Null when nothing has been verified
 * @param coverageFrom    the first day of that same stretch. A boundary is an interval, not a point: 「10-08까지
 *                        빠짐없이」 is a different claim from 「10-02부터 10-08까지 빠짐없이」, and only one of
 *                        them was ever true of this channel
 * @param gaps            the unread stretches between {@link #coverageThrough} and {@code asOf}, oldest first
 */
public record ReviewCoverage(List<CoveredWindow> verified, LocalDate coverageFrom, LocalDate coverageThrough,
                             List<CoveredWindow> gaps) {

    public static final ReviewCoverage NONE = new ReviewCoverage(List.of(), null, null, List.of());

    /**
     * Build the answer from raw evidence.
     *
     * <p>{@code asOf} is the seller's today, in KST. Days after it are not gaps — they have not happened.
     */
    public static ReviewCoverage of(List<CoveredWindow> evidence, LocalDate asOf) {
        List<CoveredWindow> merged = merge(evidence);
        if (merged.isEmpty()) {
            return NONE;
        }
        CoveredWindow oldest = merged.get(0);
        List<CoveredWindow> gaps = new ArrayList<>();
        LocalDate cursor = oldest.end().plusDays(1);
        for (int i = 1; i < merged.size(); i++) {
            CoveredWindow next = merged.get(i);
            if (cursor.isBefore(next.start())) {
                gaps.add(new CoveredWindow(cursor, next.start().minusDays(1), CoveredWindow.CoverageSource.MERGED));
            }
            cursor = next.end().plusDays(1);
        }
        if (asOf != null && !cursor.isAfter(asOf)) {
            gaps.add(new CoveredWindow(cursor, asOf, CoveredWindow.CoverageSource.MERGED));
        }
        return new ReviewCoverage(merged, oldest.start(), oldest.end(), List.copyOf(gaps));
    }

    /** Overlapping or touching stretches become one. Pure, and the only place this rule is written. */
    static List<CoveredWindow> merge(List<CoveredWindow> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        List<CoveredWindow> sorted = new ArrayList<>(evidence);
        sorted.sort(Comparator.comparing(CoveredWindow::start).thenComparing(CoveredWindow::end));
        List<CoveredWindow> out = new ArrayList<>();
        CoveredWindow open = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            CoveredWindow next = sorted.get(i);
            if (open.joins(next)) {
                open = open.mergedWith(next);
            } else {
                out.add(open);
                open = next;
            }
        }
        out.add(open);
        return List.copyOf(out);
    }

    /** Whether every day from the oldest evidence to {@code asOf} has been read. */
    public boolean completeThrough(LocalDate asOf) {
        return coverageThrough != null && gaps.isEmpty() && !coverageThrough.isBefore(asOf);
    }

    /** Whether {@code day} sits inside any verified stretch — asked by the planner so it skips what it has. */
    public boolean covers(LocalDate day) {
        return verified.stream().anyMatch(w -> w.covers(day));
    }
}
