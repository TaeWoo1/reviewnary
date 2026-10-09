package com.sellerops.coverage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Which periods still have to be read, oldest first — and where the plan stops.</b>
 *
 * <p>A plan is produced and shown before anything runs. That is the point: the 2026-10-08 read covered the last
 * seven days and the seller had no way to see that twenty-nine days behind it were untouched. A plan that can be
 * printed is a plan that can be disagreed with.
 *
 * @param windows  the periods to read, oldest first, each inside the bound one read can carry
 * @param stopped  why the plan ends where it does
 * @param remaining days still uncovered after the planned windows — 0 when the plan reaches today
 */
public record ReviewCatchUpPlan(List<CoveredWindow> windows, Stop stopped, long remaining) {

    public static final ReviewCatchUpPlan NOTHING_TO_DO = new ReviewCatchUpPlan(List.of(), Stop.NOTHING_TO_DO, 0);

    /** Why planning ended. Never «unknown»: a plan that cannot say why it stopped cannot be trusted to resume. */
    public enum Stop {
        /** The plan reaches the seller's today. Nothing is left. */
        COMPLETE,
        /** Nothing was missing to begin with. */
        NOTHING_TO_DO,
        /** The window limit for one authorisation was reached. */
        MAX_WINDOWS,
        /** The row budget for one authorisation was reached. */
        MAX_ROWS,
        /** There is no evidence to plan from — the boundary is unknown, and a guessed start is not a plan. */
        NO_BOUNDARY
    }

    /**
     * How much one press may set in motion.
     *
     * <p>One click is one bounded authorisation, and the bound has to be a number rather than a feeling. Three
     * independent ceilings, because the three ways a catch-up runs long are independent: many windows, one very
     * large window, and a long wall-clock. The elapsed ceiling is enforced by the runner against a real clock —
     * it is carried here so the plan and the run agree on it.
     */
    public record Limits(int maxWindows, int maxRows, Duration maxElapsed, int windowDays) {

        /**
         * The default for an operator press, measured against this store: a 7-day NAVER window held 45 rows and
         * settled in 14 seconds. Six windows is six weeks of history, ~270 rows and well inside two minutes —
         * long enough to be worth a press, short enough that the seller is still watching when it ends.
         */
        public static final Limits OPERATOR_PRESS = new Limits(6, 2_000, Duration.ofMinutes(4), 7);

        public Limits {
            if (maxWindows < 1 || maxRows < 1 || windowDays < 1) {
                throw new IllegalArgumentException("a bound below one is not a bound");
            }
            if (maxElapsed == null || maxElapsed.isZero() || maxElapsed.isNegative()) {
                throw new IllegalArgumentException("a catch-up needs a positive elapsed bound");
            }
        }
    }

    /**
     * Plan from a coverage answer.
     *
     * <p>Walks forward from the boundary in {@code windowDays} steps, stepping over anything already verified —
     * the 10-02 … 10-08 island this channel already holds is not read again, and a window is clamped so it never
     * crosses into it. A window that would start after {@code asOf} is not planned: tomorrow is not a gap.
     */
    public static ReviewCatchUpPlan from(ReviewCoverage coverage, LocalDate asOf, Limits limits) {
        if (coverage == null || coverage.coverageThrough() == null) {
            return new ReviewCatchUpPlan(List.of(), Stop.NO_BOUNDARY, 0);
        }
        // <b>A walk closes history, and history ends yesterday.</b> Planning as far as today would put a window
        // on the one day coverage may never claim ({@link ReviewCoverage#lastClosedDay}), so that window would
        // settle, prove nothing, and be planned again on the next pass — a walk that can never report COMPLETE.
        // Today is refreshed by its own read instead, and that read is about freshness rather than coverage.
        LocalDate through = ReviewCoverage.lastClosedDay(asOf);
        if (through == null) {
            return NOTHING_TO_DO;
        }
        LocalDate cursor = coverage.coverageThrough().plusDays(1);
        if (cursor.isAfter(through)) {
            return NOTHING_TO_DO;
        }
        List<CoveredWindow> windows = new ArrayList<>();
        Stop stop = Stop.COMPLETE;
        while (!cursor.isAfter(through)) {
            if (coverage.covers(cursor)) {
                cursor = cursor.plusDays(1);
                continue;
            }
            if (windows.size() >= limits.maxWindows()) {
                stop = Stop.MAX_WINDOWS;
                break;
            }
            LocalDate end = cursor.plusDays(limits.windowDays() - 1L);
            if (end.isAfter(through)) {
                end = through;
            }
            // Never swallow a day already verified: a window that crosses the island would be delivered as one
            // period and recorded as covering days it did not re-read.
            while (end.isAfter(cursor) && coverage.covers(end)) {
                end = end.minusDays(1);
            }
            windows.add(new CoveredWindow(cursor, end, CoveredWindow.CoverageSource.SCREEN_READ));
            cursor = end.plusDays(1);
        }
        long remaining = 0;
        for (LocalDate d = cursor; !d.isAfter(through); d = d.plusDays(1)) {
            if (!coverage.covers(d)) {
                remaining++;
            }
        }
        if (remaining > 0 && stop == Stop.COMPLETE) {
            stop = Stop.MAX_WINDOWS;
        }
        return new ReviewCatchUpPlan(List.copyOf(windows), windows.isEmpty() ? Stop.NOTHING_TO_DO : stop, remaining);
    }

    /**
     * Halve a window whose read could not exclude 「더 있을 수 있음」.
     *
     * <p>Empty when the window is one day: a single day that saturates cannot be narrowed by date, and splitting
     * it into two copies of itself would loop forever while reporting progress. That case is a real limit of the
     * recipe and belongs on screen, not in a retry.
     */
    public static List<CoveredWindow> split(CoveredWindow saturated) {
        if (saturated.days() < 2) {
            return List.of();
        }
        long half = saturated.days() / 2;
        LocalDate mid = saturated.start().plusDays(half - 1);
        return List.of(new CoveredWindow(saturated.start(), mid, saturated.source()),
                new CoveredWindow(mid.plusDays(1), saturated.end(), saturated.source()));
    }
}
