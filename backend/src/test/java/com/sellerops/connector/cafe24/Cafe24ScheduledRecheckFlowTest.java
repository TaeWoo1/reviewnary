package com.sellerops.connector.cafe24;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.collect.ScheduledCollectionFollowUp;
import com.sellerops.collect.SyncRunExecutor;
import com.sellerops.collect.SyncScheduleClaimer;
import com.sellerops.collect.SyncScheduleRunner;
import com.sellerops.community.Cafe24CommunityArticleRepository;
import com.sellerops.connector.ChannelConnectionStatusRepository;
import com.sellerops.connector.ConnectorAlertRepository;
import com.sellerops.connector.ConnectorRegistry;
import com.sellerops.connector.DataType;
import com.sellerops.connector.cafe24.FakeCafe24Board.Kind;
import com.sellerops.credential.ConnectorCredentialRepository;
import com.sellerops.credential.CredentialVault;
import com.sellerops.ingest.IngestionService;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.publish.InquiryExecutionRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckAccountWaitRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckDailyUsageRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckLedger;
import com.sellerops.inquiry.recheck.InquiryRecheckRunLockRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemWriter;
import com.sellerops.order.OrderDailySummaryRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductService;
import com.sellerops.review.ReviewRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.sync.SyncCursorRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import com.sellerops.sync.SyncSchedule;
import com.sellerops.sync.SyncScheduleRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * <b>The whole scheduled path, offline</b> (2026-11-08): the real scheduler claims a Cafe24 INQUIRY schedule, the real
 * executor runs the real Cafe24 connector against the fake mall, and — only when the run finished cleanly — the follow-up
 * runs the recheck over the same account. Fake mall, fake clock for the connector and the recheck; no network, no model.
 *
 * <p>The scheduler's own time is the instant handed to {@code runDueSchedules}, kept equal to the fake clock here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class Cafe24ScheduledRecheckFlowTest {

    /** 2026-10-01 10:00 KST: the first scheduled collection, while the inquiry is new. */
    static final Instant FIRST = Instant.parse("2026-10-01T01:00:00Z");
    /** 2026-11-08 10:00 KST: five weeks later. */
    static final Instant LATER = Instant.parse("2026-11-08T01:00:00Z");
    static final String OLD = "2026-09-30T10:00:00+09:00";
    static final String LATE_ANSWER = "2026-11-07T15:00:00+09:00";

    @Autowired SellerAccountRepository sellerAccounts;
    @Autowired ChannelRepository channels;
    @Autowired ReviewRepository reviews;
    @Autowired InquiryRepository inquiries;
    @Autowired OrderDailySummaryRepository orders;
    @Autowired ProductRepository products;
    @Autowired Cafe24CommunityArticleRepository communityArticles;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquiryWorkItemAuditRepository audits;
    @Autowired InquiryExecutionRepository executions;
    @Autowired PlatformTransactionManager txManager;
    @Autowired com.sellerops.order.ChannelOrderRepository channelOrders;
    @Autowired com.sellerops.order.ChannelOrderStatusEventRepository channelOrderStatusEvents;
    @Autowired SyncJobRepository syncJobs;
    @Autowired SyncCursorRepository cursors;
    @Autowired SyncScheduleRepository schedules;
    @Autowired ChannelConnectionStatusRepository connectionStatus;
    @Autowired ConnectorAlertRepository alerts;
    @Autowired ConnectorCredentialRepository credentials;
    @Autowired InquiryRecheckDailyUsageRepository dailyUsage;
    @Autowired InquiryRecheckRunLockRepository runLocks;
    @Autowired InquiryRecheckAccountWaitRepository accountWaits;

    final FakeCafe24Board mall = new FakeCafe24Board("samplemall");
    final Cafe24AnswerRecheckTest.SettableClock clock = new Cafe24AnswerRecheckTest.SettableClock(FIRST);
    final UUID org = UUID.randomUUID();

    SellerAccount account;
    IngestionService ingestion;
    Cafe24Authorizer authorizer;
    SyncRunExecutor executor;

    @BeforeEach
    void setUp() {
        Channel ch = new Channel();
        ch.setCode(Cafe24ApiConnector.CHANNEL_CODE);
        ch.setNameKo("카페24");
        ch.setStatus(ChannelStatus.AVAILABLE);
        ch.setSupportsReview(true);
        ch.setSupportsInquiry(true);
        ch.setSupportsOrder(true);
        ch.setSupportsSales(true);
        ch.setSupportsProduct(true);
        ch.setSortOrder(0);
        channels.save(ch);
        SellerAccount acc = new SellerAccount();
        acc.setOrgId(org);
        acc.setChannelId(ch.getId());
        acc.setConnectionStatus(ChannelStatus.CONNECTED);
        acc.setFileUpload(false);
        account = sellerAccounts.save(acc);

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        CredentialVault vault = new CredentialVault(credentials, new ObjectMapper(),
                Base64.getEncoder().encodeToString(key), "local-test-1");
        vault.store(org, account.getId(), "API", "OAUTH2",
                Map.of("mall_id", "samplemall", "refresh_token", "old-refresh-token"), null, null, null);

        ingestion = new IngestionService(reviews, inquiries, orders, new ProductService(products), communityArticles,
                channels, new InquiryWorkItemWriter(inquiries, workItems, audits, txManager));
        // One authorizer for the sweep and the recheck, as the configuration wires it.
        authorizer = new Cafe24Authorizer(new Cafe24TokenClient(mall), vault, "app-client-id", "app-client-secret");
        Cafe24ApiConnector connector = new Cafe24ApiConnector(authorizer, new Cafe24OrdersClient(mall),
                new Cafe24BoardArticlesClient(mall), null,
                new Cafe24InquiryAnswerObserver(new Cafe24BoardArticlesClient(mall), new Cafe24BoardCommentsClient(mall)),
                clock);
        executor = new SyncRunExecutor(sellerAccounts, channels, new ConnectorRegistry(List.of(connector)), ingestion,
                new com.sellerops.order.ChannelOrderIngestionService(channelOrders, channelOrderStatusEvents, channels,
                        txManager),
                syncJobs, cursors, connectionStatus);

        SyncSchedule s = new SyncSchedule();
        s.setOrgId(org);
        s.setSellerAccountId(account.getId());
        s.setDataType(DataType.INQUIRY.name());
        s.setCadenceKind("INTERVAL");
        s.setIntervalMinutes(60);
        s.setEnabled(true);
        s.setNextRunAt(FIRST);
        schedules.save(s);
    }

    @Test
    void aScheduledCollectionThatEndsCleanlyIsFollowedByTheRecheckAndOnlyThen() {
        mall.post(1801L, OLD);
        mall.post(1802L, OLD);
        List<Kind> sent = new ArrayList<>();
        mall.beforeEachRequest = sent::add;

        // The first scheduled collection: both are new, read by the sweep; nothing is old enough to recheck.
        assertThat(tick(FIRST, true)).extracting(SyncJob::getStatus).containsExactly("SUCCESS");
        assertThat(sent).doesNotContain(Kind.BY_NUMBER);

        // Five weeks later the seller answers 1801 on the seller centre.
        mall.shopComments(1801L, LATE_ANSWER);

        // With the recheck off (no follow-up bean), the scheduled sweep alone never closes it.
        sent.clear();
        assertThat(tick(LATER, false)).extracting(SyncJob::getStatus).containsExactly("SUCCESS");
        assertThat(sent).as("no recheck request").doesNotContain(Kind.BY_NUMBER);
        assertThat(stored(1801L).getStatus()).isEqualTo("UNANSWERED");
        assertThat(phaseOf(1801L)).isEqualTo(InquiryWorkItemPhase.OPEN);

        // With it on: the next scheduled collection, then the recheck — after the sweep's own requests.
        sent.clear();
        assertThat(tick(LATER.plus(Duration.ofHours(1)), true)).extracting(SyncJob::getStatus)
                .containsExactly("SUCCESS");
        assertThat(sent).contains(Kind.LIST, Kind.BY_NUMBER);
        assertThat(sent.lastIndexOf(Kind.LIST)).as("the sweep first, the recheck after it")
                .isLessThan(sent.indexOf(Kind.BY_NUMBER));
        assertThat(stored(1801L).getStatus()).isEqualTo("ANSWERED");
        assertThat(phaseOf(1801L)).isEqualTo(InquiryWorkItemPhase.COMPLETED);
        assertThat(stored(1802L).getCommentCheckedAt()).isEqualTo(LATER.plus(Duration.ofHours(1)));
        int used = ledger().usedOn(account.getId(), java.time.LocalDate.of(2026, 11, 8));
        assertThat(used).isPositive();

        // The next scheduled collection the same day: 1802 was looked at today — nothing more is asked.
        sent.clear();
        tick(LATER.plus(Duration.ofHours(2)), true);
        assertThat(sent).as("the sweep ran; the recheck asked nothing").contains(Kind.LIST)
                .doesNotContain(Kind.BY_NUMBER);
        assertThat(ledger().usedOn(account.getId(), java.time.LocalDate.of(2026, 11, 8))).isEqualTo(used);

        // A collection that does not end cleanly is not followed by a recheck — the next day, with 1802 due again.
        sent.clear();
        mall.failNext(Kind.LIST, 503);
        List<SyncJob> failed = tick(LATER.plus(Duration.ofDays(1)), true);
        assertThat(failed).extracting(SyncJob::getStatus).doesNotContain("SUCCESS");
        assertThat(sent).as("no recheck after a failed collection").doesNotContain(Kind.BY_NUMBER);

        // The account's next clean collection brings it back.
        sent.clear();
        Instant next = schedules.findAll().get(0).getNextRunAt();
        tick(next, true);
        assertThat(sent).contains(Kind.BY_NUMBER);
        assertThat(stored(1802L).getCommentCheckedAt()).isEqualTo(next);
    }

    /**
     * 2026-11-09: the scheduled collection ends {@code SUCCESS} by its articles while its comment look was cut off by the
     * account or the channel — 401, 429, 503, at the comment read and at the discovery. Its articles and what it learned
     * are kept; the recheck does not follow it. One article's own trouble does not hold the recheck back.
     */
    @Test
    void aScheduledCollectionWhoseCommentLookTheAccountCutOffIsNotFollowedByTheRecheck() {
        mall.post(1801L, OLD);
        List<Kind> sent = new ArrayList<>();
        mall.beforeEachRequest = sent::add;
        assertThat(tick(FIRST, true)).extracting(SyncJob::getStatus).containsExactly("SUCCESS");
        mall.shopComments(1801L, LATE_ANSWER); // answered late on the seller centre: only the recheck can see it
        mall.post(1901L, "2026-11-07T09:00:00+09:00"); // new, in the routine window, with a comment to read
        mall.customerComments(1901L, LATE_ANSWER);

        Instant at = LATER;
        record Cut(Kind kind, int status) {
        }
        for (Cut cut : List.of(new Cut(Kind.COMMENTS, 401), new Cut(Kind.COMMENTS, 429), new Cut(Kind.COMMENTS, 503),
                new Cut(Kind.DISCOVERY, 401), new Cut(Kind.DISCOVERY, 429), new Cut(Kind.DISCOVERY, 503))) {
            sent.clear();
            mall.failNext(cut.kind(), cut.status());
            List<SyncJob> jobs = tick(at, true);
            assertThat(jobs).extracting(SyncJob::getStatus).as("%s", cut).containsExactly("SUCCESS");
            assertThat(jobs.get(0).getAccountSideStop()).as("%s", cut).isNotNull();
            assertThat(sent).as("%s: the sweep and its comment look ran", cut).contains(Kind.LIST, cut.kind());
            assertThat(sent).as("%s: no recheck request", cut).doesNotContain(Kind.BY_NUMBER);
            assertThat(stored(1901L).getStatus()).as("the new inquiry is collected").isEqualTo("UNANSWERED");
            assertThat(stored(1901L).getCommentCheck())
                    .isEqualTo(com.sellerops.ingest.canonical.CommentAnswerCheck.Result.NOT_CHECKED);
            assertThat(stored(1801L).getStatus()).isEqualTo("UNANSWERED");
            assertThat(ledger().usedOn(account.getId(), java.time.LocalDate.of(2026, 11, 8)))
                    .as("%s: nothing spent from the recheck's day", cut).isZero();
            at = schedules.findAll().get(0).getNextRunAt();
        }

        // One article's own trouble — its comment read answers 500 — is not the account's: the recheck follows.
        sent.clear();
        mall.brokenComments.add(1901L);
        List<SyncJob> jobs = tick(at, true);
        assertThat(jobs).extracting(SyncJob::getStatus).containsExactly("SUCCESS");
        assertThat(jobs.get(0).getAccountSideStop()).isNull();
        assertThat(sent).contains(Kind.BY_NUMBER);
        assertThat(sent.lastIndexOf(Kind.LIST)).isLessThan(sent.indexOf(Kind.BY_NUMBER));
        assertThat(stored(1801L).getStatus()).isEqualTo("ANSWERED");
        assertThat(phaseOf(1801L)).isEqualTo(InquiryWorkItemPhase.COMPLETED);
    }

    /** One scheduler tick at {@code at}; with the recheck follow-up when {@code recheckOn}. */
    List<SyncJob> tick(Instant at, boolean recheckOn) {
        clock.set(at);
        List<ScheduledCollectionFollowUp> followUps = recheckOn
                ? List.of(new Cafe24AnswerRecheckAfterCollection(runner()))
                : List.of();
        SyncScheduleRunner scheduler = new SyncScheduleRunner(new SyncScheduleClaimer(schedules), executor, schedules,
                syncJobs, connectionStatus, alerts, null, followUps);
        return scheduler.runDueSchedules(at, 20);
    }

    Cafe24AnswerRecheckRunner runner() {
        return new Cafe24AnswerRecheckRunner(mall, authorizer, inquiries, workItems, executions, ingestion, ledger(),
                Cafe24RecheckPolicy.PROVISIONAL, clock);
    }

    InquiryRecheckLedger ledger() {
        return new InquiryRecheckLedger(dailyUsage, runLocks, accountWaits, clock);
    }

    Inquiry stored(long no) {
        return inquiries.findByOrgIdAndChannelIdAndExternalId(org, account.getChannelId(),
                Cafe24InquiryArticleMapper.externalId(6, no)).orElseThrow();
    }

    InquiryWorkItemPhase phaseOf(long no) {
        return workItems.findByInquiryId(stored(no).getId()).map(InquiryWorkItem::getPhase).orElse(null);
    }
}
