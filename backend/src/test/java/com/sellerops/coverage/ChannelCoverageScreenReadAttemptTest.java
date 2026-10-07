package com.sellerops.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.DataOrigin;
import com.sellerops.connector.ConnectorCapabilityRepository;
import com.sellerops.coverage.dto.ChannelCoverageRow;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.OrderDailySummaryRepository;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.AsideTrigger;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewimport.ReviewImportSegmentAttemptRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import com.sellerops.sync.SyncScheduleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A screen read is an attempt, and it leaves no sync run.</b>
 *
 * <p>The failure this pins, measured 2026-10-08 on the live demo org: the seller pressed 「지금 수집하기」 on
 * NAVER 리뷰, the dispatched read reached the seller centre and settled {@code AUTH_REQUIRED} — and
 * {@code sync_jobs} did not gain a row. Coverage's «latest attempt», if it looked only at connector runs,
 * would have answered «nothing attempted» about a channel attempted four minutes earlier, which is the same
 * class of silence that let a sign-in wall read as 「확인된 적 없음」 the day before.
 *
 * <p>The companion rule is here too: what the screen read reports must not overwrite the date of a read that
 * really happened. NAVER 리뷰 holds 4,432 rows acquired through the export lane on 09-02; an AUTH_REQUIRED
 * today says the login died, not that September never happened.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ChannelCoverageScreenReadAttemptTest {

    @Autowired ChannelRepository channels;
    @Autowired ConnectorCapabilityRepository capabilities;
    @Autowired SellerAccountRepository accounts;
    @Autowired SyncScheduleRepository schedules;
    @Autowired SyncJobRepository syncJobs;
    @Autowired InquiryRepository inquiries;
    @Autowired ReviewRepository reviews;
    @Autowired OrderDailySummaryRepository orders;
    @Autowired ReviewImportSegmentAttemptRepository acquisitions;
    @Autowired ScheduledAsideJobRepository screenReads;

    private static final Instant READ_ON_09_02 = Instant.parse("2026-09-02T03:43:03Z");
    private static final Instant PRESSED_TODAY = Instant.parse("2026-10-08T00:55:51Z");

    private final UUID org = UUID.randomUUID();
    /** A job always belongs to a helper. Which helper is not this field's business — only that it is one. */
    private final UUID device = UUID.randomUUID();
    private ChannelCoverageService service;
    private Channel naver;
    private SellerAccount account;

    @BeforeEach
    void setUp() {
        service = new ChannelCoverageService(channels, capabilities, accounts, schedules, syncJobs,
                inquiries, reviews, orders, acquisitions, screenReads);
        naver = new Channel();
        naver.setCode("NAVER");
        naver.setNameKo("네이버 스마트스토어");
        naver.setStatus(ChannelStatus.AVAILABLE);
        naver.setSupportsInquiry(true);
        naver.setSupportsReview(true);
        naver.setSupportsOrder(true);
        naver.setSupportsSales(true);
        naver.setSupportsProduct(true);
        naver.setSortOrder(0);
        naver = channels.save(naver);

        account = new SellerAccount();
        account.setOrgId(org);
        account.setChannelId(naver.getId());
        account.setConnectionStatus(ChannelStatus.CONNECTED);
        account.setFileUpload(false);
        account = accounts.save(account);

        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(naver.getId());
        r.setBody("포장이 꼼꼼했어요");
        r.setRating(5);
        r.setNegative(false);
        r.setReceivedAt(LocalDate.parse("2026-09-01").atStartOfDay(ZoneOffset.UTC).toInstant());
        r.setContentHash(UUID.randomUUID().toString());
        r.setDedupKeyVersion(2);
        r.setReplyState(ReviewReplyState.UNKNOWN);
        r.setDataOrigin(DataOrigin.REAL);
        reviews.save(r);
    }

    @Test
    @DisplayName("a sign-in wall on a pressed read is today's answer, and September keeps its date")
    void anAuthRequiredScreenReadDoesNotEraseAnEarlierRead() {
        successfulRun(READ_ON_09_02);
        screenRead(AsideJobOutcome.AUTH_REQUIRED, PRESSED_TODAY);

        ChannelCoverageRow row = row("REVIEW");
        assertThat(row.lastSuccessfulSyncAt()).isEqualTo(READ_ON_09_02);
        assertThat(row.latestAttemptAt()).isEqualTo(PRESSED_TODAY);
        assertThat(row.latestAttemptOutcome()).isEqualTo(AcquisitionAttemptOutcome.AUTH_REQUIRED);
        // Both sentences can be on one screen now: 「마지막 성공 수집 9월 2일」 and 「지금: 로그인 필요」.
        assertThat(row.rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("a screen read with no connector run at all still registers as the latest attempt")
    void aScreenReadIsVisibleWithoutAnySyncRun() {
        // The live shape: the OPERATOR lane writes a scheduled_aside_job and nothing else.
        screenRead(AsideJobOutcome.AUTH_REQUIRED, PRESSED_TODAY);

        ChannelCoverageRow row = row("REVIEW");
        assertThat(row.latestAttemptAt()).isEqualTo(PRESSED_TODAY);
        assertThat(row.latestAttemptOutcome()).isEqualTo(AcquisitionAttemptOutcome.AUTH_REQUIRED);
        // And it is not mistaken for a read: nothing was observed, so nothing dates a collection.
        assertThat(row.lastSuccessfulSyncAt()).isNull();
    }

    @Test
    @DisplayName("an OBSERVED screen read is a success — but it is the job, not this field, that stores rows")
    void anObservedScreenReadIsASuccessfulAttempt() {
        screenRead(AsideJobOutcome.OBSERVED, PRESSED_TODAY);

        assertThat(row("REVIEW").latestAttemptOutcome()).isEqualTo(AcquisitionAttemptOutcome.SUCCESS);
    }

    @Test
    @DisplayName("the later of the two lanes is the latest attempt, whichever lane it is")
    void theLaterLaneWins() {
        screenRead(AsideJobOutcome.AUTH_REQUIRED, Instant.parse("2026-10-08T00:55:51Z"));
        reachingFailure(Instant.parse("2026-10-08T01:30:00Z"));

        assertThat(row("REVIEW").latestAttemptAt()).isEqualTo(Instant.parse("2026-10-08T01:30:00Z"));

        screenRead(AsideJobOutcome.OBSERVED, Instant.parse("2026-10-08T02:00:00Z"));
        assertThat(row("REVIEW").latestAttemptAt()).isEqualTo(Instant.parse("2026-10-08T02:00:00Z"));
        assertThat(row("REVIEW").latestAttemptOutcome()).isEqualTo(AcquisitionAttemptOutcome.SUCCESS);
    }

    @Test
    @DisplayName("every other screen-read ending is a failure, and none of them is a sign-in wall")
    void otherOutcomesAreFailures() {
        for (AsideJobOutcome other : new AsideJobOutcome[] {AsideJobOutcome.SURFACE_UNREADABLE,
                AsideJobOutcome.EXECUTOR_UNAVAILABLE, AsideJobOutcome.REFUSED}) {
            screenReads.deleteAll();
            screenRead(other, PRESSED_TODAY);
            assertThat(row("REVIEW").latestAttemptOutcome())
                    .as("%s is not a sign-in wall and must not send the seller to a login screen", other)
                    .isEqualTo(AcquisitionAttemptOutcome.FAILED);
        }
    }

    @Test
    @DisplayName("an unfinished read is not an attempt yet — only a settled one has an ending")
    void aQueuedReadIsNotAnAttempt() {
        ScheduledAsideJob queued = new ScheduledAsideJob();
        queued.setOrgId(org);
        queued.setSellerAccountId(account.getId());
        queued.setDeviceId(device);
        queued.setRecipe(AsideRecipe.NAVER_REVIEW_OBSERVE_V1);
        queued.setTrigger(AsideTrigger.OPERATOR);
        queued.setStatus(ScheduledAsideJobStatus.QUEUED);
        queued.setClientJobId("op-nr-pending");
        queued.setExpiresAt(PRESSED_TODAY.plusSeconds(600));
        screenReads.save(queued);

        assertThat(row("REVIEW").latestAttemptAt()).isNull();
        assertThat(row("REVIEW").latestAttemptOutcome()).isNull();
    }

    @Test
    @DisplayName("another channel's screen read never answers for this one")
    void aDifferentChannelsReadIsNotThisChannelsAttempt() {
        ScheduledAsideJob coupang = settled(AsideRecipe.COUPANG_REVIEW_OBSERVE_V1,
                AsideJobOutcome.AUTH_REQUIRED, PRESSED_TODAY);
        screenReads.save(coupang);

        assertThat(row("REVIEW").latestAttemptAt()).isNull();
    }

    @Test
    @DisplayName("a deployment with no screen-read lane answers exactly as it did before this field existed")
    void noLaneIsNotAFailedAttempt() {
        ChannelCoverageService withoutLane = new ChannelCoverageService(channels, capabilities, accounts,
                schedules, syncJobs, inquiries, reviews, orders, acquisitions, null);
        screenRead(AsideJobOutcome.AUTH_REQUIRED, PRESSED_TODAY);
        successfulRun(READ_ON_09_02);

        ChannelCoverageRow row = withoutLane.coverage(org, List.of("NAVER")).stream()
                .filter(r -> "REVIEW".equals(r.dataType())).findFirst().orElseThrow();
        assertThat(row.lastSuccessfulSyncAt()).isEqualTo(READ_ON_09_02);
        assertThat(row.latestAttemptAt()).isEqualTo(READ_ON_09_02);
    }

    private ChannelCoverageRow row(String dataType) {
        return service.coverage(org, List.of("NAVER")).stream()
                .filter(r -> dataType.equals(r.dataType())).findFirst().orElseThrow();
    }

    private void screenRead(AsideJobOutcome outcome, Instant settledAt) {
        screenReads.save(settled(AsideRecipe.NAVER_REVIEW_OBSERVE_V1, outcome, settledAt));
    }

    private ScheduledAsideJob settled(AsideRecipe recipe, AsideJobOutcome outcome, Instant settledAt) {
        ScheduledAsideJob job = new ScheduledAsideJob();
        job.setOrgId(org);
        job.setDeviceId(device);
        job.setSellerAccountId(account.getId());
        job.setRecipe(recipe);
        job.setTrigger(AsideTrigger.OPERATOR);
        job.setStatus(ScheduledAsideJobStatus.SETTLED);
        job.setOutcome(outcome);
        job.setClientJobId("op-" + recipe.jobTag() + "-" + settledAt.toEpochMilli());
        job.setClaimedAt(settledAt.minusSeconds(47));
        job.setSettledAt(settledAt);
        job.setExpiresAt(settledAt.plusSeconds(600));
        return job;
    }

    private void successfulRun(Instant finishedAt) {
        run("SUCCESS", null, finishedAt);
    }

    private void reachingFailure(Instant finishedAt) {
        run("FAILED", SyncJob.FAILURE_AUTH_REQUIRED, finishedAt);
    }

    private void run(String status, String failureCode, Instant finishedAt) {
        SyncJob j = new SyncJob();
        j.setOrgId(org);
        j.setChannelId(naver.getId());
        j.setSellerAccountId(account.getId());
        j.setDataType("REVIEW");
        j.setJobType("PULL");
        j.setTrigger("MANUAL");
        j.setStatus(status);
        j.setFailureCode(failureCode);
        j.setStartedAt(finishedAt.minusSeconds(1));
        j.setFinishedAt(finishedAt);
        j.setCreatedAt(finishedAt.minusSeconds(1));
        syncJobs.save(j);
    }
}
