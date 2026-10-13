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
 * <p><b>And a child that never reports is the one case that needs asking about.</b> «The walk stops the moment
 * the helper does» was written as a safety property and is also a liveness bug: a child that expires unclaimed
 * or is abandoned mid-lease never reaches {@code settle}, so nothing calls {@link #advance} and the walk waits
 * for it forever — see {@link #stopIfStranded}.
 *
 * <h2>How it ends</h2>
 *
 * Five ways, four of which are not failures:
 *
 * <ul>
 *   <li><b>COMPLETE</b> — the cursor passed yesterday, and any history span reached its start. Also COMPLETE,
 *   with stop reason {@code HISTORY_BOUNDARY}: a history window met a day the marketplace calendar will not
 *   select — the store's reachable history ends there, which is the end of the walk rather than a failure.</li>
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
     * What one walk — a press or an automatic check — may set in motion.
     *
     * <p>Four ceilings because a catch-up runs long in four independent ways. Measured against this store: a
     * 7-day NAVER window held 45 rows and settled in 14 seconds.
     *
     * <p><b>Raised 2026-10-13 (product-owner decision: a year of history, unattended).</b> The old 8 windows /
     * 70 days / 3,000 rows / 6 minutes made a year of history take five or more walks an hour apart. A year is
     * 12 history windows of 31 days plus a few forward ones; 24 windows and 400 days let it finish in one walk,
     * or two when splits eat the window budget. 12,000 rows is roughly a year of a store four times busier than
     * the measured one; 20 machine-minutes is 24 windows at ~30 s each with room for splits. The bounds still
     * exist because an unattended walk that never ends is a different product.
     */
    public record Limits(int maxWindows, int maxDays, int maxRows, Duration maxElapsed) {

        public static final int WALK_MAX_WINDOWS = 24;
        public static final int WALK_MAX_DAYS = 400;
        public static final int WALK_MAX_ROWS = 12_000;
        public static final Duration WALK_MAX_ELAPSED = Duration.ofMinutes(20);

        /** The bounds of one walk, whoever started it. Named PRESS for history; the automatic check uses it too. */
        public static final Limits PRESS = new Limits(WALK_MAX_WINDOWS, WALK_MAX_DAYS, WALK_MAX_ROWS,
                WALK_MAX_ELAPSED);

        public Limits {
            if (maxWindows < 1 || maxDays < 1 || maxRows < 1) {
                throw new IllegalArgumentException("a bound below one is not a bound");
            }
            if (maxElapsed == null || maxElapsed.isZero() || maxElapsed.isNegative()) {
                throw new IllegalArgumentException("a catch-up needs a positive elapsed bound");
            }
        }
    }

    /**
     * <b>How long an automatic check leaves history alone after a walk stopped inside it.</b>
     *
     * <p>A history window that failed or saturated would otherwise be planned again by the very next hourly
     * tick, fail again, and — because a tick that starts a walk does not also refresh today — the seller's
     * freshness would stop moving while the walk kept walking into the same wall. A day is long enough for
     * that not to happen and short enough that a transient failure costs one day of history, not the year.
     * A person's press is never held back by this.
     */
    public static final Duration HISTORY_RETRY = Duration.ofHours(24);

    /** Why a history walk ended at a day the marketplace calendar would not select. */
    public static final String HISTORY_BOUNDARY = "HISTORY_BOUNDARY";

    /** The window lane's word for a day the marketplace calendar disabled (or the month step toward it). */
    static final String DATE_NOT_SELECTABLE = "DATE_NOT_SELECTABLE";

    /** The reader's word for a page that held more rows than it reads at once — a window to halve. */
    static final String TOO_MANY_ROWS = "TOO_MANY_ROWS";

    /** The window lane's word for a period the screen did not settle on exactly as asked. */
    static final String RANGE_MISMATCH = "RANGE_MISMATCH";

    private final ReviewCatchUpRunRepository runs;
    private final ReviewCoverageCursor coverage;
    private final ScheduledAsideJobService jobs;
    private final Clock clock;
    private final Limits limits;
    private final ReviewHistoryHorizon horizon;

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewCatchUpOrchestrator(ReviewCatchUpRunRepository runs, ReviewCoverageCursor coverage,
                                     ScheduledAsideJobService jobs,
                                     org.springframework.beans.factory.ObjectProvider<ReviewHistoryHorizon> horizon) {
        this(runs, coverage, jobs, Clock.systemUTC(), Limits.PRESS,
                horizon.getIfAvailable(() -> ReviewHistoryHorizon.NONE));
    }

    public ReviewCatchUpOrchestrator(ReviewCatchUpRunRepository runs, ReviewCoverageCursor coverage,
                                     ScheduledAsideJobService jobs, Clock clock, Limits limits) {
        this(runs, coverage, jobs, clock, limits, ReviewHistoryHorizon.NONE);
    }

    public ReviewCatchUpOrchestrator(ReviewCatchUpRunRepository runs, ReviewCoverageCursor coverage,
                                     ScheduledAsideJobService jobs, Clock clock, Limits limits,
                                     ReviewHistoryHorizon horizon) {
        this.runs = runs;
        this.coverage = coverage;
        this.jobs = jobs;
        this.clock = clock;
        this.limits = limits;
        this.horizon = horizon == null ? ReviewHistoryHorizon.NONE : horizon;
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
     * paused at a sign-in wall is continued by {@link #resumeAfterSignIn} when the product sees the seller sign
     * in, and otherwise — rarely, six hours on — by {@link #resumeAfterWait}, because the product usually
     * CANNOT see it. Retrying a wall every hour is how a product teaches a seller that automatic means noisy;
     * waiting for an event that may never be observable is how it teaches them that automatic means nothing
     * happens.
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
        LocalDate historyFrom = historyFrom(orgId, sellerAccountId, dataType, trigger);
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(held, today,
                new ReviewCatchUpPlan.Limits(limits.maxWindows(), limits.maxRows(), limits.maxElapsed(),
                        ReviewCatchUpRun.DEFAULT_STEP_DAYS),
                historyFrom, ReviewCatchUpRun.HISTORY_STEP_DAYS);
        if (plan.windows().isEmpty()) {
            return Optional.empty();
        }
        CoveredWindow first = plan.windows().get(0);
        Optional<CoveredWindow> history = ReviewCatchUpPlan.historySpan(held, today, historyFrom);
        // The plan puts forward gaps before history, and every forward window lies after the history span — so
        // a first window that starts after it is a forward one, and one inside it means there were no gaps.
        boolean startsInHistory = history.isPresent() && !first.start().isAfter(history.get().end());
        Instant now = clock.instant();
        ReviewCatchUpRun run = new ReviewCatchUpRun();
        run.setOrgId(orgId);
        run.setSellerAccountId(sellerAccountId);
        run.setChannelId(channelId);
        run.setDataType(dataType);
        run.setState(ReviewCatchUpState.RUNNING);
        run.setTriggerSource(trigger);
        // <b>The real planned start — not a guess.</b> With a history span it is the setting's recorded
        // history_from (the product owner's one-year default, or the seller's own date); without one it is the
        // oldest forward gap, as before. Either way it is what this walk was asked to read, written down.
        run.setRequestedFrom(history.map(CoveredWindow::start)
                .filter(h -> h.isBefore(first.start()))
                .orElse(first.start()));
        history.ifPresent(h -> {
            run.setHistoryFrom(h.start());
            run.setHistoryThrough(h.end());
        });
        // <b>어제까지.</b> A walk closes history, and the day still being written is not history — it is read
        // separately, as freshness. Setting this to today would leave a window on a day whose coverage can never
        // be claimed, and the run would reach it and stop being able to finish.
        run.setRequestedThrough(ReviewCoverage.lastClosedDay(today));
        if (startsInHistory) {
            run.setPhase(ReviewCatchUpRun.Phase.HISTORY);
            run.setCursorDay(first.end());
            run.setStepDays(ReviewCatchUpRun.HISTORY_STEP_DAYS);
        } else {
            run.setCursorDay(first.start());
            run.setStepDays(ReviewCatchUpRun.DEFAULT_STEP_DAYS);
        }
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        run.setClientRequestId(requestId);
        runs.save(run);
        ScheduledAsideJob child = queue(run, recipe, first.start(), first.end());
        log.info("review catch-up: started run={} trigger={} from={} through={} phase={} windows={}",
                run.getId(), trigger, run.getRequestedFrom(), run.getRequestedThrough(), run.getPhase(),
                plan.windows().size());
        return Optional.of(new Started(run, child));
    }

    /**
     * The history start this walk plans to, or null for none.
     *
     * <p>The setting's recorded {@code history_from}, then two corrections, both from this row's own walks:
     *
     * <ul>
     *   <li><b>A calendar boundary already met is not asked again.</b> A walk that ended at
     *   {@link #HISTORY_BOUNDARY} recorded the first day the marketplace would not select; the start moves to
     *   the day after it. Without this every tick would plan those days and meet the same disabled calendar.</li>
     *   <li><b>An automatic check leaves history alone for {@link #HISTORY_RETRY} after a walk stopped in it</b>
     *   (FAILED or STOPPED_SATURATED in the HISTORY phase). A press is a person asking, and is not held back.</li>
     * </ul>
     */
    private LocalDate historyFrom(UUID orgId, UUID sellerAccountId, String dataType, AsideTrigger trigger) {
        LocalDate recorded = horizon.historyFrom(orgId, sellerAccountId).orElse(null);
        if (recorded == null) {
            return null;
        }
        LocalDate boundary = runs.findFirstBySellerAccountIdAndDataTypeAndStopReasonAndFinishedAtIsNotNullOrderByFinishedAtDesc(
                        sellerAccountId, dataType, HISTORY_BOUNDARY)
                .filter(r -> orgId.equals(r.getOrgId()))
                .map(ReviewCatchUpRun::getPausedWindowStart)
                .orElse(null);
        if (boundary != null && !boundary.isBefore(recorded)) {
            recorded = boundary.plusDays(1);
        }
        if (trigger == AsideTrigger.SCHEDULED) {
            boolean heldBack = runs.findFirstBySellerAccountIdAndDataTypeAndFinishedAtIsNotNullOrderByFinishedAtDesc(
                            sellerAccountId, dataType)
                    .filter(r -> orgId.equals(r.getOrgId()))
                    .filter(r -> r.getPhase() == ReviewCatchUpRun.Phase.HISTORY)
                    .filter(r -> r.getState() == ReviewCatchUpState.FAILED
                            || r.getState() == ReviewCatchUpState.STOPPED_SATURATED)
                    .filter(r -> r.getFinishedAt().plus(HISTORY_RETRY).isAfter(clock.instant()))
                    .isPresent();
            if (heldBack) {
                return null;
            }
        }
        return recorded;
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
        return resumePaused(orgId, sellerAccountId, dataType, recipe, "SIGN_IN");
    }

    /**
     * <b>The same window again, because enough time has passed that looking is cheaper than waiting.</b>
     *
     * <p>Same transition as {@link #resumeAfterSignIn} — PAUSED_AUTH → RUNNING, cursor back on the walled
     * window, a fresh attempt — and that is deliberate: there is one way to continue a walled walk, and two
     * names for who decided to. What differs is only the decider, and the audit trail keeps them apart because
     * the child's trigger is still the walk's own.
     *
     * <p><b>Why a timer may ask at all, when this class says it must not.</b> The paragraph on
     * {@link #resumeAfterSignIn} is right about the wall and wrong about the session, and
     * {@code ReviewAutoCheckClaimer#AUTH_RETRY} already records the measurement that showed it: on 2026-10-09
     * the seller signed in directly in their own browser, the session came back, and the lane had no way to be
     * told — because the only thing that clears a wall is a successful read, and the lane was refusing to
     * perform one. That argument was applied to the walk-less refresh and <b>not</b> to a paused walk, which
     * short-circuits the turn before the probe is reached. A walk is the half with more to lose: a new one-day
     * window opens at every KST midnight, so a signed-out seller parks a walk within a day and the whole
     * standing lane then produces nothing until a person presses something.
     *
     * <p>So the caller — not this class — holds the interval, and it is the same six hours the walk-less path
     * uses. Nothing new runs on anyone's computer to support this: the read is its own probe.
     */
    @Transactional
    public Optional<Started> resumeAfterWait(UUID orgId, UUID sellerAccountId, String dataType,
                                             AsideRecipe recipe) {
        return resumePaused(orgId, sellerAccountId, dataType, recipe, "WAIT");
    }

    private Optional<Started> resumePaused(UUID orgId, UUID sellerAccountId, String dataType, AsideRecipe recipe,
                                           String asker) {
        Optional<ReviewCatchUpRun> paused = runs.findFirstBySellerAccountIdAndDataTypeAndStateIn(
                sellerAccountId, dataType, EnumSet.of(ReviewCatchUpState.PAUSED_AUTH))
                .filter(run -> orgId.equals(run.getOrgId()));
        if (paused.isEmpty()) {
            return Optional.empty();
        }
        log.info("review catch-up: resuming a walled walk run={} asked-by={}", paused.get().getId(), asker);
        return Optional.of(resume(paused.get(), recipe));
    }

    /**
     * <b>A walk that is waiting for a child nobody will ever report on.</b>
     *
     * <p>{@link #advance} is reachable from exactly one place — {@link ScheduledAsideJobService#settle} — and a
     * child that was never claimed, or claimed and then abandoned, does not settle: it is relabelled EXPIRED by
     * a bulk UPDATE, and the walk is left RUNNING with nothing on any desk. Nothing in this runtime notices,
     * and three things then conspire to make it permanent: {@code uq_review_catch_up_live} refuses a second
     * live walk for the row, so no fresh walk can ever be started; the automatic check reads «a walk is already
     * running» and stands down every hour, exactly as it should; and the seller's queue simply goes quiet. Over
     * one 10-minute absence of a helper — a sleep, a restart, a dropped network — the standing lane is over,
     * and nothing anywhere says so.
     *
     * <p><b>Stopped, not resumed.</b> The windows this walk completed still stand, because coverage is read from
     * the evidence those windows left, so the honest move is to say where it stopped and let the next turn plan
     * again from the real boundary. Resuming would mean this method deciding how many times a walk may lose its
     * child, which is a bound nothing has measured; a fresh plan needs no such number and re-derives everything
     * from the database, like the rest of this runtime.
     *
     * @return whether a stranded walk was stopped — false both for a walk that is genuinely working and for one
     *         that is not RUNNING at all, so a caller may treat false as «leave this alone»
     */
    @Transactional
    public boolean stopIfStranded(UUID catchUpRunId) {
        ReviewCatchUpRun run = runs.findById(catchUpRunId).orElse(null);
        if (run == null || run.getState() != ReviewCatchUpState.RUNNING) {
            return false;
        }
        if (jobs.hasLiveChild(run.getId())) {
            return false;
        }
        stop(run, ReviewCatchUpState.FAILED, "CHILD_LOST");
        return true;
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
        if (child.getOutcome() != AsideJobOutcome.OBSERVED && TOO_MANY_ROWS.equals(child.getFailureCode())) {
            // <b>More rows than one page reads — the same fact as a PARTIAL window, said by the reader instead
            // of the delivery.</b> It arrives as SURFACE_UNREADABLE + TOO_MANY_ROWS, and it used to end the walk
            // as FAILED: one busy month in a year of history stopped the year. Halve it and look again, down to a
            // single day, which still stops as DAY_SATURATED.
            if (!run.split(start, end)) {
                run.setPausedWindowStart(start);
                stop(run, ReviewCatchUpState.STOPPED_SATURATED, "DAY_SATURATED");
                return;
            }
            continueWalk(run, child);
            return;
        }
        if (child.getOutcome() != AsideJobOutcome.OBSERVED && RANGE_MISMATCH.equals(child.getFailureCode())
                && run.getPhase() == ReviewCatchUpRun.Phase.HISTORY) {
            // <b>The screen settled on a different period than the one asked for — nothing was ingested.</b>
            // In history this is what a period longer than the marketplace allows looks like (2026-10-13: a
            // 31-day window ending one day past "start + one month − one day"). Halve it and ask again; a
            // shorter period is what the screen is telling us to ask for. Down to one day, where a mismatch
            // is no longer about length and the walk stops FAILED as before.
            if (run.split(start, end)) {
                continueWalk(run, child);
                return;
            }
            stop(run, ReviewCatchUpState.FAILED, RANGE_MISMATCH);
            return;
        }
        if (child.getOutcome() != AsideJobOutcome.OBSERVED && DATE_NOT_SELECTABLE.equals(child.getFailureCode())
                && run.getPhase() == ReviewCatchUpRun.Phase.HISTORY) {
            // <b>The marketplace calendar will not select a day in this history window.</b> Somewhere in it the
            // store's reachable history ends. Halve toward the newer half until one day is left, so the days
            // that ARE reachable are still read; that day is the boundary. Recorded on the run (paused window
            // start) so the next walk starts the day after it, and the walk ends COMPLETE — reading as far back
            // as the marketplace allows is the whole of what was asked. In a forward-gap window this is not a
            // boundary and falls through to FAILED below.
            if (run.split(start, end)) {
                continueWalk(run, child);
                return;
            }
            run.setPausedWindowStart(start);
            stop(run, ReviewCatchUpState.COMPLETE, HISTORY_BOUNDARY);
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
        continueWalk(run, child);
    }

    /** After a window was counted or split: the bound, then the next window — or the end of the walk. */
    private void continueWalk(ReviewCatchUpRun run, ScheduledAsideJob child) {
        Optional<String> bound = exceeded(run);
        if (bound.isPresent()) {
            stop(run, ReviewCatchUpState.STOPPED_LIMIT, bound.get());
            return;
        }
        LocalDate[] next = run.nextWindowOrHistory();
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
            // The paused window, not the one after it. In history the cursor already IS that window (its last
            // day), because a walled window never moved it.
            if (run.getPausedWindowStart() != null && run.getPhase() == ReviewCatchUpRun.Phase.FORWARD) {
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
        LocalDate[] next = run.nextWindowOrHistory();
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
