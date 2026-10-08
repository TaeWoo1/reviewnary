package com.sellerops.coverage.catchup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The walk itself — cursor, stride, and what a saturated window does to both.</b>
 *
 * <p>Tested on the entity because that is where the arithmetic lives, and because the one property that matters
 * is arithmetic: <b>the windows a walk produces must tile the gap exactly.</b> A day skipped between two
 * windows is a hole the boundary then moves straight over; a day read twice is work the seller waits through
 * for nothing.
 */
class ReviewCatchUpWalkTest {

    private static ReviewCatchUpRun walking(String from, String through) {
        ReviewCatchUpRun run = new ReviewCatchUpRun();
        run.setRequestedFrom(LocalDate.parse(from));
        run.setRequestedThrough(LocalDate.parse(through));
        run.setCursorDay(LocalDate.parse(from));
        run.setStepDays(ReviewCatchUpRun.DEFAULT_STEP_DAYS);
        run.setState(ReviewCatchUpState.RUNNING);
        return run;
    }

    private static String show(LocalDate[] w) {
        return w == null ? "-" : w[0] + "~" + w[1];
    }

    @Test
    @DisplayName("this store's gap: 09-03 → 10-08 is six windows, oldest first, and the last one is short")
    void sixWindowsOldestFirst() {
        ReviewCatchUpRun run = walking("2026-09-03", "2026-10-08");
        StringBuilder walked = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            LocalDate[] w = run.nextWindow();
            if (w == null) {
                break;
            }
            walked.append(show(w)).append(" ");
            run.completed(w[0], w[1], 45);
        }
        assertThat(walked.toString().trim().split(" ")).containsExactly(
                "2026-09-03~2026-09-09",
                "2026-09-10~2026-09-16",
                "2026-09-17~2026-09-23",
                "2026-09-24~2026-09-30",
                "2026-10-01~2026-10-07",
                "2026-10-08~2026-10-08");
        assertThat(run.nextWindow()).isNull();
        assertThat(run.getWindowsDone()).isEqualTo(6);
        assertThat(run.getDaysCovered()).isEqualTo(36);
        assertThat(run.getRowsObserved()).isEqualTo(270);
    }

    @Test
    @DisplayName("a saturated week becomes three days and then the remaining four — never two halves of the same days")
    void deterministicSplit() {
        ReviewCatchUpRun run = walking("2026-09-03", "2026-10-08");
        LocalDate[] week = run.nextWindow();
        assertThat(show(week)).isEqualTo("2026-09-03~2026-09-09");

        assertThat(run.split(week[0], week[1])).isTrue();
        LocalDate[] firstHalf = run.nextWindow();
        assertThat(show(firstHalf)).isEqualTo("2026-09-03~2026-09-05");

        run.completed(firstHalf[0], firstHalf[1], 500);
        LocalDate[] remainder = run.nextWindow();
        // The rest of the window that was split — exactly the rest, and then the stride goes back to a week.
        assertThat(show(remainder)).isEqualTo("2026-09-06~2026-09-09");

        run.completed(remainder[0], remainder[1], 120);
        assertThat(show(run.nextWindow())).isEqualTo("2026-09-10~2026-09-16");
        // The split window's seven days were covered once each.
        assertThat(run.getDaysCovered()).isEqualTo(7);
    }

    @Test
    @DisplayName("a split that saturates again splits again, and a single day cannot be split")
    void splitUntilItCannot() {
        ReviewCatchUpRun run = walking("2026-09-03", "2026-10-08");
        LocalDate[] w = run.nextWindow();
        assertThat(run.split(w[0], w[1])).isTrue();          // 7 → 3
        w = run.nextWindow();
        assertThat(show(w)).isEqualTo("2026-09-03~2026-09-05");
        assertThat(run.split(w[0], w[1])).isTrue();          // 3 → 1
        w = run.nextWindow();
        assertThat(show(w)).isEqualTo("2026-09-03~2026-09-03");
        // One day at its own ceiling is a limit of the recipe, not a window to subdivide. Halving it forever
        // would report progress while reading the same day.
        assertThat(run.split(w[0], w[1])).isFalse();
    }

    @Test
    @DisplayName("the walk never runs past the day it set out to reach")
    void neverPastToday() {
        ReviewCatchUpRun run = walking("2026-10-06", "2026-10-08");
        assertThat(show(run.nextWindow())).isEqualTo("2026-10-06~2026-10-08");
        run.completed(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-08"), 10);
        assertThat(run.nextWindow()).isNull();
    }
}
