package com.sellerops.coverage.catchup;

import com.sellerops.coverage.CoveredWindow;
import com.sellerops.coverage.ReviewCatchUpPlan;
import com.sellerops.coverage.ReviewCoverage;
import com.sellerops.coverage.ReviewCoverageCursor;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideDispatch;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>One press, several windows, one at a time — and it stops.</b>
 *
 * <p>The seller presses 「지금 수집하기」. If this channel's reviews have a coverage gap, that press authorises
 * reading the days between the boundary and today, oldest first. Making them press once per seven-day window
 * would be asking them to re-approve the same decision six times, for a shape the product deliberately does
 * not show them.
 *
 * <h2>What moves it forward</h2>
 *
 * <b>A child settling.</b> Not a scheduler — there is none running, and a catch-up that needed one would be an
 * autonomous lane wearing an operator's authorisation. Every child reports to
 * {@link ScheduledAsideJobService#settle}, which calls {@link #advance} in the same request, so the next window
 * is queued by the helper's own report and the walk stops the moment the helper does.
 *
 * <p>One job per desk at a time is already a schema rule ({@code one-live-job}); this queues the next child
 * only after the previous one has settled, so that rule is never even approached.
 *
 * <h2>How it ends</h2>
 *
 * Five ways, four of which are not failures:
 *
 * <ul>
 *   <li><b>COMPLETE</b> — the cursor passed today.</li>
 *   <li><b>PAUSED_AUTH</b> — a window met the sign-in wall. The window is remembered, not skipped; the seller
 *   signs in and the same window is read again.</li>
 *   <li><b>STOPPED_SATURATED</b> — a single day held at least a full page. It cannot be narrowed by date, so
 *   nothing past that day may be claimed as read, and the seller is told that day may hold more.</li>
 *   <li><b>STOPPED_LIMIT</b> — one press's bound was reached. Pressing again continues from the cursor.</li>
 *   <li><b>FAILED</b> — a window ended in a way this lane cannot continue from. What completed still stands,
 *   because coverage is read from evidence and the completed windows left theirs.</li>
 * </ul>
 */
@Component
public class ReviewCatchUpOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewCatchUpOrchestrator.class);

    /**
     * What one press may set in motion.
     *
     * <p>Four ceilings because a catch-up runs long in four independent ways. Measured against this store: a
     * 7-day NAVER window held 45 rows and settled in 14 seconds, so eight windows is ~two minutes and ~360
     * rows — and today's 36-day gap (six windows) fits inside one press, which is the point.
     */
    public record Limits(int maxWindows, int maxDays, int maxRows, Duration maxElapsed) {

        public static final Limits PRESS = new Limits(8, 70, 3_000, Duration.ofMinutes(6));

        public Limits {
            if (maxWindows < 1 || maxDays < 1 || maxRows < 1) {
                throw new IllegalArgumentException("a bound below one is not a bound");
            }
            if (maxElapsed == null || maxElapsed.isZero() || maxElapsed.isNegative()) {
                throw new IllegalArgumentException("a catch-up needs a positive elapsed bound");
            }
        }
    }

    private final ReviewCatchUpRunRepository runs;
    private final ReviewCoverageCursor coverage;
    private final ScheduledAsideJobService jobs;
    private final Clock clock;
    private final Limits limits;

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewCatchUpOrchestrator(ReviewCatchUpRunRepository runs, ReviewCoverageCursor coverage,
                                     ScheduledAsideJobService jobs) {
        this(runs, coverage, jobs, Clock.systemUTC(), Limits.PRESS);
    }

    public ReviewCatchUpOrchestrator(ReviewCatchUpRunRepository runs, ReviewCoverageCursor coverage,
                                     ScheduledAsideJobService jobs, Clock clock, Limits limits) {
        this.runs = runs;
        this.coverage = coverage;
        this.jobs = jobs;
        this.clock = clock;
        this.limits = limits;
    }

    /** What a press learns: the intent it started or re-found, and the first child it put on the desk. */
    public record Started(ReviewCatchUpRun run, ScheduledAsideJob firstJob) {
    }

    /**
     * Start — or re-find — the catch-up one press authorises.
     *
     * <p>Empty means «this row has no gap to catch up», and the caller then does what it always did: one job
     * for the period the screen is showing. That is deliberately the fallback rather than the error, because a
     * channel with no period evidence at all is the state the 2026-10-08 live read ran in.
     */
    @Transactional
    public Optional<Started> start(UUID orgId, UUID sellerAccountId, UUID channelId, String dataType,
                                   AsideRecipe recipe, String requestId) {
        if (!"REVIEW".equals(dataType) || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        // The same press, re-finding its own intent. A double-click must not start a second walk.
        Optional<ReviewCatchUpRun> same = runs.findByOrgIdAndClientRequestId(orgId, requestId);
        if (same.isPresent()) {
            return Optional.of(new Started(same.get(), null));
        }
        // A live intent on this row is the one in charge; a second press does not start a rival walk.
        Optional<ReviewCatchUpRun> live = runs.findFirstBySellerAccountIdAndDataTypeAndStateIn(
                sellerAccountId, dataType, EnumSet.of(ReviewCatchUpState.RUNNING, ReviewCatchUpState.PAUSED_AUTH));
        if (live.isPresent()) {
            return Optional.of(resume(live.get(), recipe, requestId));
        }
        LocalDate today = LocalDate.now(ReviewCoverageCursor.KST);
        ReviewCoverage held = coverage.of(orgId, channelId, today);
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(held, today,
                new ReviewCatchUpPlan.Limits(limits.maxWindows(), limits.maxRows(), limits.maxElapsed(),
                        ReviewCatchUpRun.DEFAULT_STEP_DAYS));
        if (plan.windows().isEmpty()) {
            return Optional.empty();
        }
        CoveredWindow first = plan.windows().get(0);
        Instant now = clock.instant();
        ReviewCatchUpRun run = new ReviewCatchUpRun();
        run.setOrgId(orgId);
        run.setSellerAccountId(sellerAccountId);
        run.setChannelId(channelId);
        run.setDataType(dataType);
        run.setState(ReviewCatchUpState.RUNNING);
        run.setRequestedFrom(first.start());
        run.setRequestedThrough(today);
        run.setCursorDay(first.start());
        run.setStepDays(ReviewCatchUpRun.DEFAULT_STEP_DAYS);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        run.setClientRequestId(requestId);
        runs.save(run);
        ScheduledAsideJob child = queue(run, recipe, first.start(), first.end());
        log.info("review catch-up: started run={} from={} through={} windows={}",
                run.getId(), run.getRequestedFrom(), run.getRequestedThrough(), plan.windows().size());
        return Optional.of(new Started(run, child));
    }

    /**
     * A child settled. Record what it proved, and put the next window on the desk — or stop, and say why.
     *
     * <p>Called from inside {@code settle}, so the job row this reads already carries what the delivery wrote
     * (the period it covered, and whether it reached its own row ceiling). Never throws into the helper's
     * report: a catch-up that cannot continue must not turn a child's honest report into a 500.
     */
    @Transactional
    public void advance(ScheduledAsideJob child) {
        if (child == null || child.getCatchUpRunId() == null) {
            return;
        }
        ReviewCatchUpRun run = runs.findById(child.getCatchUpRunId()).orElse(null);
        if (run == null || run.getState() != ReviewCatchUpState.RUNNING) {
            return;
        }
        LocalDate start = child.getRequestedWindowStart();
        LocalDate end = child.getRequestedWindowEnd();
        if (start == null || end == null) {
            stop(run, ReviewCatchUpState.FAILED, "WINDOW_UNKNOWN");
            return;
        }
        if (child.getOutcome() == AsideJobOutcome.AUTH_REQUIRED) {
            // The window is remembered, not skipped. A catch-up that stepped over a login wall would leave a
            // hole it had already counted as walked.
            run.setPausedWindowStart(start);
            stop(run, ReviewCatchUpState.PAUSED_AUTH, "AUTH_REQUIRED");
            return;
        }
        if (child.getOutcome() != AsideJobOutcome.OBSERVED) {
            stop(run, ReviewCatchUpState.FAILED, String.valueOf(child.getOutcome()));
            return;
        }
        boolean complete = child.getDeliveryCompleteness() == SourceCompleteness.COMPLETE;
        if (!complete) {
            // Saturated: the rows are stored, the days are not proved. Narrow the period and look again.
            if (!run.split(start, end)) {
                run.setPausedWindowStart(start);
                stop(run, ReviewCatchUpState.STOPPED_SATURATED, "DAY_SATURATED");
                return;
            }
        } else {
            run.completed(start, end, child.getObservedCount() == null ? 0 : child.getObservedCount());
        }
        Optional<String> bound = exceeded(run);
        if (bound.isPresent()) {
            stop(run, ReviewCatchUpState.STOPPED_LIMIT, bound.get());
            return;
        }
        LocalDate[] next = run.nextWindow();
        if (next == null) {
            stop(run, ReviewCatchUpState.COMPLETE, null);
            return;
        }
        run.setUpdatedAt(clock.instant());
        runs.save(run);
        try {
            queue(run, child.getRecipe(), next[0], next[1]);
        } catch (RuntimeException e) {
            // A desk that went away, or a grant that was revoked mid-walk. The windows already read still
            // stand; this says where it stopped instead of pretending it finished.
            log.info("review catch-up: could not queue the next window run={} type={}",
                    run.getId(), e.getClass().getSimpleName());
            stop(run, ReviewCatchUpState.FAILED, "NEXT_WINDOW_UNAVAILABLE");
        }
    }

    private Started resume(ReviewCatchUpRun run, AsideRecipe recipe, String requestId) {
        if (run.getState() == ReviewCatchUpState.PAUSED_AUTH) {
            // The paused window, not the one after it.
            if (run.getPausedWindowStart() != null) {
                run.setCursorDay(run.getPausedWindowStart());
            }
            run.setPausedWindowStart(null);
            run.setStopReason(null);
            run.setState(ReviewCatchUpState.RUNNING);
        }
        LocalDate[] next = run.nextWindow();
        if (next == null) {
            stop(run, ReviewCatchUpState.COMPLETE, null);
            return new Started(run, null);
        }
        run.setUpdatedAt(clock.instant());
        runs.save(run);
        return new Started(run, queue(run, recipe, next[0], next[1]));
    }

    private ScheduledAsideJob queue(ReviewCatchUpRun run, AsideRecipe recipe, LocalDate start, LocalDate end) {
        // The child's id is derived from the intent and the window, so a retry of the same window converges on
        // one job instead of queueing a second (`(device, clientJobId)` idempotency).
        String clientJobId = "cu-" + run.getId().toString().substring(0, 8) + "-" + start + "-" + end;
        return jobs.dispatch(AsideDispatch.catchUpWindow(run.getOrgId(), run.getSellerAccountId(), recipe,
                clientJobId, run.getId(), start, end));
    }

    /** Which bound this press has reached, if any. Named, because 「상한에 도달함」 needs to say which one. */
    private Optional<String> exceeded(ReviewCatchUpRun run) {
        if (run.getWindowsDone() >= limits.maxWindows()) {
            return Optional.of("MAX_WINDOWS");
        }
        if (run.getDaysCovered() >= limits.maxDays()) {
            return Optional.of("MAX_DAYS");
        }
        if (run.getRowsObserved() >= limits.maxRows()) {
            return Optional.of("MAX_ROWS");
        }
        if (Duration.between(run.getStartedAt(), clock.instant()).compareTo(limits.maxElapsed()) >= 0) {
            return Optional.of("MAX_ELAPSED");
        }
        return Optional.empty();
    }

    private void stop(ReviewCatchUpRun run, ReviewCatchUpState state, String reason) {
        run.setState(state);
        run.setStopReason(reason);
        Instant now = clock.instant();
        run.setUpdatedAt(now);
        if (!state.live()) {
            run.setFinishedAt(now);
        }
        runs.save(run);
        log.info("review catch-up: run={} state={} reason={} windows={} days={} rows={}",
                run.getId(), state, reason, run.getWindowsDone(), run.getDaysCovered(), run.getRowsObserved());
    }

    /** The live intent for one row, for the screen to describe. */
    @Transactional(readOnly = true)
    public Optional<ReviewCatchUpRun> liveFor(UUID sellerAccountId, String dataType) {
        return runs.findFirstBySellerAccountIdAndDataTypeAndStateIn(sellerAccountId, dataType,
                EnumSet.of(ReviewCatchUpState.RUNNING, ReviewCatchUpState.PAUSED_AUTH));
    }

}
