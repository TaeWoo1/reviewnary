package com.sellerops.autocheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.auth.device.HelperDevice;
import com.sellerops.auth.device.HelperDeviceRepository;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.coverage.ReviewCoverageCursor;
import com.sellerops.coverage.catchup.ReviewCatchUpOrchestrator;
import com.sellerops.coverage.catchup.ReviewCatchUpRun;
import com.sellerops.coverage.catchup.ReviewCatchUpRunRepository;
import com.sellerops.coverage.catchup.ReviewCatchUpState;
import com.sellerops.localagent.ScreenReadView;
import com.sellerops.responsibility.IdentityVerdict;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideHelperDevices;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideMarketplaceAccess;
import com.sellerops.responsibility.aside.AsideMarketplaceTarget;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.AsideTrigger;
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
 * <b>「새 리뷰를 자동으로 확인합니다」 — 설정 하나가 제품처럼 행동하는지.</b>
 *
 * <p>이 파일이 지키는 것은 다섯 문장이고, 전부 판매자가 겪는 것으로 적혀 있다:
 *
 * <ul>
 *   <li>연결하면 켜져 있고, 끄면 꺼진 채로 있다 — 저절로 다시 켜지지 않는다;</li>
 *   <li>빈 과거는 <b>어제까지</b> 메우고, 오늘은 이름 붙인 하루로 따로 읽는다 — 기간 없는 자동 읽기는 없다;</li>
 *   <li>사람이 누르면 자동 확인보다 앞선다 — 「이미 수집 중」으로 거절되지 않고, 진행 중인 작업을 본다;</li>
 *   <li>도우미가 없으면 PAUSED_DEVICE이고, 돌아오면 설정 그대로 이어진다;</li>
 *   <li>로그인 벽은 timer가 다시 때리지 않는다 — 판매자가 로그인할 때 그 창부터 이어진다.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:review_auto_check;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ReviewAutoCheckTest {

    @Autowired ReviewAutoCheckRepository settingRows;
    @Autowired ScheduledAsideJobRepository jobs;
    @Autowired SellerAccountRepository accounts;
    @Autowired ChannelRepository channels;
    @Autowired HelperDeviceRepository devices;
    @Autowired ReviewImportSegmentRepository segments;
    @Autowired ReviewImportPlanRepository plans;
    @Autowired ReviewCatchUpRunRepository runs;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private UUID org;
    private UUID userId;
    private SellerAccount account;
    private Channel naver;
    private UUID deviceId;
    private LocalDate today;
    private Instant now;
    private ScheduledAsideJobService dispatcher;
    private ReviewCatchUpOrchestrator orchestrator;
    private ReviewAutoCheckService settings;
    private com.sellerops.localagent.ScreenReadService screenReads;
    private ReviewAutoCheckReconciler reconciler;

    @BeforeEach
    void setUp() {
        settingRows.deleteAll();
        runs.deleteAll();
        jobs.deleteAll();
        segments.deleteAll();
        plans.deleteAll();
        devices.deleteAll();
        accounts.deleteAll();
        org = UUID.randomUUID();
        userId = UUID.randomUUID();
        today = LocalDate.now(KST);
        now = Instant.now();

        naver = channels.findByCode("NAVER").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("NAVER");
            c.setNameKo("네이버 스마트스토어");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSupportsReview(true);
            c.setSupportsInquiry(true);
            c.setSupportsOrder(true);
            c.setSupportsSales(true);
            c.setSupportsProduct(true);
            c.setSortOrder(0);
            return channels.save(c);
        });
        account = accounts.save(connected(naver.getId()));
        deviceId = linkHelper();

        Clock clock = new Clock() {
            @Override public ZoneId getZone() {
                return ZoneOffset.UTC;
            }
            @Override public Clock withZone(ZoneId zone) {
                return this;
            }
            @Override public Instant instant() {
                return now;
            }
        };
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
        dispatcher = new ScheduledAsideJobService(jobs, clock,
                new AsideMarketplaceAccess(false, Set.of(), Set.of()), resolver,
                new AsideHelperDevices(devices, clock));
        orchestrator = new ReviewCatchUpOrchestrator(runs,
                new ReviewCoverageCursor(segments, channels, jobs), dispatcher, clock,
                ReviewCatchUpOrchestrator.Limits.PRESS);
        dispatcher.addSettledListener(orchestrator::advance);
        settings = new ReviewAutoCheckService(settingRows, accounts, channels, clock);
        dispatcher.setAutoCheckAuthority(settings);
        // 운영에서와 같은 매듭: 두 lane이 같은 보고를 듣는다 (ReviewAutoCheckWiring).
        dispatcher.addSettledListener(settings::noteSettled);
        screenReads = new com.sellerops.localagent.ScreenReadService(dispatcher,
                new AsideHelperDevices(devices, clock), accounts, channels,
                new com.sellerops.coverage.catchup.ReviewCatchUpStatus(runs, orchestrator));
        reconciler = new ReviewAutoCheckReconciler(new ReviewAutoCheckClaimer(settingRows), settings, accounts,
                orchestrator, dispatcher, clock);
    }

    /** 지금 due로 만든다 — tick이 이 계정을 이번 차례에 집도록. */
    private void setDue() {
        settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW").ifPresent(row -> {
            row.setNextCheckAt(now);
            settingRows.save(row);
        });
    }

    /** 연결이 완료된 순간 — 설정이 켜지는 유일한 자동 경로. */
    private void connectionCompleted(SellerAccount target) {
        new ReviewAutoCheckConnectionListener(settings)
                .onConnected(new com.sellerops.selleraccount.SellerAccountConnectedEvent(
                        target.getOrgId(), target.getId()));
    }

    /** 시간을 앞으로 보낸다 — 두 읽기의 「마지막」을 가릴 수 있게. */
    private void tick(Duration by) {
        now = now.plus(by);
    }

    private SellerAccount connected(UUID channelId) {
        SellerAccount a = new SellerAccount();
        a.setOrgId(org);
        a.setChannelId(channelId);
        a.setConnectionStatus(ChannelStatus.CONNECTED);
        a.setFileUpload(false);
        return a;
    }

    private UUID linkHelper() {
        HelperDevice device = new HelperDevice();
        device.setOrgId(org);
        device.setUserId(userId);
        device.setTokenHash("hash-" + UUID.randomUUID());
        device.setDeviceName("Mac (arm64)");
        device.setHelperVersion("0.3.0");
        device.setExpiresAt(now.plus(Duration.ofDays(180)));
        return devices.save(device).getId();
    }

    /** A reconciled period import ending {@code daysBeforeToday} days ago — the boundary this org really has. */
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
        segments.save(segment);
    }

    private Optional<ScheduledAsideJob> onDesk() {
        return jobs.findAll().stream()
                .filter(j -> j.getStatus() == ScheduledAsideJobStatus.QUEUED
                        || j.getStatus() == ScheduledAsideJobStatus.CLAIMED)
                .findFirst();
    }

    /**
     * 운영과 같은 순서로 끝낸다: <b>전달이 먼저, 보고가 나중</b>이다. 백엔드는 읽은 것을 받아 귀속과 기간을
     * 적고(그 때 identity가 정해진다), helper는 그 다음에 작업을 보고한다 — settle이 두 lane에 알리는 순간에는
     * 이미 그 사실들이 행에 있다. 이 도우미가 반대로 하고 있었고, 그래서 settle 시점의 행에는 아무 귀속도
     * 없었다.
     */
    private void settleAsRead(ScheduledAsideJob job, int rows) {
        jobs.findById(job.getId()).ifPresent(live -> {
            live.setWindowStart(job.getRequestedWindowStart());
            live.setWindowEnd(job.getRequestedWindowEnd());
            live.setIdentityVerdict(IdentityVerdict.MATCH);
            live.recordDelivery(rows, 0);
            live.recordCoverageVerdict(SourceCompleteness.BOUNDED, "WHOLE_PERIOD_READ");
            jobs.save(live);
        });
        dispatcher.claim(org, job.getDeviceId());
        dispatcher.settle(org, job.getDeviceId(), job.getId(), AsideJobOutcome.OBSERVED, rows, null);
    }

    private void settleAsAuthWall(ScheduledAsideJob job) {
        dispatcher.claim(org, job.getDeviceId());
        dispatcher.settle(org, job.getDeviceId(), job.getId(), AsideJobOutcome.AUTH_REQUIRED, null, null,
                "AUTH_REQUIRED");
    }

    // ---------------------------------------------------------------- the setting

    @Test
    @DisplayName("연결한 NAVER 계정은 묻지 않고 켜져 있다 — 동의 화면도, 기기 선택도, 주기 선택도 없다")
    void aConnectedNaverAccountIsOnByDefault() {
        connectionCompleted(account);
        ReviewAutoCheckView view = settings.view(org, account.getId());

        assertThat(view.supported()).isTrue();
        assertThat(view.enabled()).isTrue();
        assertThat(view.paused()).isNull();
        // 설정은 기기를 모른다. 도우미가 없어도 켜져 있고, 도우미가 바뀌어도 그대로다.
        ReviewAutoCheck row = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(row.getMode()).isEqualTo("READ_ONLY");
        assertThat(row.getIntervalMinutes()).isEqualTo(60);
        assertThat(row.getNextCheckAt()).isNotNull();
    }

    @Test
    @DisplayName("설정을 들여다보는 것은 설정을 만들지 않는다 — 읽기가 켜는 일이 되면 누가 켰는지 알 수 없다")
    void lookingAtTheSettingNeverCreatesIt() {
        // 이 기능 전에 연결한 계정: 읽을 화면은 있고 행은 없다. 화면은 「꺼짐」으로 그리고, 켜는 것은 그
        // 조직의 명시적 backfill이다 — 화면을 열어 본 것이 켜는 일이 되면 「누가 이 계정의 자동 확인을
        // 켰는가」에 아무도 답할 수 없고, 계정 목록을 그리는 화면 하나가 전부를 켜 버린다.
        ReviewAutoCheckView view = settings.view(org, account.getId());

        assertThat(view.supported()).isTrue();
        assertThat(view.enabled()).isFalse();
        assertThat(settingRows.count()).isZero();
        // 그리고 행이 없으면 dispatch 게이트도 통과하지 못한다.
        assertThat(settings.allows(org, account.getId(), AsideRecipe.NAVER_REVIEW_OBSERVE_V1)).isFalse();

        // 켜는 길은 둘뿐이다: 그 조직의 backfill, 또는 판매자가 직접 켜기.
        assertThat(settings.backfillForOrg(org)).isEqualTo(1);
        assertThat(settings.view(org, account.getId()).enabled()).isTrue();
    }

    @Test
    @DisplayName("판매자가 직접 켜면 행이 없어도 켜진다 — 보는 것과 결정하는 것은 다르다")
    void theSellerSwitchingItOnMayCreateTheRow() {
        assertThat(settingRows.count()).isZero();

        assertThat(settings.set(org, account.getId(), userId, true).enabled()).isTrue();
        assertThat(settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")).isPresent();
    }

    @Test
    @DisplayName("끄면 꺼진 채로 있다 — 다음 tick이 다시 켜지 않는다")
    void turningItOffSticks() {
        connectionCompleted(account);
        assertThat(settings.set(org, account.getId(), userId, false).enabled()).isFalse();

        // 「없으면 만든다」가 이 행을 되살리지 않는다: 꺼진 행이 그 결정의 기억이다.
        reconciler.tick(now);
        assertThat(settings.view(org, account.getId()).enabled()).isFalse();
        assertThat(onDesk()).isEmpty();

        // 그리고 끈 계정은 dispatch 게이트를 통과하지 못한다.
        assertThat(settings.allows(org, account.getId(), AsideRecipe.NAVER_REVIEW_OBSERVE_V1)).isFalse();
    }

    @Test
    @DisplayName("리뷰 API가 있는 채널에는 설정 자체가 없다 — 꺼진 것과 없는 것은 다른 답이다")
    void anApiChannelHasNoSetting() {
        Channel cafe24 = channels.findByCode("CAFE24").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("CAFE24");
            c.setNameKo("카페24");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSupportsReview(true);
            c.setSortOrder(1);
            return channels.save(c);
        });
        SellerAccount apiAccount = accounts.save(connected(cafe24.getId()));

        assertThat(settings.view(org, apiAccount.getId())).isEqualTo(ReviewAutoCheckView.UNSUPPORTED);
        assertThat(settingRows.findBySellerAccountIdAndDataType(apiAccount.getId(), "REVIEW")).isEmpty();
        assertThatThrownBy(() -> settings.set(org, apiAccount.getId(), userId, false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("기간을 지정해 읽을 수 없는 화면에는 설정이 없다 — 쿠팡 상품평은 판매자의 누름으로 남는다")
    void aScreenThatCannotSelectAPeriodGetsNoSetting() {
        // 쿠팡에는 리뷰 화면 읽기 recipe가 있다 — 그래서 이 테스트가 필요하다. 없는 것은 그 화면의 기간을
        // 옮기는 능력이고(`coupang-observe-runner.ts`에 기간 이동이 없다), 아무도 보지 않는 읽기가 화면이
        // 그때 보여주던 기간을 읽으면 나중에 무엇을 덮었는지 말할 수 없다.
        Channel coupang = channels.findByCode("COUPANG").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("COUPANG");
            c.setNameKo("쿠팡");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSupportsReview(true);
            c.setSortOrder(2);
            return channels.save(c);
        });
        SellerAccount coupangAccount = accounts.save(connected(coupang.getId()));

        assertThat(AsideRecipe.forScreenRead("COUPANG", com.sellerops.connector.DataType.REVIEW)).isPresent();
        assertThat(settings.view(org, coupangAccount.getId())).isEqualTo(ReviewAutoCheckView.UNSUPPORTED);
        assertThat(settingRows.findBySellerAccountIdAndDataType(coupangAccount.getId(), "REVIEW")).isEmpty();
        // 그리고 설정이 어떻게든 켜져 있었더라도 choke point가 거절한다 — 범위는 문서가 아니라 구조다.
        assertThatThrownBy(() -> dispatcher.dispatch(
                com.sellerops.responsibility.aside.AsideDispatch.scheduled(org, coupangAccount.getId(),
                        AsideRecipe.COUPANG_REVIEW_OBSERVE_V1, "ac-coupang", today, today)))
                .isInstanceOf(ApiException.class);
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("설정 없이는 SCHEDULED dispatch가 없다 — 아무도 요청하지 않은 읽기는 열리지 않는다")
    void noSettingNoScheduledRead() {
        // 행이 없는 상태(= 아직 채택되지 않음)에서 직접 dispatch를 시도한다.
        assertThatThrownBy(() -> dispatcher.dispatch(
                com.sellerops.responsibility.aside.AsideDispatch.scheduled(org, account.getId(),
                        AsideRecipe.NAVER_REVIEW_OBSERVE_V1, "ac-probe", today, today)))
                .isInstanceOf(ApiException.class);
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("자동 읽기는 기간을 지정해야 한다 — 화면이 보여주던 기간을 읽는 자동 읽기는 만들 수 없다")
    void aScheduledReadMustNameItsPeriod() {
        assertThatThrownBy(() -> new com.sellerops.responsibility.aside.AsideDispatch(org, account.getId(),
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1, AsideTrigger.SCHEDULED,
                com.sellerops.responsibility.aside.AsideJobLimits.ONE_PAGE, null, "ac-windowless"))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- the tick

    @Test
    @DisplayName("scheduler는 설정을 만들지 않는다 — 연결하지 않은 조직이 loop에 집히지 않는다")
    void theTickNeverCreatesASetting() {
        // 행이 없는 계정(= 이 기능 전에 연결했거나, 다른 조직의 계정). tick이 이것을 채택하면 「누구의 스토어를
        // 읽는가」를 loop이 정하는 것이 되고, 그 다음에 필요한 것은 건너뛸 조직 목록이다 — 제품 코드가 들고
        // 있을 수 없는 종류의 목록이다.
        coveredThrough(1);
        assertThat(settingRows.count()).isZero();

        ReviewAutoCheckReconciler.TickReport report = reconciler.tick(now);

        assertThat(settingRows.count()).isZero();
        assertThat(report).isEqualTo(new ReviewAutoCheckReconciler.TickReport(0, 0, 0, 0, 0));
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("연결을 마치면 켜져 있다 — 연결 이벤트 하나가 그 한 줄을 참으로 만든다")
    void finishingAConnectionSwitchesItOn() {
        SellerAccount fresh = accounts.save(connected(naver.getId()));
        assertThat(settingRows.findBySellerAccountIdAndDataType(fresh.getId(), "REVIEW")).isEmpty();

        new ReviewAutoCheckConnectionListener(settings)
                .onConnected(new com.sellerops.selleraccount.SellerAccountConnectedEvent(org, fresh.getId()));

        assertThat(settings.view(org, fresh.getId()).enabled()).isTrue();
        // 두 번 와도 하나다 — 「다시 연결됨」은 연결이 아니다.
        new ReviewAutoCheckConnectionListener(settings)
                .onConnected(new com.sellerops.selleraccount.SellerAccountConnectedEvent(org, fresh.getId()));
        assertThat(settingRows.findBySellerAccountIdAndDataType(fresh.getId(), "REVIEW")).isPresent();
        assertThat(settingRows.findByOrgIdAndDataType(org, "REVIEW")).hasSize(1);
    }

    @Test
    @DisplayName("backfill은 내 조직의 계정만 켠다 — 그리고 껐던 것은 다시 켜지지 않는다")
    void backfillIsScopedToOneOrgAndRespectsOff() {
        UUID otherOrg = UUID.randomUUID();
        SellerAccount theirs = new SellerAccount();
        theirs.setOrgId(otherOrg);
        theirs.setChannelId(naver.getId());
        theirs.setConnectionStatus(ChannelStatus.CONNECTED);
        theirs.setFileUpload(false);
        theirs = accounts.save(theirs);

        // 내 계정 하나는 이미 끄고 시작한다.
        connectionCompleted(account);
        settings.set(org, account.getId(), userId, false);

        assertThat(settings.backfillForOrg(org)).isZero();
        assertThat(settings.view(org, account.getId()).enabled()).isFalse();

        // 다른 조직의 계정은 내 backfill이 건드리지 않는다.
        assertThat(settingRows.findBySellerAccountIdAndDataType(theirs.getId(), "REVIEW")).isEmpty();
        assertThat(settings.backfillForOrg(otherOrg)).isEqualTo(1);
        assertThat(settingRows.findBySellerAccountIdAndDataType(theirs.getId(), "REVIEW")).isPresent();
    }

    @Test
    @DisplayName("빈 과거가 있으면 walk가 시작된다 — SCHEDULED로, 어제까지")
    void aGapStartsAWalk() {
        coveredThrough(20);
        connectionCompleted(account);

        ReviewAutoCheckReconciler.TickReport report = reconciler.tick(now);

        assertThat(report.walked()).isEqualTo(1);
        ScheduledAsideJob child = onDesk().orElseThrow();
        assertThat(child.getTrigger()).isEqualTo(AsideTrigger.SCHEDULED);
        assertThat(child.getRequestedWindowStart()).isEqualTo(today.minusDays(19));
        assertThat(child.getRunId()).isNull();
        assertThat(child.getDeviceId()).isEqualTo(deviceId);
        ReviewCatchUpRun run = runs.findById(child.getCatchUpRunId()).orElseThrow();
        assertThat(run.getTriggerSource()).isEqualTo(AsideTrigger.SCHEDULED);
        assertThat(run.getRequestedThrough()).isEqualTo(today.minusDays(1));
        // 누름이 없었으므로 누름 id도 없다.
        assertThat(run.getClientRequestId()).isNull();
    }

    @Test
    @DisplayName("과거가 닫혀 있으면 오늘 하루를 이름 붙여 읽는다 — coverage는 오늘로 전진하지 않는다")
    void aClosedHistoryRefreshesToday() {
        coveredThrough(1);
        connectionCompleted(account);

        assertThat(reconciler.tick(now).refreshedToday()).isEqualTo(1);
        ScheduledAsideJob job = onDesk().orElseThrow();
        assertThat(job.getTrigger()).isEqualTo(AsideTrigger.SCHEDULED);
        assertThat(job.getRequestedWindowStart()).isEqualTo(today);
        assertThat(job.getRequestedWindowEnd()).isEqualTo(today);
        // 오늘 읽기는 부모가 없다: 전진시킬 경계가 없으므로 셀 것도 없다.
        assertThat(job.getCatchUpRunId()).isNull();

        settleAsRead(job, 3);
        // 오늘을 읽고도 경계는 어제에 머문다. freshness는 「언제 봤는가」이고 경계는 「어디까지 빠짐없이」다.
        assertThat(new ReviewCoverageCursor(segments, channels, jobs).of(org, naver.getId(), today)
                .coverageThrough()).isEqualTo(today.minusDays(1));
    }

    @Test
    @DisplayName("한 시간 뒤의 차례는 다른 차례다 — 같은 날짜라도 새 읽기가 생긴다")
    void thenextSlotIsANewRead() {
        // <b>2026-10-09 proof에서 잡힌 결함.</b> job id가 날짜였을 때, 10:30 차례는 09:30 차례의 끝난 행을
        // 다시 찾아 아무것도 보내지 않았다 — 자동 확인이 그날 첫 읽기 뒤로 조용히 멈췄다. 읽는 기간은 여전히
        // 「오늘」이지만, 그것은 창이고 요청의 신원이 아니다.
        coveredThrough(1);
        connectionCompleted(account);

        assertThat(reconciler.tick(now).refreshedToday()).isEqualTo(1);
        ScheduledAsideJob first = onDesk().orElseThrow();
        settleAsRead(first, 3);

        // 한 시간 뒤, 같은 날. 주기가 돌아왔으므로 새 차례이고 새 작업이다.
        now = now.plus(Duration.ofHours(1));
        assertThat(reconciler.tick(now).refreshedToday()).isEqualTo(1);
        ScheduledAsideJob second = onDesk().orElseThrow();

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getClientJobId()).isNotEqualTo(first.getClientJobId());
        // 둘 다 오늘 하루를 읽는다 — 창은 같고 차례는 다르다.
        assertThat(second.getRequestedWindowStart()).isEqualTo(today);
        assertThat(second.getRequestedWindowEnd()).isEqualTo(today);
        assertThat(second.getTrigger()).isEqualTo(AsideTrigger.SCHEDULED);
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 차례를 다시 돌리면 작업은 하나다 — 재시도가 두 번째 읽기가 되지 않는다")
    void theSameSlotRetriedConvergesOnOneJob() {
        coveredThrough(1);
        connectionCompleted(account);
        ReviewAutoCheck row = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        java.time.Instant slot = row.getNextCheckAt();

        // 같은 due slot을 두 번 수행한다 — claim 뒤에 그 차례가 다시 돌는 경우의 모양.
        assertThat(reconciler.turn(row, slot)).isEqualTo(ReviewAutoCheckReconciler.Outcome.TODAY);
        ScheduledAsideJob first = onDesk().orElseThrow();

        // 아직 책상에 있는 동안: 두 번째 작업을 만들지 않는다(진행 중인 일을 건드리지 않는다는 규칙이 먼저 막는다).
        assertThat(reconciler.turn(row, slot)).isEqualTo(ReviewAutoCheckReconciler.Outcome.SKIPPED);
        assertThat(jobs.count()).isEqualTo(1);

        // 끝난 뒤에도 같은 차례는 같은 요청이다 — client job id가 그 차례의 신원이므로 끝난 그 행으로 수렴한다.
        settleAsRead(first, 3);
        assertThat(reconciler.turn(row, slot)).isEqualTo(ReviewAutoCheckReconciler.Outcome.TODAY);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(onDesk()).isEmpty();
    }

    // ---------------------------------------------------------------- a person outranks it

    @Test
    @DisplayName("자동 확인이 읽는 중에 누르면, 거절이 아니라 그 작업을 본다")
    void aPressJoinsTheWorkInFlight() {
        coveredThrough(20);
        connectionCompleted(account);
        reconciler.tick(now);
        ScheduledAsideJob scheduled = onDesk().orElseThrow();

        // 사람이 누른다. 같은 계정, 같은 자료. 「이미 수집 중」이 아니라 진행 중인 그 작업이 답이다.
        Optional<ScheduledAsideJob> seen = dispatcher.liveFor(org, account.getId(),
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1);
        assertThat(seen).isPresent();
        assertThat(seen.get().getId()).isEqualTo(scheduled.getId());
        // 그리고 진행 중인 작업은 취소되지 않는다 — 지금 판매자의 화면을 읽고 있다.
        assertThat(jobs.findById(scheduled.getId()).orElseThrow().getStatus())
                .isIn(ScheduledAsideJobStatus.QUEUED, ScheduledAsideJobStatus.CLAIMED);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("사람의 작업이 책상에 있으면 tick은 비켜선다 — scheduler는 사람보다 앞서지 않는다")
    void theTickYieldsToAPress() {
        coveredThrough(1);
        connectionCompleted(account);
        // 판매자가 누른 읽기 하나가 이미 책상에 있다.
        dispatcher.dispatch(com.sellerops.responsibility.aside.AsideDispatch.operator(org, account.getId(),
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1, "press-1"));

        assertThat(reconciler.tick(now).skipped()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(jobs.findAll().get(0).getTrigger()).isEqualTo(AsideTrigger.OPERATOR);
    }

    // ---------------------------------------------------------------- device and auth

    @Test
    @DisplayName("도우미가 없으면 PAUSED_DEVICE — 설정은 그대로이고, 돌아오면 저절로 이어진다")
    void noHelperPausesWithoutRevoking() {
        coveredThrough(1);
        connectionCompleted(account);
        devices.deleteAll();

        assertThat(reconciler.tick(now).pausedDevice()).isEqualTo(1);
        ReviewAutoCheck row = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(row.getPausedReason()).isEqualTo(AutoCheckPause.PAUSED_DEVICE);
        // 끄지 않았다. 판매자의 의사는 기기 수명과 아무 관계가 없다.
        assertThat(row.on()).isTrue();
        assertThat(settings.view(org, account.getId()).enabled()).isTrue();

        // 새 맥, 새 device row. 재동의는 없다.
        linkHelper();
        now = now.plus(Duration.ofHours(1));
        row.setNextCheckAt(now);
        settingRows.save(row);
        assertThat(reconciler.tick(now).refreshedToday()).isEqualTo(1);
        assertThat(settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW").orElseThrow()
                .getPausedReason()).isNull();
    }

    @Test
    @DisplayName("그 계정을 마지막으로 읽어낸 데스크가 다음에도 쓰인다 — 가장 최근에 연결한 기기가 아니라")
    void theDeskThatReadThisStoreIsPreferred() {
        coveredThrough(20);
        connectionCompleted(account);
        reconciler.tick(now);
        ScheduledAsideJob first = onDesk().orElseThrow();
        assertThat(first.getDeviceId()).isEqualTo(deviceId);

        // 그 사이에 다른 맥을 연결했다. 「조직의 가장 새 grant」를 쓰면 다음 창이 이 스토어에 로그인되어
        // 있지 않은 쪽으로 가고, 한 창을 「로그인 필요」를 배우는 데 쓴다.
        UUID newer = linkHelper();
        assertThat(newer).isNotEqualTo(deviceId);

        // 창 하나가 settle되면 다음 창이 바로 책상에 올라간다 — 그때 데스크가 다시 해결된다.
        settleAsRead(first, 45);

        assertThat(onDesk().orElseThrow().getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("그 데스크가 사라지면 현재 살아 있는 도우미로 간다 — 멈추지 않는다")
    void aGoneDeskFallsBackToTheLiveOne() {
        coveredThrough(20);
        connectionCompleted(account);
        reconciler.tick(now);
        ScheduledAsideJob first = onDesk().orElseThrow();

        // 읽어낸 그 맥을 판매자가 연결 해제했고, 다른 맥이 연결되어 있다.
        UUID newer = linkHelper();
        settleAsRead(first, 45);
        ScheduledAsideJob second = onDesk().orElseThrow();
        assertThat(second.getDeviceId()).isEqualTo(deviceId);
        devices.findById(deviceId).ifPresent(d -> {
            d.setRevokedAt(now);
            devices.save(d);
        });
        settleAsRead(second, 45);

        assertThat(onDesk().orElseThrow().getDeviceId()).isEqualTo(newer);
    }

    @Test
    @DisplayName("로그인 벽은 timer가 다시 때리지 않는다 — 판매자가 로그인할 때 그 창부터 이어진다")
    void anAuthWallWaitsForThePerson() {
        coveredThrough(20);
        connectionCompleted(account);
        reconciler.tick(now);
        ScheduledAsideJob walled = onDesk().orElseThrow();
        LocalDate stoppedOn = walled.getRequestedWindowStart();
        settleAsAuthWall(walled);

        ReviewCatchUpRun paused = runs.findById(walled.getCatchUpRunId()).orElseThrow();
        assertThat(paused.getState()).isEqualTo(ReviewCatchUpState.PAUSED_AUTH);

        // 한 시간 뒤 tick: 벽을 다시 때리지 않는다. 책상은 비어 있고, 이유만 적힌다.
        now = now.plus(Duration.ofHours(1));
        settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW").ifPresent(row -> {
            row.setNextCheckAt(now);
            settingRows.save(row);
        });
        assertThat(reconciler.tick(now).pausedAuth()).isEqualTo(1);
        assertThat(onDesk()).isEmpty();
        assertThat(settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW").orElseThrow()
                .getPausedReason()).isEqualTo(AutoCheckPause.PAUSED_AUTH);

        // 판매자가 로그인한다. 멈춘 그 창이, 같은 trigger로 다시 책상에 올라간다.
        ReviewCatchUpOrchestrator.Started resumed = orchestrator.resumeAfterSignIn(org, account.getId(), "REVIEW",
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1).orElseThrow();
        assertThat(resumed.firstJob().getRequestedWindowStart()).isEqualTo(stoppedOn);
        assertThat(resumed.firstJob().getTrigger()).isEqualTo(AsideTrigger.SCHEDULED);
        assertThat(runs.findById(paused.getId()).orElseThrow().getState())
                .isEqualTo(ReviewCatchUpState.RUNNING);

        // 두 번째 로그인 알림은 아무 일도 하지 않는다 — 1회성은 상태 전이 자체가 보장한다.
        assertThat(orchestrator.resumeAfterSignIn(org, account.getId(), "REVIEW",
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1)).isEmpty();
        assertThat(jobs.findAll().stream().filter(j -> j.getStatus() == ScheduledAsideJobStatus.QUEUED)).hasSize(1);
    }

    @Test
    @DisplayName("부모 없는 오늘 읽기가 벽에 막히면 lane이 멈춘다 — timer가 같은 벽을 다시 때리지 않는다")
    void aWallOnTheParentlessReadPausesTheLane() {
        // 과거가 닫혀 있으면 오늘 읽기는 catch-up run에 속하지 않는다 — 전진시킬 경계가 없으므로. 그래서
        // 벽에 막혔을 때 PAUSED_AUTH를 들고 있을 run이 없고, 그대로 두면 다음 시간이 같은 벽으로 걸어간다.
        coveredThrough(1);
        connectionCompleted(account);
        reconciler.tick(now);
        ScheduledAsideJob walled = onDesk().orElseThrow();
        assertThat(walled.getCatchUpRunId()).isNull();
        settleAsAuthWall(walled);

        // 다음 차례: 아무것도 책상에 올리지 않고, 왜 멈췄는지만 적는다.
        now = now.plus(Duration.ofHours(1));
        assertThat(reconciler.tick(now).pausedAuth()).isEqualTo(1);
        assertThat(onDesk()).isEmpty();
        assertThat(jobs.count()).isEqualTo(1);
        ReviewAutoCheck row = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(row.getPausedReason()).isEqualTo(AutoCheckPause.PAUSED_AUTH);
        // 끄지 않았다. 벽은 판매자의 의사가 아니다.
        assertThat(row.on()).isTrue();
    }

    @Test
    @DisplayName("벽은 드물게 다시 본다 — 판매자가 어디서 로그인했든 lane이 스스로 돌아온다")
    void theWallIsLookedAtAgainRarelyEnoughToNotBeNoise() {
        // <b>2026-10-09 라이브에서 드러난 교착.</b> 판매자가 2차 인증 문제로 제품 창을 닫고 자기 브라우저의
        // 다른 탭에서 직접 로그인했다. 세션은 돌아왔지만 lane은 영원히 PAUSED_AUTH였다 — 벽을 지우는 것은
        // 성공한 읽기 하나뿐인데, lane은 벽이 있다고 보는 동안 읽지 않았으므로.
        coveredThrough(1);
        connectionCompleted(account);
        reconciler.tick(now);
        settleAsAuthWall(onDesk().orElseThrow());

        // 다음 시간: 보지 않는다. 그리고 다시 볼 시각이 한 시간이 아니라 여섯 시간 뒤로 밀린다.
        now = now.plus(Duration.ofHours(1));
        setDue();
        assertThat(reconciler.tick(now).pausedAuth()).isEqualTo(1);
        assertThat(onDesk()).isEmpty();
        ReviewAutoCheck paused = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(paused.getPausedReason()).isEqualTo(AutoCheckPause.PAUSED_AUTH);
        assertThat(paused.getNextCheckAt()).isEqualTo(now.plus(ReviewAutoCheckClaimer.AUTH_RETRY));

        // 여섯 시간 뒤: 다시 본다. 판매자가 그사이 어디서든 로그인했다면 이번 읽기는 성공하고,
        // 그 성공이 벽을 지운다 — 제품이 로그인을 목격할 필요가 없다.
        now = now.plus(ReviewAutoCheckClaimer.AUTH_RETRY);
        assertThat(reconciler.tick(now).refreshedToday()).isEqualTo(1);
        ScheduledAsideJob retried = onDesk().orElseThrow();
        assertThat(retried.getRequestedWindowStart()).isEqualTo(today);
        settleAsRead(retried, 3);

        // 성공한 뒤에는 멈춤이 풀리고 주기가 돌아온다.
        now = now.plus(Duration.ofHours(1));
        setDue();
        assertThat(reconciler.tick(now).refreshedToday()).isEqualTo(1);
        ReviewAutoCheck recovered = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(recovered.getPausedReason()).isNull();
        assertThat(recovered.getNextCheckAt()).isEqualTo(now.plus(Duration.ofMinutes(60)));
    }

    @Test
    @DisplayName("도우미가 없는 멈춤은 주기 그대로다 — 데스크를 찾는 일은 아무 화면도 열지 않는다")
    void theDevicePauseKeepsTheOrdinaryCadence() {
        coveredThrough(1);
        connectionCompleted(account);
        devices.deleteAll();

        assertThat(reconciler.tick(now).pausedDevice()).isEqualTo(1);
        ReviewAutoCheck row = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(row.getPausedReason()).isEqualTo(AutoCheckPause.PAUSED_DEVICE);
        assertThat(row.getNextCheckAt()).isEqualTo(now.plus(Duration.ofMinutes(60)));
    }

    @Test
    @DisplayName("그 벽은 판매자가 로그인할 때 풀린다 — 한 번만, 그리고 설정이 켜져 있을 때만")
    void theParentlessWallIsResumedBySigningIn() {
        coveredThrough(1);
        connectionCompleted(account);
        reconciler.tick(now);
        settleAsAuthWall(onDesk().orElseThrow());

        // 판매자가 로그인한다. 기다리던 것은 그 읽기 자체이고, 승인은 여전히 그 설정이다.
        ScreenReadView resumed = screenReads.resumeAfterSignIn(org, account.getId(), "REVIEW").orElseThrow();
        assertThat(jobs.count()).isEqualTo(2);
        ScheduledAsideJob second = jobs.findById(resumed.jobId()).orElseThrow();
        assertThat(second.getTrigger()).isEqualTo(AsideTrigger.SCHEDULED);
        assertThat(second.getRequestedWindowStart()).isEqualTo(today);
        assertThat(second.getRequestedWindowEnd()).isEqualTo(today);

        // 두 번째 알림은 진행 중인 그 작업을 돌려주고, 세 번째 작업을 만들지 않는다.
        ScreenReadView again = screenReads.resumeAfterSignIn(org, account.getId(), "REVIEW").orElseThrow();
        assertThat(again.jobId()).isEqualTo(second.getId());
        assertThat(jobs.count()).isEqualTo(2);

        // 그 읽기가 끝나면 더 이상 벽이 아니므로, 또 한 번의 로그인은 아무것도 이어가지 않는다.
        // 시계를 앞으로 보낸다: 「마지막으로 끝난 읽기」는 settled_at 순서로 정해지고, 두 읽기가 같은 순간에
        // 끝나면 그 순서가 없다. 진짜 시계에서는 늘 다르지만, 여기서는 가짜 시계가 멈춰 있다.
        tick(Duration.ofMinutes(1));
        settleAsRead(second, 3);
        assertThat(screenReads.resumeAfterSignIn(org, account.getId(), "REVIEW")).isEmpty();
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("자동 확인이 꺼진 계정에서는 로그인이 아무것도 시작하지 않는다 — 누름만이 그 승인이다")
    void signingInResumesNothingWhenTheSettingIsOff() {
        coveredThrough(1);
        connectionCompleted(account);
        reconciler.tick(now);
        settleAsAuthWall(onDesk().orElseThrow());
        settings.set(org, account.getId(), userId, false);

        assertThat(screenReads.resumeAfterSignIn(org, account.getId(), "REVIEW")).isEmpty();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("멈춘 것이 없으면 로그인은 아무것도 시작하지 않는다")
    void signingInWithNothingPausedStartsNothing() {
        coveredThrough(1);
        connectionCompleted(account);

        assertThat(orchestrator.resumeAfterSignIn(org, account.getId(), "REVIEW",
                AsideRecipe.NAVER_REVIEW_OBSERVE_V1)).isEmpty();
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("읽기가 도착하면 그 자리에서 풀린다 — 다음 차례를 기다리지 않는다")
    void aLandedReadClearsThePauseAtOnce() {
        // <b>2026-10-09 라이브에서 측정된 것.</b> 이어진 읽기가 OBSERVED로 끝났고 데스크는 READY였고
        // freshness는 「오늘 23:49 확인」으로 움직였는데, 설정만 여섯 시간 동안 PAUSED_AUTH였다 — 그것을
        // 지우는 유일한 손이 claimer의 다음 차례였으므로. 그 여섯 시간 동안 화면은 이미 로그인한 가게에
        // 로그인하라고 말했다.
        coveredThrough(1);
        connectionCompleted(account);
        reconciler.tick(now);
        settleAsAuthWall(onDesk().orElseThrow());
        now = now.plus(Duration.ofHours(1));
        reconciler.tick(now);
        ReviewAutoCheck parked = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(parked.getPausedReason()).isEqualTo(AutoCheckPause.PAUSED_AUTH);
        assertThat(parked.getNextCheckAt()).isEqualTo(now.plus(ReviewAutoCheckClaimer.AUTH_RETRY));

        // 판매자가 로그인하고, 이어진 읽기가 도착한다. tick은 한 번도 더 돌지 않는다.
        ScreenReadView resumed = screenReads.resumeAfterSignIn(org, account.getId(), "REVIEW").orElseThrow();
        tick(Duration.ofMinutes(1));
        settleAsRead(jobs.findById(resumed.jobId()).orElseThrow(), 3);

        ReviewAutoCheck row = settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow();
        assertThat(row.getPausedReason()).isNull();
        // 그리고 여섯 시간짜리 유보도 함께 사라진다 — 그것은 벽에 대한 backoff였고, 벽이 없다.
        assertThat(row.getNextCheckAt()).isEqualTo(now.plus(Duration.ofMinutes(ReviewAutoCheck.INTERVAL_MINUTES)));
    }

    @Test
    @DisplayName("누가 읽었는지는 묻지 않는다 — 벽은 채널 세션에 대한 사실이고, 성공한 읽기 하나면 거짓이 된다")
    void anyLandedReadClearsIt() {
        coveredThrough(1);
        connectionCompleted(account);
        reconciler.tick(now);
        settleAsAuthWall(onDesk().orElseThrow());
        now = now.plus(Duration.ofHours(1));
        reconciler.tick(now);

        // 자동 확인이 아니라 판매자가 직접 누른 읽기다.
        tick(Duration.ofMinutes(1));
        ScreenReadView pressed = screenReads.start(org, account.getId(), "REVIEW", "press-1");
        ScheduledAsideJob job = jobs.findById(pressed.jobId()).orElseThrow();
        assertThat(job.getTrigger()).isEqualTo(AsideTrigger.OPERATOR);
        settleAsRead(job, 2);

        assertThat(settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow().getPausedReason()).isNull();
    }

    @Test
    @DisplayName("끝나지 않은 벽은 그대로 둔다 — 또 하나의 AUTH_REQUIRED는 아무것도 증명하지 않는다")
    void aSecondWallLeavesThePauseAlone() {
        coveredThrough(1);
        connectionCompleted(account);
        reconciler.tick(now);
        settleAsAuthWall(onDesk().orElseThrow());
        now = now.plus(Duration.ofHours(1));
        reconciler.tick(now);

        tick(Duration.ofMinutes(1));
        ScreenReadView pressed = screenReads.start(org, account.getId(), "REVIEW", "press-1");
        settleAsAuthWall(jobs.findById(pressed.jobId()).orElseThrow());

        assertThat(settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow().getPausedReason()).isEqualTo(AutoCheckPause.PAUSED_AUTH);
    }

    @Test
    @DisplayName("한 보고를 두 lane이 함께 듣는다 — walk는 다음 창으로 가고, 설정은 벽을 내려놓는다")
    void bothLanesHearOneReport() {
        // 목록이 아니라 자리 하나였을 때, 나중에 붙은 lane이 먼저 붙은 lane을 조용히 밀어냈다.
        coveredThrough(40);
        connectionCompleted(account);
        reconciler.tick(now);
        ScheduledAsideJob first = onDesk().orElseThrow();
        assertThat(first.getCatchUpRunId()).isNotNull();

        // 그 사이 이 줄은 벽에 주차돼 있었다고 하자 — 지금 책상 위의 읽기가 그것을 거짓으로 만든다.
        settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW").ifPresent(row -> {
            row.setPausedReason(AutoCheckPause.PAUSED_AUTH);
            settingRows.save(row);
        });
        tick(Duration.ofMinutes(1));
        settleAsRead(first, 5);

        // walk: 다음 창이 책상에 올라왔다.
        ScheduledAsideJob next = onDesk().orElseThrow();
        assertThat(next.getId()).isNotEqualTo(first.getId());
        // 설정: 벽이 사라졌다.
        assertThat(settingRows.findBySellerAccountIdAndDataType(account.getId(), "REVIEW")
                .orElseThrow().getPausedReason()).isNull();
    }
}
