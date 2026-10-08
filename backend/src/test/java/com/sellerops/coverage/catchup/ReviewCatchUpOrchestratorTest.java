package com.sellerops.coverage.catchup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.coverage.ReviewCoverageCursor;
import com.sellerops.auth.device.HelperDevice;
import com.sellerops.auth.device.HelperDeviceRepository;
import com.sellerops.responsibility.aside.AsideHelperDevices;
import com.sellerops.responsibility.IdentityVerdict;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideMarketplaceAccess;
import com.sellerops.responsibility.aside.AsideMarketplaceTarget;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;
import com.sellerops.reviewimport.ReviewImportPlan;
import com.sellerops.reviewimport.ReviewImportPlanRepository;
import com.sellerops.reviewimport.ReviewImportPlanStatus;
import com.sellerops.reviewimport.ReviewImportSegment;
import com.sellerops.reviewimport.ReviewImportSegmentRepository;
import com.sellerops.reviewimport.SegmentCoverageState;
import com.sellerops.reviewimport.SegmentExecutionState;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>One press, six windows, one desk — and every way it is allowed to stop.</b>
 *
 * <p>What this pins is the thing the seller experiences: they press once and the product works through what is
 * behind them, oldest first, without asking again. The two ways that could go wrong quietly are what the
 * assertions are about — a window counted without being read, and a walk that steps over something.
 *
 * <p>No helper here. Each child is settled the way the real one settles it, through the same
 * {@code settle} the device endpoint calls, which is also what makes the walk advance — there is no scheduler
 * in this system and a catch-up that needed one would be an autonomous lane wearing a press's authorisation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:operator_screen_read;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ReviewCatchUpOrchestratorTest {

    @Autowired ScheduledAsideJobRepository jobs;
    @Autowired SellerAccountRepository accounts;
    @Autowired ChannelRepository channels;
    @Autowired HelperDeviceRepository devices;
    @Autowired ReviewImportSegmentRepository segments;
    @Autowired ReviewImportPlanRepository plans;
    @Autowired ReviewCatchUpRunRepository runs;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private UUID org;
    private SellerAccount account;
    private Channel naver;
    private UUID deviceId;
    private ScheduledAsideJobService dispatcher;
    private ReviewCatchUpOrchestrator orchestrator;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        runs.deleteAll();
        jobs.deleteAll();
        segments.deleteAll();
        plans.deleteAll();
        devices.deleteAll();
        accounts.deleteAll();
        org = UUID.randomUUID();
        today = LocalDate.now(KST);

        naver = channels.findByCode("NAVER").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("NAVER");
            c.setNameKo("네이버 스마트스토어");
            c.setStatus(com.sellerops.channel.ChannelStatus.AVAILABLE);
            c.setSupportsReview(true);
            c.setSupportsInquiry(true);
            c.setSupportsOrder(true);
            c.setSupportsSales(true);
            c.setSupportsProduct(true);
            c.setSortOrder(0);
            return channels.save(c);
        });
        account = new SellerAccount();
        account.setOrgId(org);
        account.setChannelId(naver.getId());
        account.setConnectionStatus(com.sellerops.channel.ChannelStatus.CONNECTED);
        account.setFileUpload(false);
        account = accounts.save(account);

        HelperDevice device = new HelperDevice();
        device.setOrgId(org);
        device.setUserId(UUID.randomUUID());
        device.setTokenHash("hash-" + UUID.randomUUID());
        device.setDeviceName("Mac (arm64)");
        device.setHelperVersion("0.3.0");
        device.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        deviceId = devices.save(device).getId();

        AsideMarketplaceTarget resolver = new AsideMarketplaceTarget() {
            @Override
            public Optional<Target> resolve(UUID orgId, AsideRecipe recipe) {
                return Optional.empty();
            }

            @Override
            public Optional<Target> resolveFor(UUID orgId, UUID sellerAccountId, AsideRecipe recipe) {
                return Optional.of(new Target(sellerAccountId, "slot-1", "digest"));
            }
        };
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        dispatcher = new ScheduledAsideJobService(jobs, clock,
                new AsideMarketplaceAccess(false, Set.of(), Set.of()), resolver,
                new AsideHelperDevices(devices, clock));
        orchestrator = new ReviewCatchUpOrchestrator(runs,
                new ReviewCoverageCursor(segments, channels, jobs), dispatcher, clock,
                ReviewCatchUpOrchestrator.Limits.PRESS);
        dispatcher.setSettledListener(orchestrator::advance);
    }

    /** The boundary this organisation really has: a reconciled period import ending 36 days before today. */
    private void coveredThrough(int daysBeforeToday) {
        ReviewImportPlan plan = new ReviewImportPlan();
        plan.setOrgId(org);
        plan.setChannelId(naver.getId());
        plan.setSellerAccountId(account.getId());
        plan.setRequestedStart(today.minusDays(daysBeforeToday + 60L));
        plan.setRequestedEnd(today.minusDays(daysBeforeToday));
        plan.setStatus(ReviewImportPlanStatus.COMPLETED);
        plans.save(plan);
        ReviewImportSegment segment = new ReviewImportSegment();
        segment.setPlanId(plan.getId());
        segment.setOrgId(org);
        segment.setOrdinal(0);
        segment.setSegmentStart(plan.getRequestedStart());
        segment.setSegmentEnd(plan.getRequestedEnd());
        segment.setExecutionState(SegmentExecutionState.COMPLETED);
        segment.setCoverageState(SegmentCoverageState.COVERED);
        segment.setCoveredRows(100);
        segments.save(segment);
    }

    private Optional<ReviewCatchUpOrchestrator.Started> press(String requestId) {
        return orchestrator.start(org, account.getId(), naver.getId(), "REVIEW",
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1, requestId);
    }

    /** Settle one child exactly as the helper does: the delivery writes the period, then the report lands. */
    private void settleAsRead(ScheduledAsideJob child, int observed, int capacity) {
        jobs.findById(child.getId()).ifPresent(live -> {
            live.setIdentityVerdict(IdentityVerdict.MATCH);
            live.setWindowStart(live.getRequestedWindowStart());
            live.setWindowEnd(live.getRequestedWindowEnd());
            live.setObservedCapacity(capacity);
            live.setDeliveryCompleteness(observed < capacity
                    ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            live.setInsertedCount(observed);
            live.setChangedCount(0);
            jobs.save(live);
        });
        claim();
        dispatcher.settle(org, deviceId, child.getId(), AsideJobOutcome.OBSERVED, observed, null);
    }

    private void settleAsAuthWall(ScheduledAsideJob child) {
        claim();
        dispatcher.settle(org, deviceId, child.getId(), AsideJobOutcome.AUTH_REQUIRED, null, null);
    }

    private void claim() {
        dispatcher.claim(org, deviceId);
    }

    /** The child now on the desk, or empty. One per desk is a schema rule; this reads it back. */
    private Optional<ScheduledAsideJob> onDesk() {
        return jobs.findAll().stream()
                .filter(j -> j.getStatus() != ScheduledAsideJobStatus.SETTLED
                        && j.getStatus() != ScheduledAsideJobStatus.EXPIRED)
                .findFirst();
    }

    @Test
    @DisplayName("one press walks the whole gap: six windows, oldest first, nobody pressing again")
    void onePressWalksTheGap() {
        coveredThrough(36);

        ReviewCatchUpOrchestrator.Started started = press("press-1").orElseThrow();
        assertThat(started.run().getRequestedFrom()).isEqualTo(today.minusDays(35));
        assertThat(started.run().getRequestedThrough()).isEqualTo(today);
        // The first window is the oldest missing one, not the last seven days.
        assertThat(started.firstJob().getRequestedWindowStart()).isEqualTo(today.minusDays(35));
        assertThat(started.firstJob().getRequestedWindowEnd()).isEqualTo(today.minusDays(29));
        assertThat(started.firstJob().getTrigger().name()).isEqualTo("OPERATOR");
        // Never a Responsibility run id: a press that carried one would make the audit trail name the
        // autonomous lane as the requester.
        assertThat(started.firstJob().getRunId()).isNull();

        List<String> walked = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Optional<ScheduledAsideJob> child = onDesk();
            if (child.isEmpty()) {
                break;
            }
            walked.add(child.get().getRequestedWindowStart() + "~" + child.get().getRequestedWindowEnd());
            // One job per desk at a time, always.
            assertThat(jobs.findAll().stream().filter(j -> j.getStatus() == ScheduledAsideJobStatus.QUEUED
                    || j.getStatus() == ScheduledAsideJobStatus.CLAIMED)).hasSize(1);
            settleAsRead(child.get(), 45, 500);
        }

        assertThat(walked).containsExactly(
                today.minusDays(35) + "~" + today.minusDays(29),
                today.minusDays(28) + "~" + today.minusDays(22),
                today.minusDays(21) + "~" + today.minusDays(15),
                today.minusDays(14) + "~" + today.minusDays(8),
                today.minusDays(7) + "~" + today.minusDays(1),
                today + "~" + today);
        ReviewCatchUpRun run = runs.findById(started.run().getId()).orElseThrow();
        assertThat(run.getState()).isEqualTo(ReviewCatchUpState.COMPLETE);
        assertThat(run.getWindowsDone()).isEqualTo(6);
        assertThat(run.getDaysCovered()).isEqualTo(36);
        assertThat(run.getRowsObserved()).isEqualTo(270);
    }

    @Test
    @DisplayName("a saturated window is split, not skipped — and the days it holds are read")
    void saturationSplits() {
        coveredThrough(36);
        ScheduledAsideJob first = press("press-1").orElseThrow().firstJob();
        LocalDate windowStart = first.getRequestedWindowStart();

        // At its own ceiling: the rows are real and stored, but «there were no more» cannot be said.
        settleAsRead(first, 500, 500);

        ScheduledAsideJob next = onDesk().orElseThrow();
        // The same days again, narrower — three of the seven, deterministically.
        assertThat(next.getRequestedWindowStart()).isEqualTo(windowStart);
        assertThat(next.getRequestedWindowEnd()).isEqualTo(windowStart.plusDays(2));
        ReviewCatchUpRun run = runs.findById(first.getCatchUpRunId()).orElseThrow();
        assertThat(run.getState()).isEqualTo(ReviewCatchUpState.RUNNING);
        // Nothing was counted: a saturated window proves no days.
        assertThat(run.getWindowsDone()).isZero();
        assertThat(run.getDaysCovered()).isZero();

        settleAsRead(next, 200, 500);
        // And then the rest of the window that was split — exactly the rest.
        ScheduledAsideJob remainder = onDesk().orElseThrow();
        assertThat(remainder.getRequestedWindowStart()).isEqualTo(windowStart.plusDays(3));
        assertThat(remainder.getRequestedWindowEnd()).isEqualTo(windowStart.plusDays(6));
    }

    @Test
    @DisplayName("a single day at its ceiling stops the walk there — nothing past it is claimed as read")
    void oneDaySaturatedStops() {
        coveredThrough(3);
        ScheduledAsideJob child = press("press-1").orElseThrow().firstJob();
        LocalDate start = child.getRequestedWindowStart();
        // 3 days → saturated → 1 day → saturated again.
        settleAsRead(child, 500, 500);
        ScheduledAsideJob oneDay = onDesk().orElseThrow();
        assertThat(oneDay.getRequestedWindowStart()).isEqualTo(oneDay.getRequestedWindowEnd());
        settleAsRead(oneDay, 500, 500);

        assertThat(onDesk()).isEmpty();
        ReviewCatchUpRun run = runs.findById(child.getCatchUpRunId()).orElseThrow();
        assertThat(run.getState()).isEqualTo(ReviewCatchUpState.STOPPED_SATURATED);
        assertThat(run.getStopReason()).isEqualTo("DAY_SATURATED");
        assertThat(run.getPausedWindowStart()).isEqualTo(start);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("a login wall pauses at that window, and pressing again after signing in reads THAT window")
    void authPausesAndResumesTheSameWindow() {
        coveredThrough(36);
        ScheduledAsideJob first = press("press-1").orElseThrow().firstJob();
        settleAsRead(first, 45, 500);
        ScheduledAsideJob second = onDesk().orElseThrow();
        LocalDate walled = second.getRequestedWindowStart();

        settleAsAuthWall(second);

        assertThat(onDesk()).as("nothing is queued behind a login wall").isEmpty();
        ReviewCatchUpRun run = runs.findById(first.getCatchUpRunId()).orElseThrow();
        assertThat(run.getState()).isEqualTo(ReviewCatchUpState.PAUSED_AUTH);
        assertThat(run.getPausedWindowStart()).isEqualTo(walled);
        // The first window still counts. A login wall does not un-read what was read before it.
        assertThat(run.getWindowsDone()).isEqualTo(1);

        // The seller signs in and presses again — the same intent resumes at the window that was walled, not
        // at the one after it.
        ScheduledAsideJob resumed = press("press-2").orElseThrow().firstJob();
        assertThat(resumed.getRequestedWindowStart()).isEqualTo(walled);
        assertThat(runs.findById(run.getId()).orElseThrow().getState()).isEqualTo(ReviewCatchUpState.RUNNING);
    }

    @Test
    @DisplayName("the same press re-finds its own intent; a second press does not start a rival walk")
    void oneIntentPerRow() {
        coveredThrough(36);
        ReviewCatchUpRun run = press("press-1").orElseThrow().run();
        // The same id: idempotent, and it does not put a second job on the desk.
        assertThat(press("press-1").orElseThrow().run().getId()).isEqualTo(run.getId());
        assertThat(press("press-1").orElseThrow().firstJob()).isNull();
        assertThat(runs.count()).isEqualTo(1);
        // A different id while one is live: the live intent is in charge, and the desk still holds one job.
        press("press-2");
        assertThat(runs.count()).isEqualTo(1);
        assertThat(jobs.findAll().stream().filter(j -> j.getStatus() == ScheduledAsideJobStatus.QUEUED)).hasSize(1);
    }

    @Test
    @DisplayName("one press is bounded — the walk stops with the bound named, and pressing again continues")
    void boundedByWindows() {
        coveredThrough(60);
        ReviewCatchUpOrchestrator tight = new ReviewCatchUpOrchestrator(runs,
                new ReviewCoverageCursor(segments, channels, jobs), dispatcher,
                Clock.fixed(Instant.now(), ZoneOffset.UTC),
                new ReviewCatchUpOrchestrator.Limits(2, 70, 3_000, Duration.ofMinutes(6)));
        dispatcher.setSettledListener(tight::advance);

        ScheduledAsideJob child = tight.start(org, account.getId(), naver.getId(), "REVIEW",
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1, "press-1").orElseThrow().firstJob();
        for (int i = 0; i < 6 && child != null; i++) {
            settleAsRead(child, 10, 500);
            child = onDesk().orElse(null);
        }

        ReviewCatchUpRun run = runs.findAll().get(0);
        assertThat(run.getWindowsDone()).isEqualTo(2);
        assertThat(run.getState()).isEqualTo(ReviewCatchUpState.STOPPED_LIMIT);
        assertThat(run.getStopReason()).isEqualTo("MAX_WINDOWS");
        assertThat(onDesk()).isEmpty();
    }

    @Test
    @DisplayName("no boundary is no walk — the press falls back to reading the screen's own period")
    void noBoundaryNoWalk() {
        assertThat(press("press-1")).isEmpty();
        assertThat(runs.count()).isZero();
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("the walk keeps the word for WHERE it stopped, not only the outcome")
    void theStopReasonNamesThePlace() {
        // 2026-10-08: the first live catch-up reported SURFACE_UNREADABLE — which is also what a missing grid
        // and a refused reading report. Three different fixes, one word, and the operator had to read a helper
        // log to tell them apart. The screen turns this word into one sentence.
        coveredThrough(36);
        ScheduledAsideJob child = press("press-1").orElseThrow().firstJob();
        claim();
        dispatcher.settle(org, deviceId, child.getId(), AsideJobOutcome.SURFACE_UNREADABLE, null, null,
                "RANGE_CONTROLS_NOT_FOUND");

        assertThat(jobs.findById(child.getId()).orElseThrow().getFailureCode())
                .isEqualTo("RANGE_CONTROLS_NOT_FOUND");
        ReviewCatchUpRun run = runs.findById(child.getCatchUpRunId()).orElseThrow();
        assertThat(run.getState()).isEqualTo(ReviewCatchUpState.FAILED);
        assertThat(run.getStopReason()).isEqualTo("RANGE_CONTROLS_NOT_FOUND");
    }

    @Test
    @DisplayName("a word this backend does not know is dropped, not refused — the report still lands")
    void anUnknownWordIsDropped() {
        coveredThrough(36);
        ScheduledAsideJob child = press("press-1").orElseThrow().firstJob();
        claim();
        // A helper's vocabulary may grow a word before this backend knows it. Refusing the report would make
        // the helper retry a job that really did finish.
        dispatcher.settle(org, deviceId, child.getId(), AsideJobOutcome.SURFACE_UNREADABLE, null, null,
                "SOMETHING_NEW_v2");
        assertThat(jobs.findById(child.getId()).orElseThrow().getFailureCode()).isNull();
        assertThat(runs.findById(child.getCatchUpRunId()).orElseThrow().getStopReason())
                .isEqualTo("SURFACE_UNREADABLE");
    }

    @Test
    @DisplayName("nothing moves before the controls are verified — no rows, no coverage, no WRITE")
    void nothingMovesBeforeVerification() {
        coveredThrough(36);
        ScheduledAsideJob child = press("press-1").orElseThrow().firstJob();
        claim();
        // The helper stopped at the third gate: it never typed, never pressed, never read. The only thing that
        // may exist afterwards is the record that it stopped.
        dispatcher.settle(org, deviceId, child.getId(), AsideJobOutcome.SURFACE_UNREADABLE, null, null,
                "DATE_CONTROL_CANDIDATES_UNREADABLE");

        ScheduledAsideJob settled = jobs.findById(child.getId()).orElseThrow();
        assertThat(settled.getWindowStart()).as("no period was covered").isNull();
        assertThat(settled.getObservedCount()).isNull();
        assertThat(settled.getInsertedCount()).isNull();
        assertThat(settled.getDeliveryCompleteness()).isNull();
        ReviewCatchUpRun run = runs.findById(child.getCatchUpRunId()).orElseThrow();
        assertThat(run.getWindowsDone()).isZero();
        assertThat(run.getDaysCovered()).isZero();
        assertThat(run.getRowsObserved()).isZero();
        // And the boundary is exactly where it was.
        assertThat(new ReviewCoverageCursor(segments, channels, jobs)
                .of(org, naver.getId(), today).coverageThrough())
                .isEqualTo(today.minusDays(36));
    }

    @Test
    @DisplayName("a window that ended in a way this lane cannot continue from stops the walk, keeping what was read")
    void aFailedWindowStopsTheWalk() {
        coveredThrough(36);
        ScheduledAsideJob first = press("press-1").orElseThrow().firstJob();
        settleAsRead(first, 45, 500);
        ScheduledAsideJob second = onDesk().orElseThrow();
        claim();
        dispatcher.settle(org, deviceId, second.getId(), AsideJobOutcome.SURFACE_UNREADABLE, null, null);

        ReviewCatchUpRun run = runs.findById(first.getCatchUpRunId()).orElseThrow();
        assertThat(run.getState()).isEqualTo(ReviewCatchUpState.FAILED);
        assertThat(run.getWindowsDone()).as("the window that was read still counts").isEqualTo(1);
        assertThat(onDesk()).isEmpty();
    }
}
