package com.sellerops.coverage.catchup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>One press, and the several periods it set out to read.</b>
 *
 * <p>The seller pressed 「지금 수집하기」 once. That press authorised a bounded catch-up — the days between the
 * coverage boundary and today — and asking them to press again for each seven-day window would be asking them
 * to re-approve a decision they already made, six times, for a shape they cannot see.
 *
 * <p><b>Why this is not {@code run_id}.</b> That column means «a Responsibility run asked for this», and
 * {@link com.sellerops.responsibility.aside.AsideDispatch} refuses an OPERATOR job that carries one, because
 * the audit trail would then say the autonomous lane requested a read a person requested. That rule is right.
 * So an operator-owned parent is its own row, and a child job carries both: the trigger that authorised it
 * (OPERATOR) and the intent it belongs to (this).
 *
 * <h2>The walk</h2>
 *
 * {@link #cursorDay} is the next day to read and {@link #stepDays} is how many days the next window spans.
 * A window that comes back complete advances the cursor past it and resets the step. A window that came back
 * at its own row ceiling halves the step and re-reads from the same cursor — deterministically, so a seven-day
 * window becomes three days and then the remaining four, and never two overlapping halves or a day skipped
 * between them ({@link #splitThrough} is what remembers the four).
 *
 * <h2>Two spans, one walk (2026-10-13)</h2>
 *
 * A walk may also read <b>history</b>: the days before the covered stretch, back to the setting's recorded
 * {@code history_from}. It walks the forward gaps first ({@link Phase#FORWARD}: cursor = the next window's first
 * day, oldest first), then turns to history ({@link Phase#HISTORY}: cursor = the next window's <b>last</b> day,
 * newest first, {@link #HISTORY_STEP_DAYS} at a time). A split in history reads the newer half first and
 * {@link #splitThrough} then remembers the older edge of the window being split.
 */
@Entity
@Table(name = "review_catch_up_run")
@Getter
@Setter
public class ReviewCatchUpRun {

    /** The step a press starts with, and returns to after every complete window. */
    public static final int DEFAULT_STEP_DAYS = 7;

    /** The step a history window starts with — see {@code ReviewCatchUpPlan#HISTORY_STEP_DAYS}. */
    public static final int HISTORY_STEP_DAYS = com.sellerops.coverage.ReviewCatchUpPlan.HISTORY_STEP_DAYS;

    /** Which span the walk is in. Forward gaps first, then history. */
    public enum Phase {
        /** After the coverage boundary, oldest first. {@link #cursorDay} is the next window's first day. */
        FORWARD,
        /** Before the covered stretch, newest first. {@link #cursorDay} is the next window's last day. */
        HISTORY
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "seller_account_id", nullable = false)
    private UUID sellerAccountId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "data_type", nullable = false, length = 16)
    private String dataType;

    /**
     * <b>Who started this walk — and therefore what every one of its children is authorised by.</b>
     *
     * <p>Stored rather than inferred. A walk is the same program whoever starts it, so the only way to know
     * whether its next window is being read because a person pressed or because their automatic check came due
     * is to have written it down when the walk began. The children carry it onto their own rows, which is what
     * keeps {@code scheduled_aside_job.trigger_source} honest across a walk that spans hours and a resume.
     *
     * <p>It is also what a resume must not change: a run paused at a sign-in wall and continued after the seller
     * signed in is still the run that was started by whoever started it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 16)
    private com.sellerops.responsibility.aside.AsideTrigger triggerSource =
            com.sellerops.responsibility.aside.AsideTrigger.OPERATOR;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private ReviewCatchUpState state;

    @Column(name = "requested_from", nullable = false)
    private LocalDate requestedFrom;

    @Column(name = "requested_through", nullable = false)
    private LocalDate requestedThrough;

    @Column(name = "cursor_day", nullable = false)
    private LocalDate cursorDay;

    @Column(name = "step_days", nullable = false)
    private int stepDays = DEFAULT_STEP_DAYS;

    /**
     * The far edge of the window being split, or null. FORWARD: its last day. HISTORY: its first (oldest) day.
     */
    @Column(name = "split_through")
    private LocalDate splitThrough;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 8)
    private Phase phase = Phase.FORWARD;

    /**
     * The history span this walk set out to read, or both null when it reads none. {@code historyFrom} is the
     * setting's recorded start (clamped to a calendar boundary already met); {@code historyThrough} is the day
     * before the covered stretch when the walk began, or yesterday when nothing was covered.
     */
    @Column(name = "history_from")
    private LocalDate historyFrom;

    @Column(name = "history_through")
    private LocalDate historyThrough;

    @Column(name = "windows_done", nullable = false)
    private int windowsDone;

    @Column(name = "rows_observed", nullable = false)
    private int rowsObserved;

    @Column(name = "days_covered", nullable = false)
    private int daysCovered;

    /**
     * <b>Which resume attempt this run is on — part of every child's client id.</b>
     *
     * <p>A child's id is derived from the intent and the window so that pressing twice converges on one job
     * instead of queueing a rival. That held, and it also meant a window could not be queued a SECOND time
     * after it had settled: {@code dispatch} finds the existing row by that id and returns it, whatever state
     * it is in. On 2026-10-09 a window walled by a sign-in was resumed, the run went RUNNING, the settled row
     * came back, nothing reached the desk, and the run sat there with no work and no way to notice.
     *
     * <p>So the attempt joins the key. A resume makes a NEW child for the same window, the row that recorded
     * the wall stays exactly as it was, and two presses inside one attempt still converge.
     */
    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    /**
     * <b>How long this intent has spent waiting on a person, in total.</b>
     *
     * <p>The press bound ({@code maxElapsed}) exists to stop one press from using the machine for too long.
     * It was measured from {@code startedAt}, which meant a seller who took ten minutes to sign in came back
     * to an intent that survived the wall and then died on the next window — the bound had been spent by a
     * human, not by any work. {@code startedAt} stays what it says it is; this is subtracted before the bound
     * is applied.
     */
    @Column(name = "paused_ms", nullable = false)
    private long pausedMs;

    /** When the current wait began, or null when nothing is being waited on. Folded into {@link #pausedMs}. */
    @Column(name = "paused_since")
    private Instant pausedSince;

    /** The window that met the sign-in wall — so a resume starts there and not one window later. */
    @Column(name = "paused_window_start")
    private LocalDate pausedWindowStart;

    @Column(name = "stop_reason", length = 32)
    private String stopReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * The press's own id, when a press started this walk. <b>Null for a walk the automatic check started</b> —
     * there was no request, and a synthesised id would make 「같은 누름이 다시 찾아온 의도」 answer yes for two
     * ticks that have nothing to do with each other.
     */
    @Column(name = "client_request_id", length = 64)
    private String clientRequestId;

    /**
     * The next period to read <b>in the current span</b>, clamped to it. Null when this span has nothing left —
     * see {@link #nextWindowOrHistory} for the walk as a whole.
     */
    public java.time.LocalDate[] nextWindow() {
        if (phase == Phase.HISTORY) {
            if (cursorDay == null || historyFrom == null || cursorDay.isBefore(historyFrom)) {
                return null;
            }
            LocalDate start = cursorDay.minusDays(Math.max(stepDays, 1) - 1L);
            if (start.isBefore(historyFrom)) {
                start = historyFrom;
            }
            return new LocalDate[] {start, cursorDay};
        }
        if (cursorDay == null || cursorDay.isAfter(requestedThrough)) {
            return null;
        }
        LocalDate end = cursorDay.plusDays(Math.max(stepDays, 1) - 1L);
        if (end.isAfter(requestedThrough)) {
            end = requestedThrough;
        }
        return new LocalDate[] {cursorDay, end};
    }

    /**
     * The next period of the walk as a whole: the current span's, or — when the forward gaps are done and this
     * walk has a history span — the newest history window, turning the walk to {@link Phase#HISTORY}.
     */
    public java.time.LocalDate[] nextWindowOrHistory() {
        LocalDate[] next = nextWindow();
        if (next == null && turnToHistory()) {
            next = nextWindow();
        }
        return next;
    }

    /** Forward gaps done: start the history span at its newest day. False when there is none to turn to. */
    boolean turnToHistory() {
        if (phase != Phase.FORWARD || historyFrom == null || historyThrough == null
                || historyThrough.isBefore(historyFrom)) {
            return false;
        }
        phase = Phase.HISTORY;
        cursorDay = historyThrough;
        stepDays = HISTORY_STEP_DAYS;
        splitThrough = null;
        return true;
    }

    /** The intent starts waiting on a person. Idempotent: a second call does not restart the clock. */
    public void beginWaiting(Instant now) {
        if (pausedSince == null) {
            pausedSince = now;
        }
    }

    /** The wait is over. What it cost is added to the total, and nothing else about the run moves. */
    public void endWaiting(Instant now) {
        if (pausedSince != null) {
            long waited = now.toEpochMilli() - pausedSince.toEpochMilli();
            if (waited > 0) {
                pausedMs += waited;
            }
            pausedSince = null;
        }
    }

    /**
     * How long the machine has actually been working on this intent.
     *
     * <p>Wall-clock since the press, minus every wait on a person — including one still in progress, so the
     * answer is the same whether it is asked during a wait or after it.
     */
    public java.time.Duration machineElapsed(Instant now) {
        long waiting = pausedSince == null ? 0L : Math.max(0L, now.toEpochMilli() - pausedSince.toEpochMilli());
        long total = now.toEpochMilli() - startedAt.toEpochMilli();
        return java.time.Duration.ofMillis(Math.max(0L, total - pausedMs - waiting));
    }

    /** A window came back whole: step past it, and go back to the default stride. */
    public void completed(LocalDate windowStart, LocalDate windowEnd, int observed) {
        windowsDone += 1;
        rowsObserved += Math.max(observed, 0);
        daysCovered += (int) (windowEnd.toEpochDay() - windowStart.toEpochDay() + 1);
        if (phase == Phase.HISTORY) {
            cursorDay = windowStart.minusDays(1);
            if (splitThrough != null && !cursorDay.isBefore(splitThrough)) {
                // Still inside the window that was split: the remainder is its older part, exactly.
                stepDays = (int) (cursorDay.toEpochDay() - splitThrough.toEpochDay() + 1);
            } else {
                splitThrough = null;
                stepDays = HISTORY_STEP_DAYS;
            }
            return;
        }
        cursorDay = windowEnd.plusDays(1);
        if (splitThrough != null && !cursorDay.isAfter(splitThrough)) {
            // Still inside the window that was split: the remainder is the rest of it, exactly.
            stepDays = (int) (splitThrough.toEpochDay() - cursorDay.toEpochDay() + 1);
        } else {
            splitThrough = null;
            stepDays = DEFAULT_STEP_DAYS;
        }
    }

    /**
     * A window came back at its own ceiling: halve it and look again at the same days.
     *
     * @return false when the window is a single day, which cannot be narrowed by date
     */
    public boolean split(LocalDate windowStart, LocalDate windowEnd) {
        long days = windowEnd.toEpochDay() - windowStart.toEpochDay() + 1;
        if (days < 2) {
            return false;
        }
        if (phase == Phase.HISTORY) {
            // Newest half first, so the walk keeps moving from new to old; the older edge is remembered.
            if (splitThrough == null) {
                splitThrough = windowStart;
            }
            cursorDay = windowEnd;
            stepDays = (int) (days / 2);
            return true;
        }
        if (splitThrough == null) {
            splitThrough = windowEnd;
        }
        cursorDay = windowStart;
        stepDays = (int) (days / 2);
        return true;
    }
}
