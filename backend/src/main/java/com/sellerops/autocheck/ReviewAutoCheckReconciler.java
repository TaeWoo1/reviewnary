package com.sellerops.autocheck;

import com.sellerops.common.ApiException;
import com.sellerops.coverage.catchup.ReviewCatchUpOrchestrator;
import com.sellerops.coverage.catchup.ReviewCatchUpRun;
import com.sellerops.coverage.catchup.ReviewCatchUpState;
import com.sellerops.coverage.ReviewCoverageCursor;
import com.sellerops.responsibility.aside.AsideDispatch;
import com.sellerops.responsibility.aside.AsideHelperBusyException;
import com.sellerops.responsibility.aside.AsideHelperUnavailableException;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>One turn of 「새 리뷰를 자동으로 확인합니다」, for one account.</b>
 *
 * <p>Two shapes of work and the difference is what each can prove:
 *
 * <ul>
 *   <li><b>History is unread</b> → the catch-up walk, closing the days between the coverage boundary and
 *   <b>yesterday</b>, one named window at a time. The same walk a press starts; only the trigger differs.</li>
 *   <li><b>History is closed</b> → one read of {@code today … today}. Named, not windowless: an unattended read
 *   that took whatever period the screen happened to show would be unable to say afterwards what it covered,
 *   and the period a marketplace defaults to is not a thing this product chose. Today's read moves no boundary —
 *   a day still being written can never be claimed as covered — so what it produces is rows and a time we
 *   looked, which is exactly what freshness is.</li>
 * </ul>
 *
 * <h2>이 lane은 사람보다 앞서지 않는다</h2>
 *
 * <p>Four ways a turn ends without reading anything, and not one of them is an error:
 *
 * <ul>
 *   <li><b>a walk is already running</b> — it advances on its own children settling; a tick that queued a
 *   second window would be racing itself;</li>
 *   <li><b>a job is on the desk</b> — possibly one the seller pressed for. It is left alone. This lane never
 *   cancels, never pre-empts, and never takes a desk away from a person;</li>
 *   <li><b>no usable helper</b> → {@link AutoCheckPause#PAUSED_DEVICE}, which the next turn clears by itself the
 *   moment a helper is back. The seller's setting is untouched;</li>
 *   <li><b>a walk is waiting at the sign-in wall</b> → {@link AutoCheckPause#PAUSED_AUTH}, and this lane does
 *   <b>not</b> retry it. A wall does not open with time. It opens when the seller signs in, and that is what
 *   continues the very window it stopped on.</li>
 * </ul>
 */
@Service
public class ReviewAutoCheckReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReviewAutoCheckReconciler.class);

    /** Accounts per tick — backpressure, not throughput: each one may put a job on a person's computer. */
    public static final int BATCH_LIMIT = 20;

    /** What one tick did. Counts only — no account id, no org id, nothing a log must not carry. */
    public record TickReport(int walked, int refreshedToday, int pausedDevice, int pausedAuth, int skipped) {
    }

    private final ReviewAutoCheckClaimer claimer;
    private final ReviewAutoCheckService settings;
    private final SellerAccountRepository accounts;
    private final ReviewCatchUpOrchestrator catchUp;
    private final ScheduledAsideJobService jobs;
    private final Clock clock;

    /** The container's constructor — annotated because there are two (see {@code ReviewAutoCheckService}). */
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewAutoCheckReconciler(ReviewAutoCheckClaimer claimer, ReviewAutoCheckService settings,
                                     SellerAccountRepository accounts, ReviewCatchUpOrchestrator catchUp,
                                     ScheduledAsideJobService jobs) {
        this(claimer, settings, accounts, catchUp, jobs, Clock.systemUTC());
    }

    public ReviewAutoCheckReconciler(ReviewAutoCheckClaimer claimer, ReviewAutoCheckService settings,
                                     SellerAccountRepository accounts, ReviewCatchUpOrchestrator catchUp,
                                     ScheduledAsideJobService jobs, Clock clock) {
        this.claimer = claimer;
        this.settings = settings;
        this.accounts = accounts;
        this.catchUp = catchUp;
        this.jobs = jobs;
        this.clock = clock;
    }

    /**
     * One pass over the accounts that are due.
     *
     * <p><b>This never creates a setting.</b> A row exists because a seller connected the channel
     * ({@code ReviewAutoCheckConnectionListener}), opened their own setting, or asked for their organisation to
     * be backfilled — all three are acts inside one organisation. A background sweep that created rows would
     * make this tick the thing that decides whose stores get read, which is the seller's decision and not a
     * loop's; it would also mean every org that merely exists in a database gets picked up, and the only way
     * back from that is a list of organisations to skip — a fixture blacklist in product code, which is not a
     * thing this product may contain.
     */
    public TickReport tick(Instant now) {
        int walked = 0;
        int today = 0;
        int pausedDevice = 0;
        int pausedAuth = 0;
        int skipped = 0;
        for (ReviewAutoCheckClaimer.Claimed claimed : claimer.claimDue(now, BATCH_LIMIT)) {
            Outcome outcome;
            try {
                outcome = turn(claimed.row(), claimed.slot());
            } catch (RuntimeException e) {
                // One account's bad turn must not end the tick for the others. The next hour re-derives
                // everything from the database, so there is nothing to repair here.
                log.warn("review auto-check: turn failed type={}", e.getClass().getSimpleName());
                outcome = Outcome.SKIPPED;
            }
            claimer.settle(claimed.row(), now, outcome.pause(), outcome.looked());
            switch (outcome) {
                case WALKED -> walked++;
                case TODAY -> today++;
                case PAUSED_DEVICE -> pausedDevice++;
                case PAUSED_AUTH -> pausedAuth++;
                default -> skipped++;
            }
        }
        TickReport report = new TickReport(walked, today, pausedDevice, pausedAuth, skipped);
        if (walked + today + pausedDevice + pausedAuth > 0) {
            log.info("review auto-check: tick walked={} today={} pausedDevice={} pausedAuth={} skipped={}",
                    walked, today, pausedDevice, pausedAuth, skipped);
        }
        return report;
    }

    /** How one account's turn ended. */
    enum Outcome {
        WALKED, TODAY, PAUSED_DEVICE, PAUSED_AUTH, SKIPPED;

        AutoCheckPause pause() {
            return switch (this) {
                case PAUSED_DEVICE -> AutoCheckPause.PAUSED_DEVICE;
                case PAUSED_AUTH -> AutoCheckPause.PAUSED_AUTH;
                default -> null;
            };
        }

        /** Whether this turn actually asked a desk to read something. */
        boolean looked() {
            return this == WALKED || this == TODAY;
        }
    }

    Outcome turn(ReviewAutoCheck row, Instant slot) {
        UUID orgId = row.getOrgId();
        UUID accountId = row.getSellerAccountId();
        SellerAccount account = accounts.findByIdAndOrgId(accountId, orgId).orElse(null);
        if (account == null) {
            return Outcome.SKIPPED;
        }
        Optional<AsideRecipe> recipe = settings.recipeFor(account);
        if (recipe.isEmpty()) {
            return Outcome.SKIPPED;
        }
        Optional<ReviewCatchUpRun> live = catchUp.liveFor(accountId, ReviewAutoCheckService.DATA_TYPE);
        if (live.isPresent()) {
            return live.get().getState() == ReviewCatchUpState.PAUSED_AUTH ? Outcome.PAUSED_AUTH : Outcome.SKIPPED;
        }
        if (jobs.liveFor(orgId, accountId, recipe.get()).isPresent()) {
            // Someone's work is on this desk — quite possibly a read the seller pressed for. Leave it.
            return Outcome.SKIPPED;
        }
        if (metTheSignInWall(orgId, accountId, recipe.get())) {
            // <b>A wall with no walk behind it still has to be waited for.</b> Today's refresh belongs to no
            // catch-up run — a day that cannot be claimed as covered has no boundary to advance — so when it
            // ends at the channel's sign-in there is no run to hold PAUSED_AUTH for it. Without this, the next
            // hour would walk into the same wall, and the hour after that: a product generating noise about the
            // one thing only the seller can resolve. It is their sign-in that continues this
            // ({@code ScreenReadService#resumeAfterSignIn}), not a timer.
            return Outcome.PAUSED_AUTH;
        }
        try {
            if (catchUp.startScheduled(orgId, accountId, account.getChannelId(),
                    ReviewAutoCheckService.DATA_TYPE, recipe.get()).isPresent()) {
                return Outcome.WALKED;
            }
            return refreshToday(row, recipe.get(), slot) ? Outcome.TODAY : Outcome.SKIPPED;
        } catch (AsideHelperUnavailableException e) {
            return Outcome.PAUSED_DEVICE;
        } catch (AsideHelperBusyException e) {
            return Outcome.SKIPPED;
        } catch (ApiException e) {
            // The setting turned off between the claim and the dispatch, or the store could not be resolved.
            // Both are «nothing to do», and both are re-asked next hour.
            return Outcome.SKIPPED;
        }
    }

    /**
     * Whether the last finished read of this very screen was turned away at the channel's sign-in.
     *
     * <p>Derived from the job row that met it, never stored a second time: a copy of «this seller is signed
     * out» would be the thing that is wrong the moment they sign back in. Trigger-agnostic on purpose — a wall
     * is a fact about the marketplace session, not about which lane happened to find it, so a read the seller
     * pressed for that hit it also stops this lane from walking into it.
     */
    private boolean metTheSignInWall(UUID orgId, UUID accountId, AsideRecipe recipe) {
        return jobs.lastFinished(orgId, accountId, recipe)
                .map(job -> job.getOutcome() == com.sellerops.responsibility.aside.AsideJobOutcome.AUTH_REQUIRED)
                .orElse(false);
    }

    /**
     * One read of the day that has not closed yet, with its period named.
     *
     * <p><b>The job's id is this turn, not this day.</b> {@code (permission, slot)} — the setting row and the
     * instant it was due for. A retry of the same turn converges on one job
     * ({@code (device, client_job_id)} idempotency); the next hour's turn is a different turn and asks for its
     * own job, even though it reads the same day.
     *
     * <p>Keyed by the day, it dedupelicated the wrong thing: the 10:30 turn re-found the 09:30 turn's settled
     * row, dispatched nothing, and the automatic check quietly stopped checking for the rest of the day. The
     * day is still what gets READ — that is the window, and the window is not the identity of the asking.
     */
    private boolean refreshToday(ReviewAutoCheck row, AsideRecipe recipe, Instant slot) {
        LocalDate today = LocalDate.now(ReviewCoverageCursor.KST);
        String clientJobId = "ac-" + row.getId().toString().substring(0, 8) + "-" + slot.getEpochSecond();
        jobs.dispatch(AsideDispatch.scheduled(row.getOrgId(), row.getSellerAccountId(), recipe, clientJobId,
                today, today));
        return true;
    }
}
