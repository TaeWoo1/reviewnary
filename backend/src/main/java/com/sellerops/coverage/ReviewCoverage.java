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
 * <h2>오늘은 「빠짐없이 확인한 날」이 될 수 없다</h2>
 *
 * <p>A day is closed only once it has ended. A read at 09:00 that states 10-02 … 10-09 is telling the truth
 * about what it saw, and 「10-09까지 빠짐없이 확인」 would still be false at 14:00 — the afternoon's reviews
 * were not on that page and nothing has looked since. So every stretch is clamped to the last <b>closed</b>
 * day ({@code asOf - 1}) before anything is merged, and a stretch that holds only today contributes nothing.
 *
 * <p>The clamp lives here, in the one pure function, and not in the evidence: the job row keeps saying which
 * days it actually read, because that is a measurement and this is an interpretation of it. Today's read is
 * expressed as freshness instead — {@code AcquisitionHistory#lastSuccessAt}, 「오늘 13:40 확인」 — which is a
 * fact about when we looked, the thing a boundary is not.
 *
 * @param verified        every stretch actually read, merged, clamped to the last closed day, in date order
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
     * <p>{@code asOf} is the seller's today, in KST. Days after it are not gaps — they have not happened. Nor is
     * today itself one: it is the day still being written, and {@link #lastClosedDay(LocalDate)} is where every
     * claim in this record stops.
     */
    public static ReviewCoverage of(List<CoveredWindow> evidence, LocalDate asOf) {
        LocalDate through = lastClosedDay(asOf);
        List<CoveredWindow> merged = merge(clamp(evidence, through));
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
        if (through != null && !cursor.isAfter(through)) {
            gaps.add(new CoveredWindow(cursor, through, CoveredWindow.CoverageSource.MERGED));
        }
        return new ReviewCoverage(merged, oldest.start(), oldest.end(), List.copyOf(gaps));
    }

    /**
     * The last day that has ended, which is the furthest any coverage claim may reach.
     *
     * <p>Null {@code asOf} answers null, like every other «we do not know» in this package. One method rather
     * than {@code asOf.minusDays(1)} written in five places, because the day this product is allowed to call
     * closed is a product rule and it is stated once.
     */
    public static LocalDate lastClosedDay(LocalDate asOf) {
        return asOf == null ? null : asOf.minusDays(1);
    }

    /**
     * Every stretch, cut back to the last closed day. A stretch that began after it disappears.
     *
     * <p>Not a filter on «ends today»: a seven-day read ending today proved its first six days and they are
     * kept. What is dropped is only the part of a window that has not finished happening.
     */
    static List<CoveredWindow> clamp(List<CoveredWindow> evidence, LocalDate through) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        if (through == null) {
            return List.copyOf(evidence);
        }
        List<CoveredWindow> out = new ArrayList<>();
        for (CoveredWindow w : evidence) {
            if (w.start().isAfter(through)) {
                continue;
            }
            out.add(w.end().isAfter(through) ? new CoveredWindow(w.start(), through, w.source()) : w);
        }
        return List.copyOf(out);
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

    /**
     * Whether every day from the oldest evidence to the last closed day before {@code asOf} has been read.
     *
     * <p>Today is deliberately not part of the question. 「어제까지 빠짐없이」 is the strongest true claim this
     * product can make at any hour of the day, and whether today has been looked at recently is freshness.
     */
    public boolean completeThrough(LocalDate asOf) {
        LocalDate through = lastClosedDay(asOf);
        return coverageThrough != null && gaps.isEmpty() && through != null && !coverageThrough.isBefore(through);
    }

    /** Whether {@code day} sits inside any verified stretch — asked by the planner so it skips what it has. */
    public boolean covers(LocalDate day) {
        return verified.stream().anyMatch(w -> w.covers(day));
    }
}
