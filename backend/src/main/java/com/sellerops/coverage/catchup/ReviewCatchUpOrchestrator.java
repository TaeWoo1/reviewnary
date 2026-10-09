package com.sellerops.coverage.catchup;

import com.sellerops.coverage.CoveredWindow;
import com.sellerops.coverage.ReviewCatchUpPlan;
import com.sellerops.coverage.ReviewCoverage;
import com.sellerops.coverage.ReviewCoverageCursor;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideDispatch;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.AsideTrigger;
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
        // <b>A live intent on this row is the one in charge, and a person's press joins it rather than racing
        // it.</b> A walk the automatic check started is doing exactly what the press is asking for, so the press
        // is answered with the child that is already on the desk — never with a rival job, and never with a
        // refusal. The child in flight is left alone: it is reading the seller's screen at this moment.
        Optional<ReviewCatchUpRun> live = runs.findFirstBySellerAccountIdAndDataTypeAndStateIn(
                sellerAccountId, dataType, EnumSet.of(ReviewCatchUpState.RUNNING, ReviewCatchUpState.PAUSED_AUTH));
        if (live.isPresent()) {
            ReviewCatchUpRun run = live.get();
            if (run.getState() == ReviewCatchUpState.RUNNING) {
                Optional<ScheduledAsideJob> inFlight = inFlightChild(run, recipe);
                if (inFlight.isPresent()) {
                    return Optional.of(new Started(run, inFlight.get()));
                }
            }
            return Optional.of(resume(run, recipe));
        }
        return begin(orgId, sellerAccountId, channelId, dataType, recipe, AsideTrigger.OPERATOR, requestId);
    }

    /**
     * <b>The walk the seller's automatic check starts when it finds history unread.</b>
     *
     * <p>Same program, same windows, same bounds as a press — only the trigger differs, and it differs on every
     * child's row. Empty means there is nothing to close: the ordinary answer, because most accounts on most
     * days have no gap, and then the check reads today instead.
     *
     * <p>Deliberately <b>not</b> a resume. The press path continues a live intent because a person is standing
     * in front of it; a tick that found a live intent has nothing to add and does not call this at all. A run
     * paused at a sign-in wall is continued by the seller signing in ({@link #resumeAfterSignIn}), never by a
     * timer — retrying a wall on a schedule is how a product teaches a seller that automatic means noisy.
     */
    @Transactional
    public Optional<Started> startScheduled(UUID orgId, UUID sellerAccountId, UUID channelId, String dataType,
                                            AsideRecipe recipe) {
        return begin(orgId, sellerAccountId, channelId, dataType, recipe, AsideTrigger.SCHEDULED, null);
    }

    /** The walk itself, with its provenance as a parameter and nothing else different. */
    private Optional<Started> begin(UUID orgId, UUID sellerAccountId, UUID channelId, String dataType,
                                    AsideRecipe recipe, AsideTrigger trigger, String requestId) {
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
        run.setTriggerSource(trigger);
        run.setRequestedFrom(first.start());
        // <b>어제까지.</b> A walk closes history, and the day still being written is not history — it is read
        // separately, as freshness. Setting this to today would leave a window on a day whose coverage can never
        // be claimed, and the run would reach it and stop being able to finish.
        run.setRequestedThrough(ReviewCoverage.lastClosedDay(today));
        run.setCursorDay(first.start());
        run.setStepDays(ReviewCatchUpRun.DEFAULT_STEP_DAYS);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        run.setClientRequestId(requestId);
        runs.save(run);
        ScheduledAsideJob child = queue(run, recipe, first.start(), first.end());
        log.info("review catch-up: started run={} trigger={} from={} through={} windows={}",
                run.getId(), trigger, run.getRequestedFrom(), run.getRequestedThrough(), plan.windows().size());
        return Optional.of(new Started(run, child));
    }

    /**
     * <b>The seller signed in. Continue whatever was waiting on exactly that.</b>
     *
     * <p>One move, and it is a conditional state transition: only a run in {@link ReviewCatchUpState#PAUSED_AUTH}
     * may be resumed here, and resuming it makes it RUNNING. That is what makes the event <b>at most once</b>
     * without a column to remember it by — a second sign-in notice finds a RUNNING run and does nothing, and
     * nothing anywhere records that a person was signed in at some past moment (a product decision of
     * 2026-10-08: a sign-in observed minutes ago is not a fact about now).
     *
     * <p><b>The trigger is the run's own.</b> A walk the automatic check started is continued as that walk, not
     * converted into a press — otherwise signing in would silently re-label unattended work as something a
     * person asked for, in the audit trail that exists to tell them apart.
     */
    @Transactional
    public Optional<Started> resumeAfterSignIn(UUID orgId, UUID sellerAccountId, String dataType,
                                               AsideRecipe recipe) {
        Optional<ReviewCatchUpRun> paused = runs.findFirstBySellerAccountIdAndDataTypeAndStateIn(
                sellerAccountId, dataType, EnumSet.of(ReviewCatchUpState.PAUSED_AUTH))
                .filter(run -> orgId.equals(run.getOrgId()));
        if (paused.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(resume(paused.get(), recipe));
    }

    /** The child a live run has on the desk right now, if any — the job a press is answered with. */
    private Optional<ScheduledAsideJob> inFlightChild(ReviewCatchUpRun run, AsideRecipe recipe) {
        return jobs.liveFor(run.getOrgId(), run.getSellerAccountId(), recipe)
                .filter(j -> run.getId().equals(j.getCatchUpRunId()));
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
            run.beginWaiting(clock.instant());
            stop(run, ReviewCatchUpState.PAUSED_AUTH, "AUTH_REQUIRED");
            return;
        }
        if (child.getOutcome() != AsideJobOutcome.OBSERVED) {
            // Where it stopped, when the child could say — the outcome alone covers three different fixes
            // (2026-10-08), and the one word the seller's screen turns into a sentence is this one.
            stop(run, ReviewCatchUpState.FAILED, child.getFailureCode() != null
                    ? child.getFailureCode()
                    : String.valueOf(child.getOutcome()));
            return;
        }
        // <b>읽었지만 어느 가게인지 댈 수 없는 창.</b> 행이 하나도 없는 기간에는 상품번호가 없고, 상품번호가
        // 없으면 그 화면이 이 조직의 가게라는 증거가 없다 — 그래서 identity는 UNRESOLVED로 남는다. 읽기는
        // 성공이지만 「이 날은 비어 있었다」는 coverage 주장은 사지 않는다. 그 창을 걸어 지났다고 적는 대신
        // 거기서 멈추고, 그 사실을 이름으로 남긴다: 포화(DAY_SATURATED)는 전혀 다른 이야기였다.
        if (child.getIdentityVerdict() != com.sellerops.responsibility.IdentityVerdict.MATCH) {
            run.setPausedWindowStart(start);
            stop(run, ReviewCatchUpState.STOPPED_SATURATED, "EMPTY_PERIOD_UNATTRIBUTED");
            return;
        }
        boolean complete = child.getDeliveryCompleteness() == SourceCompleteness.BOUNDED;
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

    private Started resume(ReviewCatchUpRun run, AsideRecipe recipe) {
        if (run.getState() == ReviewCatchUpState.PAUSED_AUTH) {
            // The paused window, not the one after it.
            if (run.getPausedWindowStart() != null) {
                run.setCursorDay(run.getPausedWindowStart());
            }
            run.setPausedWindowStart(null);
            run.setStopReason(null);
            run.setState(ReviewCatchUpState.RUNNING);
            // What the wait cost goes to the paused total, not to the bound.
            run.endWaiting(clock.instant());
            // A new attempt, so the walled window gets a NEW child instead of the settled one coming back.
            run.setAttempt(run.getAttempt() + 1);
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
        // The attempt is in the key for the reason `ReviewCatchUpRun.attempt` records: without it, a window
        // that has already settled can never be queued again, because `dispatch` returns the settled row.
        String clientJobId = "cu-" + run.getId().toString().substring(0, 8) + "-" + start + "-" + end
                + "-a" + run.getAttempt();
        return jobs.dispatch(AsideDispatch.catchUpWindow(run.getOrgId(), run.getSellerAccountId(), recipe,
                run.getTriggerSource(), clientJobId, run.getId(), start, end));
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
        // <b>기계가 일한 시간만 센다.</b> 이 상한은 한 번의 누름이 기계를 얼마나 오래 쓰는지를 묶는 것이고,
        // 사람이 로그인하는 시간은 그 둘 중 어느 것도 아니다. started_at부터 재던 동안에는, 로그인에 10분을
        // 쓴 판매자가 벽을 넘겨 살아난 intent를 다음 창에서 잃었다.
        if (run.machineElapsed(clock.instant()).compareTo(limits.maxElapsed()) >= 0) {
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
