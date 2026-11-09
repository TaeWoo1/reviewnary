package com.sellerops.connector.cafe24;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.community.Cafe24CommunityArticleRepository;
import com.sellerops.connector.DataType;
import com.sellerops.connector.FetchPage;
import com.sellerops.connector.FetchRequest;
import com.sellerops.credential.ConnectorCredentialRepository;
import com.sellerops.credential.CredentialVault;
import com.sellerops.ingest.IngestionService;
import com.sellerops.ingest.canonical.CanonicalInquiry;
import com.sellerops.ingest.canonical.CommentAnswerCheck.Result;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemWriter;
import com.sellerops.order.OrderDailySummaryRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductService;
import com.sellerops.review.ReviewRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The comment lane's three outcomes, from synthetic HTTP through the connector and the mapper into the stored inquiry
 * (2026-11-08, inquiry_operational_truth_v1 §10.4). H2 here; the V184 columns and constraints are checked separately on
 * a disposable PostgreSQL.
 *
 * <p>What each test pins: an unchanged re-read still records the look; a failed or capped look is {@code NOT_CHECKED}
 * and never moves the success time; a collection that did not look writes nothing; only a proven shop answer reaches
 * the work item; a thread reply, an already-answered article, another channel and another org never get the record.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class Cafe24CommentCheckIngestTest {
    // Not final: Cafe24CommentCheckPostgresProofIT runs every case below again on real Flyway over PostgreSQL.

    private static final LocalDate START = LocalDate.parse("2026-11-01");
    private static final LocalDate END = LocalDate.parse("2026-11-08");
    private static final String CREATED = "2026-11-05T10:00:00+09:00";

    @Autowired ReviewRepository reviews;
    @Autowired InquiryRepository inquiries;
    @Autowired OrderDailySummaryRepository orders;
    @Autowired ProductRepository products;
    @Autowired Cafe24CommunityArticleRepository communityArticles;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquiryWorkItemAuditRepository audits;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ChannelRepository channels;
    @Autowired ConnectorCredentialRepository credentials;

    // Protected so the PostgreSQL proof can give them the parent rows its foreign keys demand.
    protected final UUID org = UUID.randomUUID();
    protected final UUID account = UUID.randomUUID();
    protected final UUID otherOrg = UUID.randomUUID();
    protected final UUID otherAccount = UUID.randomUUID();
    protected final UUID channel = UUID.randomUUID();
    protected final UUID naverChannel = UUID.randomUUID();
    private final FakeCafe24HttpClient http = new FakeCafe24HttpClient();
    private final MovingClock clock = new MovingClock(Instant.parse("2026-11-08T01:00:00Z"));

    private IngestionService ingestion;
    private Cafe24ApiConnector withLane;
    private Cafe24ApiConnector withoutLane;

    /** Parent rows for the ids above. H2's entity-built schema has no foreign keys, so nothing to do here. */
    protected void prepareTenants() {
    }

    @BeforeEach
    void setUp() {
        prepareTenants();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        CredentialVault vault = new CredentialVault(credentials, new ObjectMapper(),
                Base64.getEncoder().encodeToString(key), "local-test-1");
        vault.store(org, account, "API", "OAUTH2",
                Map.of("mall_id", "samplemall", "refresh_token", "old-refresh-token"), null, null, null);
        vault.store(otherOrg, otherAccount, "API", "OAUTH2",
                Map.of("mall_id", "othermall", "refresh_token", "old-refresh-token"), null, null, null);
        ingestion = new IngestionService(reviews, inquiries, orders, new ProductService(products),
                communityArticles, channels, new InquiryWorkItemWriter(inquiries, workItems, audits, txManager));
        Cafe24Authorizer authorizer =
                new Cafe24Authorizer(new Cafe24TokenClient(http), vault, "app-client-id", "app-client-secret");
        withLane = new Cafe24ApiConnector(authorizer, new Cafe24OrdersClient(http), new Cafe24BoardArticlesClient(http),
                null, new Cafe24InquiryAnswerObserver(new Cafe24BoardArticlesClient(http),
                        new Cafe24BoardCommentsClient(http)), clock);
        withoutLane = new Cafe24ApiConnector(authorizer, new Cafe24OrdersClient(http),
                new Cafe24BoardArticlesClient(http), clock);
    }

    // ---- outcomes -----------------------------------------------------------------------------------------------

    @Test
    void noShopCommentIsRecordedWithBothTimesAndLeavesTheTaskOpen() {
        sweep(org, account, withLane, page(article(501L, "N")), noCommentsAnywhere());

        Inquiry q = stored(org, 501L);
        assertThat(q.getStatus()).isEqualTo("UNANSWERED");
        assertThat(q.getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        assertThat(q.getCommentCheckAttemptedAt()).isEqualTo(clock.instant());
        assertThat(q.getCommentCheckedAt()).isEqualTo(clock.instant());
        assertThat(phaseOf(q)).isEqualTo(InquiryWorkItemPhase.OPEN);
    }

    @Test
    void aProvenShopCommentAnswersThroughTheExistingPathAndRecordsTheLook() {
        sweep(org, account, withLane, page(article(502L, "N")), commented(502L), shopComment(502L, "samplemall"));

        Inquiry q = stored(org, 502L);
        assertThat(q.getStatus()).isEqualTo("ANSWERED");
        assertThat(q.getAnsweredAt()).isEqualTo(Instant.parse("2026-11-06T05:56:24Z"));
        assertThat(q.getCommentCheck()).isEqualTo(Result.SHOP_ANSWERED);
        assertThat(q.getCommentCheckedAt()).isEqualTo(clock.instant());
        assertThat(q.getAnswerBody()).as("the fact and its time, never the shop's words").isNull();
        assertThat(phaseOf(q)).isNull();   // stored as history: an answered inquiry opens no task
    }

    @Test
    void aShopAnswerOnAnOpenInquiryCompletesItsTask() {
        sweep(org, account, withLane, page(article(503L, "N")), noCommentsAnywhere());
        assertThat(phaseOf(stored(org, 503L))).isEqualTo(InquiryWorkItemPhase.OPEN);

        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(503L, "N")), commented(503L), shopComment(503L, "samplemall"));

        Inquiry q = stored(org, 503L);
        assertThat(q.getCommentCheck()).isEqualTo(Result.SHOP_ANSWERED);
        assertThat(phaseOf(q)).isEqualTo(InquiryWorkItemPhase.COMPLETED);
    }

    // ---- unchanged re-read, failures, caps ----------------------------------------------------------------------

    @Test
    void anUnchangedReReadStillMovesTheResultAndItsTimes() {
        sweep(org, account, withLane, page(article(510L, "N")), noCommentsAnywhere());
        Instant first = clock.instant();

        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(510L, "N")), noCommentsAnywhere());

        Inquiry q = stored(org, 510L);
        assertThat(q.getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        assertThat(q.getCommentCheckAttemptedAt()).isAfter(first).isEqualTo(clock.instant());
        assertThat(q.getCommentCheckedAt()).isEqualTo(clock.instant());
        assertThat(inquiries.findTop50ByOrgIdOrderByReceivedAtDesc(org)).hasSize(1);
    }

    @Test
    void aFailedDiscoveryIsNotCheckedAndNeverMovesTheSuccessTime() {
        sweep(org, account, withLane, page(article(511L, "N")), noCommentsAnywhere());
        Instant confirmed = clock.instant();

        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(511L, "N")), status(500));

        Inquiry q = stored(org, 511L);
        assertThat(q.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(q.getCommentCheckAttemptedAt()).isEqualTo(clock.instant());
        assertThat(q.getCommentCheckedAt()).as("a failure is not a confirmation").isEqualTo(confirmed);
        assertThat(q.getStatus()).isEqualTo("UNANSWERED");
        assertThat(phaseOf(q)).isEqualTo(InquiryWorkItemPhase.OPEN);
    }

    @Test
    void aRateLimitedDiscoveryOnAFirstSightIsNotCheckedWithNoSuccessTime() {
        sweep(org, account, withLane, page(article(512L, "N")), FakeCafe24HttpClient.rateLimited429("1"));

        Inquiry q = stored(org, 512L);
        assertThat(q.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(q.getCommentCheckAttemptedAt()).isNotNull();
        assertThat(q.getCommentCheckedAt()).isNull();
        assertThat(phaseOf(q)).isEqualTo(InquiryWorkItemPhase.OPEN);
    }

    @Test
    void aFailedCommentReadIsNotChecked() {
        sweep(org, account, withLane, page(article(513L, "N")), commented(513L), status(500));

        Inquiry q = stored(org, 513L);
        assertThat(q.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(q.getCommentCheckedAt()).isNull();
    }

    @Test
    void aDiscoveryListAtItsCapProvesNoAbsence() {
        // 100 commented articles, none of them ours: the list may simply have stopped before ours.
        String hundred = LongStream.rangeClosed(9001, 9100).mapToObj(n -> "{\"article_no\":" + n + "}")
                .collect(Collectors.joining(","));
        sweep(org, account, withLane, page(article(514L, "N")),
                new Cafe24HttpClient.Response(200, "{\"articles\":[" + hundred + "]}", Map.of()));

        assertThat(stored(org, 514L).getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
    }

    @Test
    void articlesBeyondTheCommentReadCapAreNotChecked() {
        int over = Cafe24InquiryAnswerObserver.MAX_COMMENT_READS + 2;
        List<String> rows = new ArrayList<>();
        StringBuilder discovery = new StringBuilder();
        for (long no = 600; no < 600 + over; no++) {
            rows.add(article(no, "N"));
            discovery.append(discovery.length() == 0 ? "" : ",").append("{\"article_no\":").append(no).append('}');
        }
        List<Cafe24HttpClient.Response> responses = new ArrayList<>();
        responses.add(new Cafe24HttpClient.Response(200, "{\"articles\":[" + discovery + "]}", Map.of()));
        for (long no = 600; no < 600 + Cafe24InquiryAnswerObserver.MAX_COMMENT_READS; no++) {
            responses.add(shopComment(no, "buyer01"));   // customers only → read, and no shop comment
        }
        sweep(org, account, withLane, 50, rows, responses.toArray(Cafe24HttpClient.Response[]::new));

        for (long no = 600; no < 600 + Cafe24InquiryAnswerObserver.MAX_COMMENT_READS; no++) {
            assertThat(stored(org, no).getCommentCheck()).as("read " + no).isEqualTo(Result.NO_SHOP_COMMENT);
        }
        for (long no = 600 + Cafe24InquiryAnswerObserver.MAX_COMMENT_READS; no < 600 + over; no++) {
            assertThat(stored(org, no).getCommentCheck()).as("capped " + no).isEqualTo(Result.NOT_CHECKED);
            assertThat(stored(org, no).getCommentCheckedAt()).isNull();
        }
    }

    @Test
    void aRoutineRowWhoseDateCannotBeReadIsNotProvenAbsentFromTheDiscoveryWindow() {
        // The routine lane keeps a row with no readable created_date; the discovery is asked by date window, so
        // "not in the list" says nothing about it.
        String noDate = FakeCafe24HttpClient.article(515L, "제목", "본문", 77L, null, null, "N");
        http.enqueue(FakeCafe24HttpClient.tokenOk("access-1", "old-refresh-token"));
        http.enqueue(FakeCafe24HttpClient.articlesOk(noDate));
        http.enqueue(noCommentsAnywhere());
        FetchPage page = withLane.fetch(new FetchRequest(org, account, "CAFE24", DataType.INQUIRY, null, 3));
        ingestion.ingestInquiries(org, channel, account, records(page));

        assertThat(stored(org, 515L).getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
    }

    // ---- what must not be written --------------------------------------------------------------------------------

    @Test
    void aCollectionThatDidNotLookLeavesTheStoredRecordAlone() {
        sweep(org, account, withLane, page(article(520L, "N")), noCommentsAnywhere());
        Instant recorded = clock.instant();

        clock.advanceHours(1);
        sweep(org, account, withoutLane, page(article(520L, "N")));

        Inquiry q = stored(org, 520L);
        assertThat(q.getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        assertThat(q.getCommentCheckAttemptedAt()).isEqualTo(recorded);
        assertThat(q.getCommentCheckedAt()).isEqualTo(recorded);
    }

    @Test
    void neitherNoCommentNorNotCheckedReopensAnAnsweredInquiry() {
        sweep(org, account, withLane, page(article(521L, "N")), commented(521L), shopComment(521L, "samplemall"));
        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(521L, "N")), noCommentsAnywhere());
        assertThat(stored(org, 521L).getStatus()).isEqualTo("ANSWERED");
        assertThat(stored(org, 521L).getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);

        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(521L, "N")), status(500));

        Inquiry q = stored(org, 521L);
        assertThat(q.getStatus()).isEqualTo("ANSWERED");
        assertThat(q.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(workItems.findByInquiryId(q.getId()).map(InquiryWorkItem::getPhase)
                .filter(p -> p != InquiryWorkItemPhase.COMPLETED)).isEmpty();
    }

    @Test
    void notCheckedAndNoCommentNeverCloseATaskInProgress() {
        sweep(org, account, withLane, page(article(522L, "N")), noCommentsAnywhere());
        InquiryWorkItem wi = workItems.findByInquiryId(stored(org, 522L).getId()).orElseThrow();
        wi.setPhase(InquiryWorkItemPhase.PROPOSED);
        workItems.save(wi);

        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(522L, "N")), status(500));
        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(522L, "N")), noCommentsAnywhere());

        assertThat(phaseOf(stored(org, 522L))).isEqualTo(InquiryWorkItemPhase.PROPOSED);
    }

    @Test
    void threadRepliesAndAlreadyAnsweredArticlesGetNoRecord() {
        String reply = "{\"article_no\":531,\"title\":\"답글\",\"content\":\"본문\",\"created_date\":\"" + CREATED
                + "\",\"reply_status\":\"N\",\"secret\":\"F\",\"parent_article_no\":530,\"reply_depth\":1}";
        // The candidate set is {530} only: 531 is a reply, 532 is already answered by the article itself.
        sweep(org, account, withLane, 3, List.of(article(530L, "N"), reply, article(532L, "C")),
                noCommentsAnywhere());

        assertThat(stored(org, 530L).getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        for (long no : new long[] {531L, 532L}) {
            Inquiry q = stored(org, no);
            assertThat(q.getCommentCheck()).as("article " + no).isNull();
            assertThat(q.getCommentCheckAttemptedAt()).isNull();
            assertThat(q.getCommentCheckedAt()).isNull();
        }
    }

    @Test
    void aMapperGivenACheckForAReplyStillAttachesNothing() {
        Cafe24BoardArticleRow replyRow = new ObjectMapper().convertValue(Map.of(
                "article_no", 533L, "content", "본문", "created_date", CREATED, "reply_status", "N",
                "parent_article_no", 530L, "reply_depth", 1), Cafe24BoardArticleRow.class);
        CanonicalInquiry mapped = Cafe24InquiryArticleMapper.toCanonicalInquiry(6, replyRow, 1,
                new Cafe24InquiryAnswerObserver.CommentCheck(Cafe24InquiryAnswerObserver.CheckState.NOT_CHECKED, null),
                clock.instant());
        assertThat(mapped.commentCheck()).isNull();
    }

    @Test
    void anotherOrgsSameArticleNumberIsUntouched() {
        sweep(org, account, withLane, page(article(540L, "N")), noCommentsAnywhere());
        Instant ours = clock.instant();

        clock.advanceHours(1);
        sweep(otherOrg, otherAccount, withLane, page(article(540L, "N")), status(500));

        Inquiry mine = stored(org, 540L);
        Inquiry theirs = stored(otherOrg, 540L);
        assertThat(mine.getId()).isNotEqualTo(theirs.getId());
        assertThat(mine.getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        assertThat(mine.getCommentCheckAttemptedAt()).isEqualTo(ours);
        assertThat(theirs.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(theirs.getCommentCheckedAt()).isNull();
    }

    @Test
    void anotherChannelsRowsNeverCarryTheRecord() {
        UUID naver = naverChannel;
        CanonicalInquiry row = new CanonicalInquiry(null, null, null, "본문", "UNANSWERED",
                Instant.parse("2026-11-05T01:00:00Z"), "naver:q:1", 1, "제목", null, null, null,
                com.sellerops.ingest.canonical.ChannelProductRef.of(null), null, null);
        assertThat(row.commentCheck()).isNull();
        ingestion.ingestInquiries(org, naver, account, List.of(row));

        Inquiry q = inquiries.findByOrgIdAndChannelIdAndExternalId(org, naver, "naver:q:1").orElseThrow();
        assertThat(q.getCommentCheck()).isNull();
        assertThat(q.getCommentCheckAttemptedAt()).isNull();
        assertThat(q.getCommentCheckedAt()).isNull();
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    // ---- what the detail then says (2026-11-08, the seller is told before approving) ----------------------------

    @Test
    void theDetailTellsAFailedLookANoRecordAndARecentFailureAfterASuccessApart() {
        // A failed look on first sight: no success time at all.
        sweep(org, account, withLane, page(article(541L, "N")), status(500));
        // A collection that did not look: the row exists, nothing was ever recorded.
        sweep(org, account, withoutLane, page(article(542L, "N")));
        // A success, then a failure an hour later.
        sweep(org, account, withLane, page(article(543L, "N")), noCommentsAnywhere());
        Instant confirmed = clock.instant();
        clock.advanceHours(1);
        sweep(org, account, withLane, page(article(543L, "N")), status(500));

        var failed = com.sellerops.inquiry.proposal.dto.CommentCheckView.of(stored(org, 541L), "CAFE24");
        var none = com.sellerops.inquiry.proposal.dto.CommentCheckView.of(stored(org, 542L), "CAFE24");
        var recent = com.sellerops.inquiry.proposal.dto.CommentCheckView.of(stored(org, 543L), "CAFE24");

        assertThat(failed.state()).isEqualTo("NOT_CHECKED");
        assertThat(failed.attemptedAt()).isNotNull();
        assertThat(failed.lastCheckedAt()).isNull();
        assertThat(none.state()).isEqualTo("NO_RECORD");
        assertThat(none.attemptedAt()).isNull();
        assertThat(none.lastCheckedAt()).isNull();
        assertThat(recent.state()).isEqualTo("NOT_CHECKED");
        assertThat(recent.attemptedAt()).isEqualTo(clock.instant());
        assertThat(recent.lastCheckedAt()).as("the success survives the later failure").isEqualTo(confirmed);
        // None of the three answered anything or moved a task: being told is all that changed.
        for (long no : new long[] {541L, 542L, 543L}) {
            assertThat(stored(org, no).getStatus()).isEqualTo("UNANSWERED");
            assertThat(phaseOf(stored(org, no))).isEqualTo(InquiryWorkItemPhase.OPEN);
        }
    }

    private static String article(long no, String reply) {
        return FakeCafe24HttpClient.article(no, "제목", "본문", 77L, null, CREATED, reply);
    }

    private static List<String> page(String... articles) {
        return List.of(articles);
    }

    private static Cafe24HttpClient.Response noCommentsAnywhere() {
        return new Cafe24HttpClient.Response(200, "{\"articles\":[]}", Map.of());
    }

    private static Cafe24HttpClient.Response commented(long no) {
        return new Cafe24HttpClient.Response(200, "{\"articles\":[{\"article_no\":" + no + "}]}", Map.of());
    }

    private static Cafe24HttpClient.Response shopComment(long no, String memberId) {
        return new Cafe24HttpClient.Response(200, "{\"comments\":[{\"comment_no\":39,\"article_no\":" + no
                + ",\"created_date\":\"2026-11-06T14:56:24+09:00\",\"member_id\":\"" + memberId + "\"}]}", Map.of());
    }

    private static Cafe24HttpClient.Response status(int code) {
        return new Cafe24HttpClient.Response(code, "{}", Map.of());
    }

    private void sweep(UUID orgId, UUID accountId, Cafe24ApiConnector connector, List<String> rows,
                       Cafe24HttpClient.Response... after) {
        sweep(orgId, accountId, connector, 3, rows, after);
    }

    private void sweep(UUID orgId, UUID accountId, Cafe24ApiConnector connector, int limit, List<String> rows,
                       Cafe24HttpClient.Response... after) {
        http.enqueue(FakeCafe24HttpClient.tokenOk("access-1", "old-refresh-token"));
        http.enqueue(FakeCafe24HttpClient.articlesOk(rows.toArray(String[]::new)));
        for (Cafe24HttpClient.Response r : after) {
            http.enqueue(r);
        }
        String cursor = Cafe24ArticleCursor.window(6, START, END).encode();
        FetchPage page = connector.fetch(new FetchRequest(orgId, accountId, "CAFE24", DataType.INQUIRY, cursor, limit));
        ingestion.ingestInquiries(orgId, channel, accountId, records(page));
    }

    @SuppressWarnings("unchecked")
    private static List<CanonicalInquiry> records(FetchPage page) {
        return (List<CanonicalInquiry>) page.records();
    }

    private Inquiry stored(UUID orgId, long articleNo) {
        return inquiries.findByOrgIdAndChannelIdAndExternalId(orgId, channel,
                Cafe24InquiryArticleMapper.externalId(6, articleNo)).orElseThrow();
    }

    private InquiryWorkItemPhase phaseOf(Inquiry q) {
        return workItems.findByInquiryId(q.getId()).map(InquiryWorkItem::getPhase).orElse(null);
    }

    /** A clock the test moves, so "the time of this look" and "the time of the last one" are different numbers. */
    private static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant start) {
            this.now = start;
        }

        void advanceHours(long hours) {
            now = now.plusSeconds(hours * 3600);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
