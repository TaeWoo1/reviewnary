package com.sellerops.collect.dto;

import com.sellerops.coverage.CoveredWindow;
import com.sellerops.coverage.ReviewCatchUpPlan;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>The dry plan — which periods are missing, printed before anything runs.</b>
 *
 * <p>Read-only by construction: this endpoint has no side effect, dispatches nothing and is safe to call from a
 * screen that is merely open. It exists because the alternative is a seller pressing a button whose scope is
 * invisible, which is how 「최근 7일 45건」 was read as 「9/2 이후가 메워졌다」.
 *
 * @param coverageFrom    first day of the contiguous stretch already held, or null when nothing is held
 * @param coverageThrough last day of it — the boundary
 * @param windows         the periods this press would read, oldest first
 * @param stopped         why the list ends where it does ({@link ReviewCatchUpPlan.Stop})
 * @param remainingDays   days still uncovered after those windows
 * @param executable      whether this deployment can actually read a period the seller is not already looking
 *                        at. <b>False today:</b> the screen read opens one route and reads whatever period the
 *                        page is showing, so a past window has no carrier yet. Stated rather than implied, so a
 *                        plan is never mistaken for a schedule
 */
public record ReviewCatchUpPlanView(LocalDate coverageFrom, LocalDate coverageThrough, List<Window> windows,
                                    ReviewCatchUpPlan.Stop stopped, long remainingDays, boolean executable) {

    /** One period, as the screen shows it. */
    public record Window(LocalDate start, LocalDate end, long days) {

        static Window of(CoveredWindow w) {
            return new Window(w.start(), w.end(), w.days());
        }
    }

    public static ReviewCatchUpPlanView of(com.sellerops.coverage.ReviewCoverage coverage, ReviewCatchUpPlan plan,
                                           boolean executable) {
        return new ReviewCatchUpPlanView(coverage.coverageFrom(), coverage.coverageThrough(),
                plan.windows().stream().map(Window::of).toList(), plan.stopped(), plan.remaining(), executable);
    }
}
