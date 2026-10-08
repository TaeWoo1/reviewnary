package com.sellerops.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sellerops.auth.device.HelperDevice;
import com.sellerops.auth.device.HelperDeviceRepository;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.collect.dto.CollectNowReadinessView;
import com.sellerops.collect.dto.ReviewCatchUpPlanView;
import com.sellerops.collect.dto.CollectNowView;
import com.sellerops.collect.dto.SyncRunView;
import com.sellerops.common.ApiException;
import com.sellerops.localagent.LocalAgentRunState;
import com.sellerops.localagent.ScreenReadService;
import com.sellerops.responsibility.IdentityVerdict;
import com.sellerops.responsibility.aside.AsideHelperBusyException;
import com.sellerops.responsibility.aside.AsideHelperDevices;
import com.sellerops.responsibility.aside.AsideHelperUnavailableException;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideMarketplaceAccess;
import com.sellerops.responsibility.aside.AsideMarketplaceTarget;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.AsideTrigger;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDate;
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
 * <b>지금 수집, pressed once, for six different channel × data-type pairs.</b>
 *
 * <p>What this pins is the product promise rather than either mechanism: the seller presses one button and the
 * server sends the collection down whichever route that channel actually has. The two mechanisms underneath are
 * already pinned elsewhere — the pull run by the collect tests, the screen read by
 * {@code OperatorScreenReadTest} — and nothing here re-proves them. What is new, and what used to be impossible,
 * is that one call reaches both.
 *
 * <p>Every test runs with the unattended lane <b>off</b> and both of its allow-lists <b>empty</b>, which is the
 * shipped posture. A collect-now that only worked with the Responsibility lane armed would not be a product
 * button; it would be a second handle on the unattended door.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// Deliberately the SAME datasource as OperatorScreenReadTest, so the two share one cached Spring context.
