package com.sellerops.coverage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>Which periods still have to be read — and where the plan stops.</b>
 *
 * <p>Two spans, in this order: the <b>forward gaps</b> after the coverage boundary, oldest first; then, when a
 * history start was recorded ({@link #from(ReviewCoverage, LocalDate, Limits, LocalDate, int)}), the
 * <b>history</b> before the covered stretch, newest first.
 *
 * <p>A plan is produced and shown before anything runs. That is the point: the 2026-10-08 read covered the last
 * seven days and the seller had no way to see that twenty-nine days behind it were untouched. A plan that can be
 * printed is a plan that can be disagreed with.
 *
 * @param windows  the periods to read — forward gaps oldest first, then history newest first — each inside the
 *                 bound one read can carry
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
        /**
         * There is no evidence to plan from — the boundary is unknown, and a guessed start is not a plan. Only
         * returned when no history start was recorded either: a recorded start is a plan.
         */
        NO_BOUNDARY
    }

    /**
     * How many days one history window spans. 28, against 7 forward: history is read once, unattended, and a
     * year in seven-day windows is 53 reads where 28-day windows make 14. A month that holds more than one
     * page does not get lost to the larger stride — it comes back PARTIAL (or {@code TOO_MANY_ROWS}) and the walk
     * halves it, down to a single day.
     *
     * <p>Not 31: the first live history walk (2026-10-13, {@code apr-449b0c62eea9}) read 2026-05-31..06-30 whole
     * and then got {@code RANGE_MISMATCH} on 2026-04-30..05-30 — the same 31 days, one day short of matching.
     * That is what a search period capped at "start + one month − one day" does (05-31 + 1 month rolls to
     * 07-01, so 06-30 fits; 04-30 + 1 month is 05-30, so the screen ends the period on 05-29). The cap itself is
     * inferred, not read — the screen's dates never leave the helper. 28 days fits under it in every month; a
     * window that still comes back mismatched is halved ({@code ReviewCatchUpOrchestrator}).
     */
    public static final int HISTORY_STEP_DAYS = 28;

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
     * <b>The history span: from the recorded start to the day before the covered stretch begins.</b>
     *
     * <p>{@code [historyFrom, coverageFrom - 1]}, or {@code [historyFrom, yesterday]} when nothing is covered at
     * all. Empty when no start was recorded, or when it falls on or after the covered stretch — history already
     * held is not history still to read.
     *
     * <p><b>Not a guessed start.</b> {@link ReviewCoverageCursor} refuses to synthesise one because a guessed date
     * would be indistinguishable downstream from a measured one. {@code historyFrom} is neither: it is a choice
     * the seller's setting records — the product's default of one year (product-owner decision 2026-10-13), or a
     * date the seller set — and it is planned from as exactly that.
     */
    public static Optional<CoveredWindow> historySpan(ReviewCoverage coverage, LocalDate asOf, LocalDate historyFrom) {
        LocalDate through = ReviewCoverage.lastClosedDay(asOf);
        if (historyFrom == null || through == null) {
            return Optional.empty();
        }
        LocalDate end = coverage == null || coverage.coverageFrom() == null
                ? through : coverage.coverageFrom().minusDays(1);
        if (end.isAfter(through)) {
            end = through;
        }
        if (historyFrom.isAfter(end)) {
            return Optional.empty();
        }
        return Optional.of(new CoveredWindow(historyFrom, end, CoveredWindow.CoverageSource.SCREEN_READ));
    }

    /**
     * <b>Plan the forward gaps, then the recorded history — newest first.</b>
     *
     * <p>The forward part is exactly {@link #from(ReviewCoverage, LocalDate, Limits)}. The history part walks
     * {@link #historySpan} from its newest day back to {@code historyFrom} in {@code historyStepDays} windows.
     * Newest first for two reasons: a period that holds rows is read before quiet old ones, so the seller-centre
     * screen's fingerprint is on record by the time an empty month has to be attributed to this store; and a walk
     * that stops part-way leaves one contiguous stretch rather than an island with a hole behind it.
     *
     * <p>Days already covered are skipped as the forward planner skips them, and both spans share one
     * {@code maxWindows}. With no history start this is the forward plan, unchanged — including
     * {@link Stop#NO_BOUNDARY}.
     */
    public static ReviewCatchUpPlan from(ReviewCoverage coverage, LocalDate asOf, Limits limits,
                                         LocalDate historyFrom, int historyStepDays) {
        if (historyStepDays < 1) {
            throw new IllegalArgumentException("a bound below one is not a bound");
        }
        ReviewCatchUpPlan forward = from(coverage, asOf, limits);
        Optional<CoveredWindow> span = historySpan(coverage, asOf, historyFrom);
        if (span.isEmpty()) {
            return forward;
        }
        List<CoveredWindow> windows = new ArrayList<>(forward.windows());
        Stop stop = forward.stopped() == Stop.MAX_WINDOWS || forward.stopped() == Stop.MAX_ROWS
                ? forward.stopped() : Stop.COMPLETE;
        LocalDate first = span.get().start();
        LocalDate cursor = span.get().end();
        while (!cursor.isBefore(first)) {
            if (coverage != null && coverage.covers(cursor)) {
                cursor = cursor.minusDays(1);
                continue;
            }
            if (windows.size() >= limits.maxWindows()) {
                if (stop == Stop.COMPLETE) {
                    stop = Stop.MAX_WINDOWS;
                }
                break;
            }
            LocalDate start = cursor.minusDays(historyStepDays - 1L);
            if (start.isBefore(first)) {
                start = first;
            }
            while (start.isBefore(cursor) && coverage != null && coverage.covers(start)) {
                start = start.plusDays(1);
            }
            windows.add(new CoveredWindow(start, cursor, CoveredWindow.CoverageSource.SCREEN_READ));
            cursor = start.minusDays(1);
        }
        long remaining = forward.remaining();
        for (LocalDate d = cursor; !d.isBefore(first); d = d.minusDays(1)) {
            if (coverage == null || !coverage.covers(d)) {
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
