package com.sellerops.intakealert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.credential.VaultKeyRing;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.itemanalysis.ItemAnalysisRepository;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.sellerops.common.ApiException;

/**
 * Intake alerts over the real repositories (H2, committed — no test transaction, so the engine's own transactions and
 * the unique constraint behave as in production). Each test uses its own org, so tests do not see each other's rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IntakeAlertEngineTest {

    @Autowired IntakeAlertSettingRepository settings;
    @Autowired IntakeAlertItemRepository items;
    @Autowired IntakeAlertDeliveryRepository deliveries;
    @Autowired ReviewRepository reviews;
    @Autowired InquiryRepository inquiries;
    @Autowired ChannelRepository channels;
    @Autowired ItemAnalysisRepository analyses;
    @Autowired OrganizationRepository organizations;
    @Autowired PlatformTransactionManager txManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate cleanupJdbc;

    /** Every org this test made; its committed rows are removed after the test so later classes see a clean database. */
    final List<UUID> createdOrgs = new ArrayList<>();

    /** A sender that records what it was handed. */
    static final class RecordingSender implements IntakeAlertSender {
        final IntakeAlertDelivery.Mode mode;
        final List<OutgoingAlert> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        RecordingSender(IntakeAlertDelivery.Mode mode) {
            this.mode = mode;
        }

        @Override public IntakeAlertDelivery.Mode mode() { return mode; }
        @Override public boolean needsNumber() { return mode == IntakeAlertDelivery.Mode.LIVE; }
        @Override public Outcome send(OutgoingAlert alert) {
            calls.incrementAndGet();
            sent.add(alert);
            return mode == IntakeAlertDelivery.Mode.LIVE
                    ? new Outcome(IntakeAlertDelivery.Status.ACCEPTED, "M-1", null) : Outcome.mockRecorded();
        }
    }

    UUID orgId;
    UUID userId;
    UUID channelId;
    RecordingSender mock;
    LocalSecretSealer sealer;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("데모 가게");
        orgId = organizations.save(o).getId();
        createdOrgs.add(orgId);
        userId = newUserId(orgId);
        // A channel of its own: this class commits (no test transaction), and a shared code such as NAVER would collide
        // with every later class that creates it in the same H2 database.
        Channel c = new Channel();
        c.setCode("IA-" + UUID.randomUUID().toString().substring(0, 8));
        c.setNameKo("알림 시험 채널");
        c.setStatus(ChannelStatus.CONNECTED);
        channelId = channels.save(c).getId();
        mock = new RecordingSender(IntakeAlertDelivery.Mode.MOCK);
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        sealer = new LocalSecretSealer(new VaultKeyRing(Base64.getEncoder().encodeToString(key), "test-1", ""));
    }

    @org.junit.jupiter.api.AfterEach
    void removeCommittedRows() {
        for (UUID org : createdOrgs) {
            for (String table : List.of("intake_alert_items", "intake_alert_deliveries", "intake_alert_settings", "users",
                    "item_analyses", "reviews", "inquiries")) {
                cleanupJdbc.update("delete from " + table + " where org_id = ?", org);
            }
            cleanupJdbc.update("delete from organizations where id = ?", org);
        }
        cleanupJdbc.update("delete from channels where id = ?", channelId);
        createdOrgs.clear();
    }

    /** H2 has no users foreign key; the Postgres proof inserts a real user row. */
    protected UUID newUserId(UUID org) {
        return UUID.randomUUID();
    }

    IntakeAlertEngine engine(IntakeAlertSender sender, int perPassCap) {
        return new IntakeAlertEngine(settings, items, deliveries, subjects(), sender, organizations, sealer, txManager,
                "https://app.example.test", 48, 72, perPassCap);
    }

    IntakeAlertSubjects subjects() {
        return new IntakeAlertSubjects(reviews, inquiries, channels, analyses);
    }

    /** The real service behind the same @Transactional proxy Spring gives it in production. */
    IntakeAlertSettingsService service(IntakeAlertSender sender) {
        IntakeAlertSettingsService target = new IntakeAlertSettingsService(settings, items, deliveries, subjects(),
                sender, sealer, 48, 72, 5, 30000);
        org.springframework.aop.framework.ProxyFactory pf = new org.springframework.aop.framework.ProxyFactory(target);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                (org.springframework.transaction.TransactionManager) txManager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (IntakeAlertSettingsService) pf.getProxy();
    }

    IntakeAlertSetting turnOn(IntakeAlertSender sender) {
        service(sender).update(orgId, userId, req(true));
        return settings.findByOrgIdAndUserId(orgId, userId).orElseThrow();
    }

    static IntakeAlertViews.SettingsRequest req(Boolean enabled) {
        return new IntakeAlertViews.SettingsRequest(enabled, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    Review review(UUID org, String body, Integer rating, Instant written) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(channelId);
        r.setBody(body);
        r.setRating(rating);
        r.setReceivedAt(written);
        r.setNegative(rating != null && rating <= 2);
        return reviews.save(r);
    }

    Review review(String body) {
        return review(orgId, body, 5, Instant.now());
    }

    Inquiry inquiry(String body) {
        Inquiry q = new Inquiry();
        q.setOrgId(orgId);
        q.setChannelId(channelId);
        q.setTitle("문의");
        q.setBody(body);
        q.setStatus("UNANSWERED");
        q.setReceivedAt(Instant.now());
        return inquiries.save(q);
    }

    List<IntakeAlertDelivery> deliveriesOf(IntakeAlertSetting s) {
        return deliveries.findBySettingIdOrderByCreatedAtDesc(s.getId(), PageRequest.of(0, 100));
    }

    List<IntakeAlertItem> itemsOf(IntakeAlertSetting s) {
        return items.findBySettingIdOrderByCreatedAtDesc(s.getId(), PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("이벤트 없이 저장만 된 리뷰도 정기 확인이 원본 표에서 찾아 건마다 1회 모의 알림으로 남긴다")
    void reconcilerFindsRowsWithoutTheEvent() {
        IntakeAlertSetting s = turnOn(mock);
        review("김철수 010-1234-5678 주문번호 2026111612345 포장이 찢겨서 왔어요");
        inquiry("배송 언제 오나요? 연락처 010-9999-0000");

        engine(mock, 5).runOrg(orgId);

        List<IntakeAlertDelivery> ds = deliveriesOf(s);
        assertThat(ds).hasSize(2).allSatisfy(d -> {
            assertThat(d.getKind()).isEqualTo(IntakeAlertDelivery.Kind.SINGLE);
            assertThat(d.getStatus()).isEqualTo(IntakeAlertDelivery.Status.MOCK_RECORDED);
            assertThat(d.getMode()).isEqualTo(IntakeAlertDelivery.Mode.MOCK);
            assertThat(d.getLinkPath()).isEqualTo("/notice/" + d.getId());
            assertThat(d.getMessageText()).startsWith("[리뷰너리] ")
                    .doesNotContain("김철수", "010-", "2026111612345", "찢겨", "언제 오나요");
        });
        assertThat(mock.sent).hasSize(2).allSatisfy(a -> {
            assertThat(a.toPhone()).isNull();
            assertThat(a.linkUrl()).startsWith("https://app.example.test/notice/");
        });
    }

    @Test
    @DisplayName("켜기 전에 들어온 자료와, 켠 뒤 들어왔지만 고객이 오래전에 쓴 글은 알리지 않는다")
    void noHistoryFlood() {
        review("켜기 전 리뷰");
        IntakeAlertSetting s = turnOn(mock);
        Review late = review(orgId, "1년 전 리뷰를 이제 가져옴", 5, Instant.now().minus(Duration.ofDays(300)));

        engine(mock, 5).runOrg(orgId);

        assertThat(itemsOf(s)).singleElement().satisfies(i -> {
            assertThat(i.getSubjectId()).isEqualTo(late.getId());
            assertThat(i.getStatus()).isEqualTo(IntakeAlertItem.Status.DROPPED_OLD);
        });
        assertThat(deliveriesOf(s)).isEmpty();
        assertThat(service(mock).get(orgId, userId).counts().oldNotTold()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트 반복·재실행·재수집에도 원본당 항목 1개, 알림 1건")
    void idempotentAcrossPassesAndEvents() {
        IntakeAlertSetting s = turnOn(mock);
        review("한 건");
        IntakeAlertEngine e = engine(mock, 5);
        e.runOrg(orgId);
        e.runOrg(orgId);
        e.runAll();
        assertThat(itemsOf(s)).hasSize(1);
        assertThat(deliveriesOf(s)).hasSize(1);
        assertThat(mock.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("한꺼번에 7건이면 회차당 5건만 각각 보내고 나머지는 대기 — 묶지 않는다")
    void perPassCapIsARateLimitNotAMerge() {
        IntakeAlertSetting s = turnOn(mock);
        for (int i = 0; i < 7; i++) {
            review("리뷰 " + i);
        }
        IntakeAlertEngine e = engine(mock, 5);
        e.runOrg(orgId);
        assertThat(deliveriesOf(s)).hasSize(5).allSatisfy(d -> assertThat(d.getKind()).isEqualTo(IntakeAlertDelivery.Kind.SINGLE));
        assertThat(items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.PENDING)).isEqualTo(2);
        e.runOrg(orgId);
        assertThat(deliveriesOf(s)).hasSize(7).allSatisfy(d -> assertThat(d.getKind()).isEqualTo(IntakeAlertDelivery.Kind.SINGLE));
    }

    @Test
    @DisplayName("다른 조직의 리뷰는 알리지 않고, 다른 조직 계정은 알림 상세를 읽지 못한다")
    void orgBoundary() {
        IntakeAlertSetting s = turnOn(mock);
        Organization other = new Organization();
        other.setName("다른 가게");
        UUID otherOrg = organizations.save(other).getId();
        createdOrgs.add(otherOrg);
        review(otherOrg, "다른 조직 리뷰", 5, Instant.now());
        review("우리 리뷰");
        engine(mock, 5).runOrg(orgId);
        engine(mock, 5).runOrg(otherOrg);
        IntakeAlertDelivery d = deliveriesOf(s).get(0);
        assertThat(deliveriesOf(s)).hasSize(1);
        assertThat(itemsOf(s)).allSatisfy(i -> assertThat(i.getOrgId()).isEqualTo(orgId));
        assertThatThrownBy(() -> service(mock).detail(otherOrg, userId, d.getId()))
                .isInstanceOf(ApiException.class).hasMessageContaining("알림을 찾을 수 없어요");
        assertThatThrownBy(() -> service(mock).detail(orgId, newUserId(orgId), d.getId()))
                .isInstanceOf(ApiException.class).hasMessageContaining("알림을 찾을 수 없어요");
        assertThat(service(mock).detail(orgId, userId, d.getId()).items()).singleElement()
                .satisfies(i -> assertThat(i.targetPath()).startsWith("/reviews/reply/"));
    }

    @Test
    @DisplayName("동시 4개 작업자가 같은 5건을 처리해도 항목 5개·알림 5건, 항목은 알림 하나에만")
    void concurrentWorkers() throws Exception {
        IntakeAlertSetting s = turnOn(mock);
        for (int i = 0; i < 5; i++) {
            review("동시 " + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            IntakeAlertEngine e = engine(mock, 5);
            fs.add(pool.submit(() -> {
                start.await();
                e.runOrg(orgId);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(itemsOf(s)).hasSize(5).allSatisfy(i -> {
            assertThat(i.getStatus()).isEqualTo(IntakeAlertItem.Status.INCLUDED);
            assertThat(i.getDeliveryId()).isNotNull();
        });
        assertThat(deliveriesOf(s)).hasSize(5);
        assertThat(itemsOf(s).stream().map(IntakeAlertItem::getDeliveryId).distinct()).hasSize(5);
        assertThat(mock.calls.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("보내는 중에 멈춘 알림(재기동)은 결과 확인 필요로 남고 다시 보내지 않는다")
    void restartLeavesUnknownAndNeverResends() {
        IntakeAlertSetting s = turnOn(mock);
        review("재기동");
        IntakeAlertEngine e = engine(mock, 5);
        e.reconcile(s);
        e.compose(settings.findById(s.getId()).orElseThrow());
        IntakeAlertDelivery d = deliveriesOf(s).get(0);
        // Simulate a crash after the claim: SENDING, claimed ten minutes ago, never recorded.
        d.setStatus(IntakeAlertDelivery.Status.SENDING);
        d.setClaimedAt(Instant.now().minus(Duration.ofMinutes(10)));
        deliveries.save(d);

        IntakeAlertEngine restarted = engine(mock, 5);
        restarted.runOrg(orgId);
        restarted.runOrg(orgId);

        IntakeAlertDelivery after = deliveries.findById(d.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(IntakeAlertDelivery.Status.UNKNOWN);
        assertThat(after.getFailureCode()).isEqualTo("INTERRUPTED");
        assertThat(mock.calls.get()).isZero();
        assertThat(deliveriesOf(s)).hasSize(1);
    }

    @Test
    @DisplayName("PENDING이 남은 채 재기동해도 다음 회차가 이어서 보낸다")
    void pendingSurvivesRestart() {
        IntakeAlertSetting s = turnOn(mock);
        review("대기 중");
        engine(mock, 5).reconcile(s);
        assertThat(items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.PENDING)).isEqualTo(1);
        engine(mock, 5).runAll();
        assertThat(deliveriesOf(s)).singleElement()
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(IntakeAlertDelivery.Status.MOCK_RECORDED));
    }

    @Test
    @DisplayName("끄면 대기·준비된 알림이 이유와 함께 종결되고, 다시 켜도 예전 대기분은 보내지 않는다")
    void offCancelsAndOnDoesNotResurrect() {
        IntakeAlertSetting s = turnOn(mock);
        Review r = review("끄기 전 대기");
        IntakeAlertEngine e = engine(mock, 5);
        e.reconcile(s);
        e.compose(settings.findById(s.getId()).orElseThrow());
        Review r2 = review("끄기 전 두 번째");
        e.reconcile(settings.findById(s.getId()).orElseThrow());

        service(mock).update(orgId, userId, req(false));
        IntakeAlertDelivery d = deliveriesOf(s).get(0);
        assertThat(d.getStatus()).isEqualTo(IntakeAlertDelivery.Status.CANCELLED);
        assertThat(d.getFailureCode()).isEqualTo("CANCELLED_TURNED_OFF");
        assertThat(itemsOf(s)).hasSize(2).allSatisfy(i -> {
            assertThat(i.getStatus()).isEqualTo(IntakeAlertItem.Status.CANCELLED);
            assertThat(i.getCancelReason()).isEqualTo(IntakeAlertItem.CancelReason.TURNED_OFF);
        });

        service(mock).update(orgId, userId, req(true));
        e.runOrg(orgId);
        assertThat(mock.calls.get()).isZero();
        assertThat(deliveriesOf(s)).hasSize(1);
        assertThat(itemsOf(s).stream().map(IntakeAlertItem::getSubjectId)).containsExactlyInAnyOrder(r.getId(), r2.getId());

        review("다시 켠 뒤");
        e.runOrg(orgId);
        assertThat(mock.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("번호를 바꾸면 예전 번호로 준비된 알림은 새 번호로 보내지 않고 종결한다")
    void numberChangeNeverReaimsTheQueue() {
        RecordingSender live = new RecordingSender(IntakeAlertDelivery.Mode.LIVE);
        IntakeAlertSettingsService svc = service(live);
        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(true, null, null, null, null, null, null, null,
                null, "010-1111-2222", null, true));
        IntakeAlertSetting s = settings.findByOrgIdAndUserId(orgId, userId).orElseThrow();
        review("번호 바꾸기 전");
        IntakeAlertEngine e = engine(live, 5);
        e.reconcile(s);
        e.compose(settings.findById(s.getId()).orElseThrow());

        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(null, null, null, null, null, null, null, null,
                null, "010-3333-4444", null, true));
        e.dispatch();

        assertThat(live.calls.get()).isZero();
        assertThat(deliveriesOf(s)).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(IntakeAlertDelivery.Status.CANCELLED);
            assertThat(d.getFailureCode()).isEqualTo("CANCELLED_NUMBER_CHANGED");
        });
        assertThat(svc.get(orgId, userId).phoneMasked()).isEqualTo("***-****-4444");

        review("번호 바꾼 뒤");
        e.runOrg(orgId);
        assertThat(live.sent).singleElement().satisfies(a -> assertThat(a.toPhone()).isEqualTo("01033334444"));
    }

    @Test
    @DisplayName("동의를 철회하면 번호가 지워지고 대기분은 종결 — 실발송 어댑터는 번호 없이 보내지 않는다")
    void consentWithdrawn() {
        RecordingSender live = new RecordingSender(IntakeAlertDelivery.Mode.LIVE);
        IntakeAlertSettingsService svc = service(live);
        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(true, null, null, null, null, null, null, null,
                null, "01011112222", null, true));
        IntakeAlertSetting s = settings.findByOrgIdAndUserId(orgId, userId).orElseThrow();
        review("철회 전");
        engine(live, 5).reconcile(s);
        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(null, null, null, null, null, null, null, null,
                null, null, null, false));
        assertThat(itemsOf(s)).singleElement()
                .satisfies(i -> assertThat(i.getCancelReason()).isEqualTo(IntakeAlertItem.CancelReason.CONSENT_WITHDRAWN));
        review("철회 후");
        engine(live, 5).runOrg(orgId);
        assertThat(live.calls.get()).isZero();
        assertThat(deliveriesOf(s)).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(IntakeAlertDelivery.Status.BLOCKED);
            assertThat(d.getFailureCode()).isEqualTo("NO_RECIPIENT_NUMBER");
        });
        assertThat(svc.get(orgId, userId).phoneMasked()).isNull();
    }

    @Test
    @DisplayName("리뷰 알림을 끄면 리뷰 대기분만 종결, 다시 켜도 꺼져 있던 동안의 리뷰는 알리지 않는다")
    void kindOffAndBackOn() {
        IntakeAlertSetting s = turnOn(mock);
        review("리뷰 대기");
        inquiry("문의 대기");
        IntakeAlertEngine e = engine(mock, 5);
        e.reconcile(s);
        IntakeAlertSettingsService svc = service(mock);
        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(null, false, null, null, null, null, null, null,
                null, null, null, null));
        review("꺼진 동안의 리뷰");
        e.runOrg(orgId);
        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(null, true, null, null, null, null, null, null,
                null, null, null, null));
        e.runOrg(orgId);
        List<IntakeAlertItem> all = itemsOf(s);
        assertThat(all).filteredOn(i -> i.getSubjectKind() == IntakeAlertItem.Kind.REVIEW).singleElement()
                .satisfies(i -> assertThat(i.getCancelReason()).isEqualTo(IntakeAlertItem.CancelReason.KIND_OFF));
        assertThat(deliveriesOf(s)).singleElement().satisfies(d -> assertThat(d.getInquiryCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("보내기 직전 설정 세대가 다르면(경합) 보내지 않고 이유와 함께 종결한다")
    void preSendCheckCatchesARace() {
        IntakeAlertSetting s = turnOn(mock);
        review("경합");
        IntakeAlertEngine e = engine(mock, 5);
        e.reconcile(s);
        e.compose(settings.findById(s.getId()).orElseThrow());
        IntakeAlertSetting now = settings.findById(s.getId()).orElseThrow();
        now.setGeneration(now.getGeneration() + 1); // a change that slipped past the settings transaction
        settings.save(now);
        e.dispatch();
        assertThat(mock.calls.get()).isZero();
        assertThat(deliveriesOf(s)).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(IntakeAlertDelivery.Status.CANCELLED);
            assertThat(d.getFailureCode()).isEqualTo("SETTING_CHANGED");
        });
        assertThat(itemsOf(s)).singleElement()
                .satisfies(i -> assertThat(i.getStatus()).isEqualTo(IntakeAlertItem.Status.CANCELLED));
    }

    @Test
    @DisplayName("알리기 전에 이미 답한 리뷰는 알리지 않는다")
    void handledBeforeTold() {
        IntakeAlertSetting s = turnOn(mock);
        Review r = review("이미 답함");
        r.setReplyState(ReviewReplyState.ANSWERED);
        reviews.save(r);
        engine(mock, 5).runOrg(orgId);
        assertThat(itemsOf(s)).singleElement()
                .satisfies(i -> assertThat(i.getStatus()).isEqualTo(IntakeAlertItem.Status.DROPPED_HANDLED));
        assertThat(mock.calls.get()).isZero();
    }

    @Test
    @DisplayName("수신 시간을 켜고 시간 밖이면 일반 건은 기다리고, 안전 제보는 바로 보낸다")
    void quietHoursHoldOrdinaryButNotSafety() {
        IntakeAlertSettingsService svc = service(mock);
        // The window is open only on a day that is not today (Seoul), so "now" is outside it.
        int today = java.time.ZonedDateTime.now(IntakeAlertEngine.SEOUL).getDayOfWeek().getValue();
        int otherDay = 1 << (today % 7);
        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(true, null, null, null, null, true, otherDay,
                540, 1080, null, null, null));
        IntakeAlertSetting s = settings.findByOrgIdAndUserId(orgId, userId).orElseThrow();
        review("보통 리뷰");
        Review danger = review(orgId, "충전하다가 연기가 나서 손에 화상을 입었어요", 1, Instant.now());
        engine(mock, 5).runOrg(orgId);
        assertThat(deliveriesOf(s)).singleElement().satisfies(d -> {
            assertThat(d.getUrgentCount()).isEqualTo(1);
            assertThat(d.getMessageText()).contains("안전 관련 리뷰가 확인됐어요").doesNotContain("화상");
        });
        assertThat(itemsOf(s)).filteredOn(i -> i.getSubjectId().equals(danger.getId())).singleElement()
                .satisfies(i -> assertThat(i.getReason()).isEqualTo(IntakeAlertItem.Reason.SAFETY));
        assertThat(items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.PENDING)).isEqualTo(1);
    }

    @Test
    @DisplayName("묶음(DIGEST)은 사용자가 고른 경우에만 — 간격 안에서는 한 번")
    void digestOnlyWhenChosen() {
        IntakeAlertSettingsService svc = service(mock);
        svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(true, null, null, "DIGEST", 60, null, null, null,
                null, null, null, null));
        IntakeAlertSetting s = settings.findByOrgIdAndUserId(orgId, userId).orElseThrow();
        review("a");
        review("b");
        inquiry("c");
        IntakeAlertEngine e = engine(mock, 5);
        e.runOrg(orgId);
        review("d");
        e.runOrg(orgId);
        assertThat(deliveriesOf(s)).singleElement().satisfies(d -> {
            assertThat(d.getKind()).isEqualTo(IntakeAlertDelivery.Kind.DIGEST);
            assertThat(d.getReviewCount()).isEqualTo(2);
            assertThat(d.getInquiryCount()).isEqualTo(1);
        });
        assertThat(items.countBySettingIdAndStatus(s.getId(), IntakeAlertItem.Status.PENDING)).isEqualTo(1);
    }

    @Test
    @DisplayName("모의로 준비된 알림을 실발송 모드로 재기동해 보내지 않는다")
    void modeChangeNeverPromotesAMock() {
        IntakeAlertSetting s = turnOn(mock);
        review("모의 준비");
        IntakeAlertEngine e = engine(mock, 5);
        e.reconcile(s);
        e.compose(settings.findById(s.getId()).orElseThrow());
        RecordingSender live = new RecordingSender(IntakeAlertDelivery.Mode.LIVE);
        engine(live, 5).dispatch();
        assertThat(live.calls.get()).isZero();
        assertThat(deliveriesOf(s)).singleElement()
                .satisfies(d -> assertThat(d.getFailureCode()).isEqualTo("MODE_CHANGED"));
    }

    @Test
    @DisplayName("번호는 동의 없이는 저장하지 않고, 형식이 틀리면 거절한다")
    void numberRules() {
        IntakeAlertSettingsService svc = service(mock);
        assertThatThrownBy(() -> svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(true, null, null, null,
                null, null, null, null, null, "010-1111-2222", null, null))).hasMessageContaining("동의");
        assertThatThrownBy(() -> svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(true, null, null, null,
                null, null, null, null, null, "02-123-4567", null, true))).hasMessageContaining("형식");
        IntakeAlertViews.SettingsView v = svc.update(orgId, userId, new IntakeAlertViews.SettingsRequest(true, null,
                null, null, null, null, null, null, null, "01011112222", null, true));
        assertThat(v.phoneMasked()).isEqualTo("***-****-2222");
        IntakeAlertSetting s = settings.findByOrgIdAndUserId(orgId, userId).orElseThrow();
        assertThat(new String(s.getPhoneSealed(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("01011112222");
    }
}