// They are siblings on one substrate and have no reason to want separate databases, and a distinct URL buys
// nothing while costing a whole context: the test JVM keeps every one it has built, on Gradle's default heap,
// and a nineteenth was enough to turn two unrelated full-context classes into OutOfMemoryError.
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:operator_screen_read;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OperatorCollectNowTest {

    @Autowired ScheduledAsideJobRepository jobs;
    @Autowired SellerAccountRepository accounts;
    @Autowired ChannelRepository channels;
    @Autowired HelperDeviceRepository devices;
    @Autowired com.sellerops.reviewimport.ReviewImportSegmentRepository segments;
    @Autowired com.sellerops.reviewimport.ReviewImportPlanRepository plans;
    @Autowired com.sellerops.coverage.catchup.ReviewCatchUpRunRepository catchUpRuns;

    private static final Instant T0 = Instant.parse("2026-10-07T04:00:00Z");

    private UUID org;
    private UUID naverAccount;
    private UUID coupangAccount;
    private UUID cafe24Account;
    private CollectNowService service;
    private ScheduledAsideJobService dispatcher;
    /** The existing pull run, as a seam. What it does is not this test's subject; whether it is reached is. */
    private CollectControlService pulls;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        devices.deleteAll();
        accounts.deleteAll();
        org = UUID.randomUUID();
        naverAccount = account(channel("NAVER", "네이버 스마트스토어"), org).getId();
        coupangAccount = account(channel("COUPANG", "쿠팡"), org).getId();
        cafe24Account = account(channel("CAFE24", "카페24 자사몰"), org).getId();
        link(org);

        AsideMarketplaceTarget resolver = new AsideMarketplaceTarget() {
            @Override
            public Optional<Target> resolve(UUID orgId, AsideRecipe recipe) {
                return Optional.empty(); // the deployment named nobody — the shipped posture
            }

            @Override
            public Optional<Target> resolveFor(UUID orgId, UUID sellerAccountId, AsideRecipe recipe) {
                return accounts.findByIdAndOrgId(sellerAccountId, orgId)
                        .filter(a -> channels.findById(a.getChannelId())
                                .map(Channel::getCode)
                                .filter(code -> recipe.channelCode().map(code::equals).orElse(false))
                                .isPresent())
                        .map(a -> new Target(a.getId(), "slot-1", "digest"));
            }
        };
        AsideHelperDevices helpers = new AsideHelperDevices(devices, Clock.fixed(T0, ZoneOffset.UTC));
        dispatcher = new ScheduledAsideJobService(jobs, Clock.fixed(T0, ZoneOffset.UTC),
                new AsideMarketplaceAccess(false, Set.of(), Set.of()), resolver, helpers);
        pulls = mock(CollectControlService.class);
        when(pulls.manualSync(any(), any(), any())).thenAnswer(call -> pullRun(call.getArgument(2)));
        service = new CollectNowService(accounts, channels,
                pulls, new ScreenReadService(dispatcher, helpers, accounts, channels), null,
                new com.sellerops.coverage.ReviewCoverageCursor(segments, channels, jobs), catchUpRuns);
    }

    @Test
    @DisplayName("the dry plan reads the gap out of real import evidence, oldest window first — and collects nothing")
    void theDryPlanIsPlannedNotGuessed() {
        // The evidence this organisation actually has: a period import that reconciled. Nothing else is seeded,
        // so the plan has to come out of that row or out of nowhere — and «out of nowhere» is what this refuses.
        UUID channelId = accounts.findByIdAndOrgId(naverAccount, org).orElseThrow().getChannelId();
        covered(channelId, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(40),
                LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(21));

        ReviewCatchUpPlanView plan = service.catchUpPlan(org, naverAccount, "REVIEW");

        assertThat(plan.coverageThrough())
                .isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(21));
        // Oldest first: the days right after the boundary, not the last seven. The last seven are the ones a
        // screen read already covers, and starting there reads the same week forever.
        assertThat(plan.windows()).isNotEmpty();
        assertThat(plan.windows().get(0).start())
                .isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(20));
        assertThat(plan.windows().get(0).days()).isEqualTo(7);
        // Asking for the plan dispatches nothing. A plan a seller is allowed to look at and then not press.
        assertThat(jobs.count()).isZero();
        // And it says whether this deployment can actually read a past period. Since 2026-10-08 the
        // screen-read route has READ navigation for this list, so a plan on this row is executable — a
        // statement about the deployment, not about whether the seller's own helper is new enough.
        assertThat(plan.executable()).isTrue();
    }

    @Test
    @DisplayName("no evidence is not a date — and the API route has no period to be missing")
    void noEvidenceAndNoPeriod() {
        ReviewCatchUpPlanView naver = service.catchUpPlan(org, naverAccount, "REVIEW");
        assertThat(naver.coverageThrough()).isNull();
        assertThat(naver.windows()).isEmpty();
        assertThat(naver.stopped()).isEqualTo(com.sellerops.coverage.ReviewCatchUpPlan.Stop.NO_BOUNDARY);

        // Cafe24 리뷰 travels by API: the channel answers for the whole store, so a boundary would be a fact
        // about nothing. The readiness row says so by leaving it null rather than by inventing a gap.
        assertThat(service.readiness(org, cafe24Account, "REVIEW").coverageThrough()).isNull();
        assertThat(service.readiness(org, cafe24Account, "REVIEW").coverageGapDays()).isNull();
    }

    /** One reconciled period import for this channel — the shape `review_import_segment` really holds. */
    private void covered(UUID channelId, LocalDate start, LocalDate end) {
        com.sellerops.reviewimport.ReviewImportPlan plan = new com.sellerops.reviewimport.ReviewImportPlan();
        plan.setOrgId(org);
        plan.setChannelId(channelId);
        plan.setSellerAccountId(naverAccount);
        plan.setRequestedStart(start);
        plan.setRequestedEnd(end);
        plan.setStatus(com.sellerops.reviewimport.ReviewImportPlanStatus.COMPLETED);
        plans.save(plan);
        com.sellerops.reviewimport.ReviewImportSegment segment = new com.sellerops.reviewimport.ReviewImportSegment();
        segment.setPlanId(plan.getId());
        segment.setOrgId(org);
        segment.setOrdinal(0);
        segment.setSegmentStart(start);
        segment.setSegmentEnd(end);
        segment.setExecutionState(com.sellerops.reviewimport.SegmentExecutionState.COMPLETED);
        segment.setCoverageState(com.sellerops.reviewimport.SegmentCoverageState.COVERED);
        segment.setCoveredRows(100);
        segments.save(segment);
    }

    @Test
    @DisplayName("NAVER 리뷰: the press becomes an OPERATOR job on the seller's own desk, not an API call")
    void naverReviewGoesToTheLocalAgent() {
        CollectNowView view = service.collectNow(org, naverAccount, "REVIEW", "press-1");

        assertThat(view.path()).isEqualTo(CollectNowRouter.Path.SCREEN_READ);
        assertThat(view.run()).isNull();
        assertThat(view.screenRead().state()).isEqualTo(LocalAgentRunState.RUNNING);
        ScheduledAsideJob job = jobs.findById(view.screenRead().jobId()).orElseThrow();
        assertThat(job.getRecipe()).isEqualTo(AsideRecipe.NAVER_REVIEW_OBSERVE_V1);
        assertThat(job.getTrigger()).isEqualTo(AsideTrigger.OPERATOR);
        assertThat(job.getSellerAccountId()).isEqualTo(naverAccount);
        assertThat(job.getRunId()).isNull();
        assertThat(job.getMaxPages()).isEqualTo(1);
        // The pull connector is not asked to fetch reviews NAVER publishes no API for.
        verifyNoInteractions(pulls);
    }

    @Test
    @DisplayName("쿠팡 리뷰: the same press, the same primitive, the WING screen")
    void coupangReviewGoesToTheLocalAgent() {
        CollectNowView view = service.collectNow(org, coupangAccount, "REVIEW", "press-1");

        assertThat(view.path()).isEqualTo(CollectNowRouter.Path.SCREEN_READ);
        ScheduledAsideJob job = jobs.findById(view.screenRead().jobId()).orElseThrow();
        assertThat(job.getRecipe()).isEqualTo(AsideRecipe.COUPANG_REVIEW_OBSERVE_V1);
        assertThat(job.getTrigger()).isEqualTo(AsideTrigger.OPERATOR);
        assertThat(job.getSellerAccountId()).isEqualTo(coupangAccount);
        verifyNoInteractions(pulls);
        // One lifecycle for both channels: NAVER and Coupang reviews are the same kind of row, claimed the
        // same way, by the same helper, with the same store fence.
        assertThat(dispatcher.claim(org, devices.findAll().get(0).getId()).orElseThrow().jobId())
                .isEqualTo(view.screenRead().jobId());
    }

    @Test
    @DisplayName("NAVER · 쿠팡 문의: the official API, and nothing is put on the seller's desk")
    void inquiriesGoThroughTheApi() {
        for (UUID account : new UUID[] {naverAccount, coupangAccount}) {
            CollectNowView view = service.collectNow(org, account, "INQUIRY", "press-1");
            assertThat(view.path()).isEqualTo(CollectNowRouter.Path.API);
            assertThat(view.screenRead()).isNull();
            assertThat(view.run().dataType()).isEqualTo("INQUIRY");
            verify(pulls).manualSync(eq(org), eq(account), eq("INQUIRY"));
        }
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("카페24 리뷰·문의: both by official API — the channel that has one for each")
    void cafe24UsesItsApiForBoth() {
        for (String dataType : new String[] {"REVIEW", "INQUIRY"}) {
            CollectNowView view = service.collectNow(org, cafe24Account, dataType, "press-" + dataType);
            assertThat(view.path()).as("%s", dataType).isEqualTo(CollectNowRouter.Path.API);
            verify(pulls).manualSync(eq(org), eq(cafe24Account), eq(dataType));
        }
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("the same request id twice is one collection, not two")
    void aRepeatedPressIsIdempotent() {
        CollectNowView first = service.collectNow(org, naverAccount, "REVIEW", "press-1");
        CollectNowView again = service.collectNow(org, naverAccount, "REVIEW", "press-1");

        assertThat(again.screenRead().jobId()).isEqualTo(first.screenRead().jobId());
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("another organisation's account is not found — not forbidden, not collected")
    void anotherOrgsAccountIsRefused() {
        SellerAccount theirs = account(channels.findByCode("NAVER").orElseThrow(), UUID.randomUUID());

        assertThatThrownBy(() -> service.collectNow(org, theirs.getId(), "REVIEW", "press-1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.collectNow(org, theirs.getId(), "INQUIRY", "press-1"))
                .isInstanceOf(ApiException.class);
        assertThat(jobs.count()).isZero();
        verify(pulls, never()).manualSync(any(), any(), any());
    }

    @Test
    @DisplayName("멈춘 catch-up은 화면이 볼 수 있는 사실이다 — 지난 시도의 결말이 아니라 지금의 의도")
    void readinessShowsAPausedCatchUp() {
        // 2026-10-09: 판매자가 로그인했고 「로그인 확인됨」까지 봤는데 아무것도 이어지지 않았다. 이어갈 의도는
        // 이 row로 남아 있었고 화면이 그걸 볼 길이 없었다 — `localAgent`와 `latestAttemptOutcome`은 둘 다
        // 지난 시도에 대한 말이고, 로그인한 뒤에도 계속 AUTH_REQUIRED라고 말한다.
        assertThat(service.readiness(org, naverAccount, "REVIEW").pausedCatchUp())
                .as("멈춘 의도가 없으면 거짓이다")
                .isFalse();

        catchUpRuns.save(pausedRun(naverAccount));
        assertThat(service.readiness(org, naverAccount, "REVIEW").pausedCatchUp())
                .as("로그인을 기다리는 catch-up이 있으면 참이다")
                .isTrue();
        // 다른 자료, 다른 가게의 것을 끌어오지 않는다.
        assertThat(service.readiness(org, naverAccount, "INQUIRY").pausedCatchUp()).isFalse();
        assertThat(service.readiness(org, coupangAccount, "REVIEW").pausedCatchUp()).isFalse();
    }

    @Test
    @DisplayName("진행 중인 catch-up은 「로그인을 기다린다」가 아니다 — 그 둘은 다음에 할 일이 다르다")
    void aRunningCatchUpIsNotPaused() {
        com.sellerops.coverage.catchup.ReviewCatchUpRun run = pausedRun(naverAccount);
        run.setState(com.sellerops.coverage.catchup.ReviewCatchUpState.RUNNING);
        run.setPausedWindowStart(null);
        catchUpRuns.save(run);
        assertThat(service.readiness(org, naverAccount, "REVIEW").pausedCatchUp()).isFalse();
    }

    private com.sellerops.coverage.catchup.ReviewCatchUpRun pausedRun(java.util.UUID accountId) {
        com.sellerops.coverage.catchup.ReviewCatchUpRun run =
                new com.sellerops.coverage.catchup.ReviewCatchUpRun();
        run.setOrgId(org);
        run.setSellerAccountId(accountId);
        run.setChannelId(channels.findAll().stream()
                .filter(c -> "NAVER".equals(c.getCode())).findFirst().orElseThrow().getId());
        run.setDataType("REVIEW");
        run.setState(com.sellerops.coverage.catchup.ReviewCatchUpState.PAUSED_AUTH);
        run.setRequestedFrom(java.time.LocalDate.of(2026, 9, 3));
        run.setRequestedThrough(java.time.LocalDate.of(2026, 10, 9));
        run.setCursorDay(java.time.LocalDate.of(2026, 9, 3));
        run.setPausedWindowStart(java.time.LocalDate.of(2026, 9, 3));
        run.setStartedAt(T0);
        run.setUpdatedAt(T0);
        run.setClientRequestId(java.util.UUID.randomUUID().toString());
        return run;
    }

    @Test
    @DisplayName("the desk's four answers are four different things for the seller to do")
    void readinessDistinguishesTheDeskStates() {
        // READY — a linked, idle helper and a published screen read.
        assertThat(service.readiness(org, naverAccount, "REVIEW"))
                .isEqualTo(CollectNowReadinessView.of(CollectNowRouter.Path.SCREEN_READ, LocalAgentRunState.READY));

        // BUSY — the desk is already reading something, which is not «go link a helper».
        service.collectNow(org, coupangAccount, "REVIEW", "press-1");
        assertThat(service.readiness(org, naverAccount, "REVIEW").localAgent())
                .isEqualTo(LocalAgentRunState.BUSY);
        assertThatThrownBy(() -> service.collectNow(org, naverAccount, "REVIEW", "press-2"))
                .isInstanceOf(AsideHelperBusyException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(AsideHelperBusyException.CODE);

        // AUTH_REQUIRED — the last finished read of THIS screen was turned away at the channel's sign-in.
        jobs.deleteAll();
        settled(naverAccount, AsideRecipe.NAVER_REVIEW_OBSERVE_V1, AsideJobOutcome.AUTH_REQUIRED);
        assertThat(service.readiness(org, naverAccount, "REVIEW").localAgent())
                .isEqualTo(LocalAgentRunState.AUTH_REQUIRED);
        // ...and it is about that screen only: Coupang's desk is unaffected by NAVER's sign-in.
        assertThat(service.readiness(org, coupangAccount, "REVIEW").localAgent())
                .isEqualTo(LocalAgentRunState.READY);
        // A read that succeeded afterwards is the newer fact, so the warning goes away by itself.
        settled(naverAccount, AsideRecipe.NAVER_REVIEW_OBSERVE_V1, AsideJobOutcome.OBSERVED);
        assertThat(service.readiness(org, naverAccount, "REVIEW").localAgent())
                .isEqualTo(LocalAgentRunState.READY);

        // UNPAIRED — nothing can be asked at all, and the fix is one press on 연결.
        devices.deleteAll();
        assertThat(service.readiness(org, naverAccount, "REVIEW").localAgent())
                .isEqualTo(LocalAgentRunState.UNPAIRED);
        assertThatThrownBy(() -> service.collectNow(org, naverAccount, "REVIEW", "press-3"))
                .isInstanceOf(AsideHelperUnavailableException.class);
    }

    @Test
    @DisplayName("an API row has no desk state, because no desk is involved")
    void readinessSaysNothingAboutADeskItDoesNotUse() {
        assertThat(service.readiness(org, cafe24Account, "REVIEW"))
                .isEqualTo(CollectNowReadinessView.of(CollectNowRouter.Path.API, null));
        devices.deleteAll();
        // Still API, still available: an unlinked helper has nothing to do with a Cafe24 review.
        assertThat(service.readiness(org, cafe24Account, "REVIEW").path())
                .isEqualTo(CollectNowRouter.Path.API);
    }

    @Test
    @DisplayName("a data type that is not a data type is refused before anything is reached")
    void nonsenseIsRefusedEarly() {
        assertThatThrownBy(() -> service.collectNow(org, naverAccount, "EVERYTHING", "press-1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.collectNow(org, naverAccount, null, "press-1"))
                .isInstanceOf(ApiException.class);
        assertThat(jobs.count()).isZero();
        verifyNoInteractions(pulls);
    }

    @Test
    @DisplayName("the unattended lane is not widened by the product having a button")
    void theResponsibilityLaneStillNeedsTheDeployment() {
        // Pressed reads work in this context; a responsibility dispatch in the same process is still refused,
        // because nobody is watching that lane and no deployment has vouched for the store.
        service.collectNow(org, naverAccount, "REVIEW", "press-1");
        jobs.deleteAll();
        assertThatThrownBy(() -> dispatcher.dispatch(
                com.sellerops.responsibility.aside.AsideDispatch.responsibility(
                        org, UUID.randomUUID(), "run-job-1", AsideRecipe.NAVER_REVIEW_OBSERVE_V1)))
                .isInstanceOf(ApiException.class);
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("every route this door can take is a read: there is no write anywhere behind it")
    void nothingBehindThisDoorWrites() {
        // The screen-read route can only reach a READ_ONLY recipe (AsideRecipeScreenReadTest pins that every
        // published recipe is one, and the dispatch primitive refuses anything else). The API route can only
        // reach `manualSync`, which is a pull. Stated here so the door itself carries the claim.
        for (AsideRecipe recipe : AsideRecipe.values()) {
            assertThat(recipe.mode())
                    .as("%s", recipe)
                    .isEqualTo(com.sellerops.responsibility.aside.AsideRecipeMode.READ_ONLY);
        }
        service.collectNow(org, coupangAccount, "REVIEW", "press-1");
        assertThat(jobs.findAll()).allSatisfy(job -> assertThat(job.getRecipe().mode())
                .isEqualTo(com.sellerops.responsibility.aside.AsideRecipeMode.READ_ONLY));
    }

    private void settled(UUID accountId, AsideRecipe recipe, AsideJobOutcome outcome) {
        ScheduledAsideJob job = new ScheduledAsideJob();
        job.setOrgId(org);
        job.setDeviceId(devices.findAll().get(0).getId());
        job.setTrigger(AsideTrigger.OPERATOR);
        job.setSellerAccountId(accountId);
        job.setClientJobId("settled-" + UUID.randomUUID());
        job.setRecipe(recipe);
        job.setStatus(ScheduledAsideJobStatus.SETTLED);
        job.setOutcome(outcome);
        job.setExpiresAt(T0.plusSeconds(600));
        job.setSettledAt(T0.plusSeconds(jobs.count() == 0 ? 10 : 20));
        if (outcome == AsideJobOutcome.OBSERVED) {
            job.setIdentityVerdict(IdentityVerdict.MATCH);
            job.setObservedCount(3);
        }
        jobs.save(job);
    }

    private SyncRunView pullRun(String dataType) {
        return new SyncRunView(UUID.randomUUID(), null, null, dataType, "MANUAL", 1, false, null,
                "PULL", null, "SUCCESS", 3, 3, 0, 0, null, T0, T0.plusSeconds(2), "API", null);
    }

    private Channel channel(String code, String nameKo) {
        return channels.findByCode(code).orElseGet(() -> {
            Channel ch = new Channel();
            ch.setCode(code);
            ch.setNameKo(nameKo);
            ch.setStatus(ChannelStatus.AVAILABLE);
            ch.setSupportsReview(true);
            ch.setSupportsInquiry(true);
            ch.setSupportsOrder(true);
            ch.setSupportsSales(true);
            ch.setSupportsProduct(true);
            ch.setSortOrder(0);
            return channels.save(ch);
        });
    }

    private SellerAccount account(Channel channel, UUID orgId) {
        SellerAccount account = new SellerAccount();
        account.setOrgId(orgId);
        account.setChannelId(channel.getId());
        account.setConnectionStatus(ChannelStatus.CONNECTED);
        account.setFileUpload(false);
        return accounts.save(account);
    }

    /** One live device grant — the thing a seller creates once by linking their installed helper. */
    private void link(UUID orgId) {
        HelperDevice device = new HelperDevice();
        device.setOrgId(orgId);
        device.setUserId(UUID.randomUUID());
        device.setTokenHash("hash-" + UUID.randomUUID());
        device.setDeviceName("Mac (arm64)");
        device.setHelperVersion("0.3.0");
        device.setExpiresAt(T0.plusSeconds(60 * 60 * 24 * 30));
        devices.save(device);
    }
}
