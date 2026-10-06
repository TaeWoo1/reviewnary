package com.sellerops.review.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.attention.reply.ReviewReplyApprovalRepository;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.attention.reply.ReviewReplyWorkLookup;
import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.itemanalysis.ItemAnalysisRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.channel.dto.ChannelReviewPageView;
import com.sellerops.review.triage.feedback.AiTriageCurrentRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>What the record dates its last import by.</b>
 *
 * <p>{@code lastImportAt} is not a diagnostic — it becomes a sentence the seller reads. When an import
 * did not finish clean the record says 「마지막 수집(…)이 목록 끝까지 확인되지 않은 상태로 끝났습니다. 채널에
 * 이보다 더 있을 수 있습니다.」, and {@code newCount} counts from that same run's start.
 *
 * <p>The failure this pins, measured on 2026-10-07: six manual syncs on a backend with no connector
 * configured put that sentence under all three channels at once, naming 02:43 — a minute in which
 * nothing was collected because nothing was attempted. The seller was told an acquisition had stopped
 * early that had never started.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReviewImportFreshnessTest {

    @Autowired ReviewRepository reviews;
    @Autowired ProductRepository products;
    @Autowired SellerAccountRepository accounts;
    @Autowired ChannelRepository channels;
    @Autowired SyncJobRepository syncJobs;
    @Autowired ItemAnalysisRepository analyses;
    @Autowired AiTriageCurrentRepository aiCurrent;
    @Autowired com.sellerops.review.triage.feedback.TriageCorrectionRepository triageCorrections;
    @Autowired com.sellerops.review.triage.feedback.TriageCorrectionAuditRepository triageCorrectionAudit;
    @Autowired ReviewTriageRepository triages;
    @Autowired ReviewReplyDraftRepository drafts;
    @Autowired ReviewReplyApprovalRepository approvals;

    private static final Instant IMPORTED = Instant.parse("2026-09-26T18:31:19Z");
    private static final Instant CONFIG_FAILURE = Instant.parse("2026-10-07T02:43:57Z");

    private ChannelReviewService service;
    private final UUID org = UUID.randomUUID();
    private Channel naver;
    private SellerAccount account;

    @BeforeEach
    void setUp() {
        service = new ChannelReviewService(reviews, products, accounts, syncJobs, analyses, aiCurrent,
                ChannelReviewTriageIT.pilotOff(), channels,
                new ReviewReplyWorkLookup(triages, drafts, approvals), triageCorrections, triageCorrectionAudit);
        naver = channels.findByCode("NAVER").orElseGet(() -> {
            Channel ch = new Channel();
            ch.setCode("NAVER");
            ch.setNameKo("네이버 스마트스토어");
            ch.setStatus(ChannelStatus.AVAILABLE);
            ch.setSupportsInquiry(true);
            ch.setSupportsReview(true);
            ch.setSupportsOrder(true);
            ch.setSupportsSales(true);
            ch.setSupportsProduct(true);
            ch.setSortOrder(0);
            return channels.save(ch);
        });
        account = new SellerAccount();
        account.setOrgId(org);
        account.setChannelId(naver.getId());
        account.setConnectionStatus(ChannelStatus.CONNECTED);
        account.setFileUpload(false);
        account = accounts.save(account);

        review(LocalDate.of(2026, 9, 10));
    }

    @Test
    @DisplayName("a config failure is not an import: the real one still dates the record")
    void configFailureDoesNotDateTheRecord() {
        run("SUCCESS", null, IMPORTED);
        run("FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, CONFIG_FAILURE);

        ChannelReviewPageView page = page();
        assertThat(page.lastImportAt()).isEqualTo(IMPORTED);
        assertThat(page.lastImportComplete()).isTrue();
    }

    @Test
    @DisplayName("config failures and nothing else: the record claims no import at all, so no sentence is drawn")
    void configFailuresAloneDateNothing() {
        run("FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, CONFIG_FAILURE);
        run("FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, CONFIG_FAILURE.plusSeconds(13));

        ChannelReviewPageView page = page();
        // The screen draws 「마지막 수집(…)이 목록 끝까지 확인되지 않은 상태로…」 only when lastImportAt is set,
        // so a null here is exactly the absence of the false sentence.
        assertThat(page.lastImportAt()).isNull();
        assertThat(page.lastImportComplete()).isFalse();
        assertThat(page.newCount()).isZero();
    }

    @Test
    @DisplayName("a live-approval refusal is not an import: it neither dates the record nor moves the line")
    void approvalGateFailureDoesNotDateTheRecord() {
        run("SUCCESS", null, IMPORTED);
        run("FAILED", SyncJob.FAILURE_CONFIGURATION_REQUIRED, CONFIG_FAILURE);

        ChannelReviewPageView page = page();
        assertThat(page.lastImportAt()).isEqualTo(IMPORTED);
        assertThat(page.lastImportComplete()).isTrue();
    }

    @Test
    @DisplayName("approval refusals and nothing else: the record claims no import, so no sentence is drawn")
    void approvalGateFailuresAloneDateNothing() {
        run("FAILED", SyncJob.FAILURE_CONFIGURATION_REQUIRED, CONFIG_FAILURE);

        ChannelReviewPageView page = page();
        assertThat(page.lastImportAt()).isNull();
        assertThat(page.lastImportComplete()).isFalse();
    }

    @Test
    @DisplayName("an import that really did stop early still warns — that contract is untouched")
    void aRealIncompleteImportStillWarns() {
        run("SUCCESS", null, Instant.parse("2026-09-20T00:00:00Z"));
        run("PARTIAL", "EXECUTION_FAILED", IMPORTED);

        ChannelReviewPageView page = page();
        assertThat(page.lastImportAt()).isEqualTo(IMPORTED);
        assertThat(page.lastImportComplete()).isFalse();
    }

    @Test
    @DisplayName("a failure that reached the channel still dates the record — only the config gate is transparent")
    void aRealFailureStillDatesTheRecord() {
        run("SUCCESS", null, Instant.parse("2026-09-20T00:00:00Z"));
        run("FAILED", "AUTH_REQUIRED", IMPORTED);

        ChannelReviewPageView page = page();
        assertThat(page.lastImportAt()).isEqualTo(IMPORTED);
        assertThat(page.lastImportComplete()).isFalse();
    }

    @Test
    @DisplayName("「새로 들어온 것」 counts from the real import, not from a run that collected nothing")
    void newCountIsMeasuredFromTheRealImport() {
        run("SUCCESS", null, IMPORTED);
        // Arrived after that import, so it is new; the config failure that follows must not move the line.
        review(LocalDate.of(2026, 9, 28), IMPORTED.plusSeconds(3600));
        run("FAILED", SyncJob.FAILURE_CONNECTOR_UNAVAILABLE, CONFIG_FAILURE);

        assertThat(page().newCount()).isEqualTo(1);
    }

    private ChannelReviewPageView page() {
        return service.list(org, account.getId(), null, null, 0, 20);
    }

    /** One finished run. {@code createdAt} is pinned so "newest" is the test's statement, not the clock's. */
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

    private void review(LocalDate writtenOn) {
        review(writtenOn, Instant.parse("2026-09-10T00:00:00Z"));
    }

    private void review(LocalDate writtenOn, Instant createdAt) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(naver.getId());
        r.setBody("접착이 약해요");
        r.setRating(2);
        r.setNegative(true);
        r.setReceivedAt(writtenOn.atStartOfDay(ZoneOffset.UTC).toInstant());
        r.setContentHash(UUID.randomUUID().toString());
        r.setDedupKeyVersion(2);
        r.setReplyState(ReviewReplyState.UNKNOWN);
        r.setMediaCount(0);
        r.setCreatedAt(createdAt);
        reviews.save(r);
    }
}
