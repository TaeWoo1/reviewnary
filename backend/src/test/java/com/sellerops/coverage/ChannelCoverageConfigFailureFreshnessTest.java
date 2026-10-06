package com.sellerops.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.connector.ConnectorCapabilityRepository;
import com.sellerops.coverage.dto.ChannelCoverageRow;
import com.sellerops.common.DataOrigin;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.OrderDailySummaryRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewimport.ReviewImportPlan;
import com.sellerops.reviewimport.ReviewImportPlanRepository;
import com.sellerops.reviewimport.ReviewImportSegment;
import com.sellerops.reviewimport.ReviewImportSegmentAttempt;
import com.sellerops.reviewimport.ReviewImportSegmentAttemptRepository;
import com.sellerops.reviewimport.ReviewImportSegmentRepository;
import com.sellerops.reviewimport.SegmentAttemptResult;
import com.sellerops.reviewimport.SegmentCoverageState;
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
 * <b>A run that never asked the channel cannot answer for it.</b>
 *
 * <p>The failure this pins, measured on 2026-10-07: a backend started without connector configuration
 * answered six manual syncs with {@code FAILED / CONNECTOR_UNAVAILABLE} in 0.0s each — no socket, no
 * marketplace, connection health deliberately untouched. Coverage still read the single latest run,
 * found it failed, and dropped four real 09-26 collection times to 「확인된 적 없음」. The product forgot
 * it had ever read NAVER 문의, CAFE24 리뷰·문의 and COUPANG 리뷰 because of runs that never left the
 * process.
 *
 * <p>The other half of the rule is tested here too, because it is the half that is easy to lose while
 * fixing the first: a run that DID reach the channel and failed still answers for it. A dead credential
 * must not read as fresh just because a success exists further back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ChannelCoverageConfigFailureFreshnessTest {

    @Autowired ChannelRepository channels;
    @Autowired ConnectorCapabilityRepository capabilities;
    @Autowired SellerAccountRepository accounts;
    @Autowired SyncScheduleRepository schedules;
    @Autowired SyncJobRepository syncJobs;
    @Autowired InquiryRepository inquiries;
    @Autowired ReviewRepository reviews;
    @Autowired OrderDailySummaryRepository orders;
    @Autowired ReviewImportSegmentAttemptRepository acquisitions;
    @Autowired ReviewImportPlanRepository plans;
    @Autowired ReviewImportSegmentRepository segments;

    private static final Instant COLLECTED = Instant.parse("2026-09-26T18:31:17Z");

    private final UUID org = UUID.randomUUID();
    private ChannelCoverageService service;
    private Channel naver;
    private SellerAccount account;

    @BeforeEach
    void setUp() {
        service = new ChannelCoverageService(channels, capabilities, accounts, schedules, syncJobs,
                inquiries, reviews, orders, acquisitions);
        naver = new Channel();
        naver.setCode("NAVER");
        naver.setNameKo("네이버");
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
        r.setBody("배송이 빨라요");
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
    @DisplayName("a collection, then a config failure: the collection is still when the channel was last read")
    void configFailureDoesNotEraseACollection() {
        run("INQUIRY", "SUCCESS", null, COLLECTED);
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, Instant.parse("2026-10-07T02:43:57Z"));

        assertThat(lastSuccess("INQUIRY")).isEqualTo(COLLECTED);
    }

    @Test
    @DisplayName("a PARTIAL collection counts, and a later config failure does not erase it either")
    void partialSurvivesAConfigFailure() {
        run("INQUIRY", "SUCCESS", null, Instant.parse("2026-09-20T00:00:00Z"));
        run("INQUIRY", "PARTIAL", "EXECUTION_FAILED", COLLECTED);
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, Instant.parse("2026-10-07T02:43:57Z"));

        // The PARTIAL reached the channel and brought rows back — it is the later word, not the SUCCESS
        // behind it, and the config failure in front of it is not a word at all.
        assertThat(lastSuccess("INQUIRY")).isEqualTo(COLLECTED);
    }

    @Test
    @DisplayName("config failures and nothing else: the channel has never been read, and says so")
    void configFailuresAloneProveNothing() {
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, Instant.parse("2026-10-07T02:43:57Z"));
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, Instant.parse("2026-10-07T02:44:10Z"));

        assertThat(lastSuccess("INQUIRY")).isNull();
    }

    @Test
    @DisplayName("a live-approval refusal is not a collection either: the 09-23 success is still the last read")
    void approvalGateFailureDoesNotEraseACollection() {
        run("INQUIRY", "SUCCESS", null, COLLECTED);
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONFIGURATION_REQUIRED, Instant.parse("2026-10-07T02:43:57Z"));

        // The interlock throws as the first statement of the signed GET — no signature, no socket. Measured
        // on the live org: Coupang 문의 had collected on 09-23 and coverage read 「확인된 적 없음」 anyway.
        assertThat(lastSuccess("INQUIRY")).isEqualTo(COLLECTED);
    }

    @Test
    @DisplayName("approval refusals and nothing else: no success is synthesised out of them")
    void approvalGateFailuresAloneProveNothing() {
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONFIGURATION_REQUIRED, Instant.parse("2026-10-07T02:43:57Z"));
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONFIGURATION_REQUIRED, Instant.parse("2026-10-07T02:44:10Z"));

        assertThat(lastSuccess("INQUIRY")).isNull();
    }

    @Test
    @DisplayName("the two gates are transparent together, and an older collection survives both")
    void bothPreAttemptGatesAreTransparent() {
        run("INQUIRY", "SUCCESS", null, COLLECTED);
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, Instant.parse("2026-10-07T02:43:57Z"));
        run("INQUIRY", "FAILED", SyncJob.FAILURE_CONFIGURATION_REQUIRED, Instant.parse("2026-10-07T02:44:10Z"));

        assertThat(lastSuccess("INQUIRY")).isEqualTo(COLLECTED);
    }

    @Test
    @DisplayName("every failure that reached the marketplace still erases — the set did not widen")
    void failuresThatReachedTheChannelStillSpeak() {
        for (String reached : new String[] {"AUTH_REQUIRED", "RATE_LIMITED", "TIMEOUT", "EXECUTION_FAILED",
                "PAGE_LIMIT_REACHED"}) {
            syncJobs.deleteAll();
            run("INQUIRY", "SUCCESS", null, COLLECTED);
            run("INQUIRY", "FAILED", reached, Instant.parse("2026-10-07T02:43:57Z"));

            assertThat(lastSuccess("INQUIRY")).as("%s reached the channel and is its latest word", reached)
                    .isNull();
        }
    }

    @Test
    @DisplayName("a failure that DID reach the channel still stands in front of an older success")
    void aRealFailureIsStillEvidence() {
        run("INQUIRY", "SUCCESS", null, COLLECTED);
        run("INQUIRY", "FAILED", "AUTH_REQUIRED", Instant.parse("2026-10-07T02:43:57Z"));

        // The credential died. A channel that has stopped answering must not read as freshly collected,
        // which is the rule the config-failure skip is deliberately narrow enough to preserve.
        assertThat(lastSuccess("INQUIRY")).isNull();
    }

    @Test
    @DisplayName("a pre-V116 failure carries no code and is never skipped — history keeps its meaning")
    void anUncodedFailureIsNotSkipped() {
        run("INQUIRY", "SUCCESS", null, COLLECTED);
        run("INQUIRY", "FAILED", null, Instant.parse("2026-10-07T02:43:57Z"));

        assertThat(lastSuccess("INQUIRY")).isNull();
    }

    @Test
    @DisplayName("the guided-acquisition fallback is untouched: an export still dates REVIEW through a config failure")
    void guidedAcquisitionStillAnswersForReview() {
        Instant exported = Instant.parse("2026-09-02T04:30:00Z");
        acquisition(SegmentAttemptResult.SUCCEEDED, exported);
        run("REVIEW", "FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, Instant.parse("2026-10-07T02:43:56Z"));

        // NAVER publishes no seller review API; the export IS the acquisition path, and a config failure
        // on a connector that was never going to serve REVIEW cannot speak over it.
        assertThat(lastSuccess("REVIEW")).isEqualTo(exported);
    }

    @Test
    @DisplayName("run and export are read together: the later of the two is the channel's last observation")
    void theLaterOfRunAndExportWins() {
        acquisition(SegmentAttemptResult.SUCCEEDED, Instant.parse("2026-09-02T04:30:00Z"));
        run("REVIEW", "SUCCESS", null, COLLECTED);
        run("REVIEW", "FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, Instant.parse("2026-10-07T02:43:56Z"));

        assertThat(lastSuccess("REVIEW")).isEqualTo(COLLECTED);
    }

    private Instant lastSuccess(String dataType) {
        List<ChannelCoverageRow> rows = service.coverage(org, List.of("NAVER"));
        return rows.stream().filter(r -> dataType.equals(r.dataType())).findFirst().orElseThrow()
                .lastSuccessfulSyncAt();
    }

    /** One finished run. {@code createdAt} is pinned so "newest" is the test's statement, not the clock's. */
    private SyncJob run(String dataType, String status, String failureCode, Instant finishedAt) {
        SyncJob j = new SyncJob();
        j.setOrgId(org);
        j.setChannelId(naver.getId());
        j.setSellerAccountId(account.getId());
        j.setDataType(dataType);
        j.setJobType("PULL");
        j.setTrigger("MANUAL");
        j.setStatus(status);
        j.setFailureCode(failureCode);
        j.setStartedAt(finishedAt.minusSeconds(1));
        j.setFinishedAt(finishedAt);
        j.setCreatedAt(finishedAt.minusSeconds(1));
        return syncJobs.save(j);
    }

    private void acquisition(SegmentAttemptResult result, Instant finishedAt) {
        ReviewImportPlan plan = new ReviewImportPlan();
        plan.setOrgId(org);
        plan.setSellerAccountId(account.getId());
        plan.setChannelId(naver.getId());
        plan.setRequestedStart(LocalDate.parse("2026-09-01"));
        plan.setRequestedEnd(LocalDate.parse("2026-09-02"));
        plan = plans.save(plan);

        ReviewImportSegment segment = new ReviewImportSegment();
        segment.setPlanId(plan.getId());
        segment.setOrgId(org);
        segment.setOrdinal(0);
        segment.setSegmentStart(LocalDate.parse("2026-09-01"));
        segment.setSegmentEnd(LocalDate.parse("2026-09-02"));
        segment.setCoverageState(SegmentCoverageState.COVERED);
        segment = segments.save(segment);

        ReviewImportSegmentAttempt a = new ReviewImportSegmentAttempt();
        a.setOrgId(org);
        a.setSegmentId(segment.getId());
        a.setAttemptNo(1);
        a.setResult(result);
        a.setStartedAt(finishedAt.minusSeconds(60));
        a.setFinishedAt(finishedAt);
        acquisitions.save(a);
    }
}
