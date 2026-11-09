package com.sellerops.connector.cafe24;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.community.Cafe24CommunityArticleRepository;
import com.sellerops.connector.DataType;
import com.sellerops.connector.FetchPage;
import com.sellerops.connector.FetchRequest;
import com.sellerops.connector.cafe24.Cafe24AnswerRecheckRunner.Outcome;
import com.sellerops.connector.cafe24.Cafe24AnswerRecheckRunner.Stop;
import com.sellerops.connector.cafe24.FakeCafe24Board.Kind;
import com.sellerops.credential.ConnectorCredentialRepository;
import com.sellerops.credential.CredentialVault;
import com.sellerops.ingest.IngestionService;
import com.sellerops.ingest.canonical.CanonicalInquiry;
import com.sellerops.ingest.canonical.CommentAnswerCheck.Result;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryOperationalState;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.publish.InquiryExecution;
import com.sellerops.inquiry.publish.InquiryExecutionRepository;
import com.sellerops.inquiry.publish.InquiryExecutionStatus;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The open-inquiry recheck, end to end over a fake Cafe24 mall that answers from what it holds (2026-11-08,
 * inquiry_operational_truth_v1 §10.1–10.3). Every inquiry here is created by collecting it — the routine sweep over the
 * fake mall at an earlier date — and every late answer is something the fake seller did on the fake seller centre. No
 * state is written by hand except where a test is about an exclusion (an approved task, a send in flight, a spam mark).
 *
 * <p>Not final: {@code Cafe24AnswerRecheckPostgresProofIT} runs every case again on Flyway over PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class Cafe24AnswerRecheckTest {

    /** 2026-10-01 10:00 KST: when the old inquiries were collected. */
    static final Instant COLLECTED = Instant.parse("2026-10-01T01:00:00Z");
    /** 2026-11-08 10:00 KST: the recheck runs, five weeks later. */
    static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");
    static final String OLD = "2026-09-30T10:00:00+09:00";
    static final String LATE_ANSWER = "2026-11-07T15:00:00+09:00";

    @Autowired ReviewRepository reviews;
    @Autowired InquiryRepository inquiries;
    @Autowired OrderDailySummaryRepository orders;
    @Autowired ProductRepository products;
    @Autowired Cafe24CommunityArticleRepository communityArticles;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquiryWorkItemAuditRepository audits;
    @Autowired InquiryExecutionRepository executions;
    @Autowired com.sellerops.inquiry.publish.InquiryActionIntentRepository intents;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ChannelRepository channels;
    @Autowired ConnectorCredentialRepository credentials;
    @Autowired com.sellerops.inquiry.recheck.InquiryRecheckDailyUsageRepository dailyUsage;
    @Autowired com.sellerops.inquiry.recheck.InquiryRecheckRunLockRepository runLocks;
    @Autowired com.sellerops.inquiry.recheck.InquiryRecheckAccountWaitRepository accountWaits;
    @Autowired JdbcTemplate jdbcTemplate;

    protected final UUID org = UUID.randomUUID();
    protected final UUID account = UUID.randomUUID();
    protected final UUID otherOrg = UUID.randomUUID();
    protected final UUID otherAccount = UUID.randomUUID();
    protected final UUID channel = UUID.randomUUID();
    protected final UUID otherChannel = UUID.randomUUID();

    final FakeCafe24Board mall = new FakeCafe24Board("samplemall");
    final FakeCafe24Board otherMall = new FakeCafe24Board("othermall");
    final SettableClock clock = new SettableClock(COLLECTED);

    IngestionService ingestion;
    Cafe24Authorizer authorizer;
    /**
     * The settings a runner gets. The day's budget is set far above any per-run limit here, so the cases about one run
     * read as they did before there was a day; the cases about the day set their own.
     */
    Cafe24RecheckPolicy policy = new Cafe24RecheckPolicy(1_000, Cafe24RecheckPolicy.PROVISIONAL.retryAfterDays(),
            Cafe24RecheckPolicy.PROVISIONAL.runLease());

    /** Parent rows for the ids above; H2's entity-built schema has no foreign keys. */
    protected void prepareTenants() {
    }

    /** Removes what {@link #prepareTenants} wrote, for a test that commits. Nothing on H2. */
    protected void dropTenants() {
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
        authorizer = new Cafe24Authorizer(new Cafe24TokenClient(new RoutingHttp()), vault, "app-client-id",
                "app-client-secret");
    }

    // ---- the story ---------------------------------------------------------------------------------------------

    @Test
    void anOldInquiryAnsweredLateOnTheSellerCentreLeavesTheListOnRecheckAndNotBefore() {
        mall.post(701L, OLD);
        collectRoutine();
        assertThat(phaseOf(701L)).isEqualTo(InquiryWorkItemPhase.OPEN);

        clock.set(NOW);
        mall.shopComments(701L, LATE_ANSWER);
        collectRoutine();
        assertThat(stored(701L).getStatus()).as("the routine sweep no longer reads a five-week-old article")
                .isEqualTo("UNANSWERED");
        assertThat(phaseOf(701L)).isEqualTo(InquiryWorkItemPhase.OPEN);

        int listsBefore = (int) mall.count(Kind.LIST);
        Outcome o = recheck(20);

        Inquiry q = stored(701L);
        assertThat(q.getStatus()).isEqualTo("ANSWERED");
        assertThat(q.getAnsweredAt()).as("the comment's own time, not the time we looked")
                .isEqualTo(Instant.parse("2026-11-07T06:00:00Z"));
        assertThat(q.getCommentCheck()).isEqualTo(Result.SHOP_ANSWERED);
        assertThat(q.getCommentCheckedAt()).isEqualTo(NOW);
        assertThat(phaseOf(701L)).isEqualTo(InquiryWorkItemPhase.COMPLETED);
        assertThat(o.answered()).isEqualTo(1);
        assertThat(o.stop()).isEqualTo(Stop.DONE);
        // token + re-read + discovery + one comment read, every one of them counted
        assertThat(o.requests()).isEqualTo(4);
        assertThat(mall.count(Kind.LIST)).as("no windowed list: the new-inquiry lane is not touched")
                .isEqualTo(listsBefore);
    }

    @Test
    void aLateAnswerByReplyStatusClosesItTooAndLeavesTheCommentRecordAlone() {
        mall.post(702L, OLD);
        collectRoutine();
        Instant firstLook = stored(702L).getCommentCheckAttemptedAt();
        clock.set(NOW);
        mall.articles.get(702L).replyStatus = "C";

        Outcome o = recheck(20);

        assertThat(stored(702L).getStatus()).isEqualTo("ANSWERED");
        assertThat(phaseOf(702L)).isEqualTo(InquiryWorkItemPhase.COMPLETED);
        assertThat(stored(702L).getCommentCheckAttemptedAt()).as("an answered article is not a comment candidate")
                .isEqualTo(firstLook);
        assertThat(o.requests()).as("token + re-read; no discovery for zero candidates").isEqualTo(2);
    }

    @Test
    void stillNoAnswerStaysOpenAndRecordsTheSuccessfulLook() {
        mall.post(703L, OLD);
        mall.customerComments(703L, LATE_ANSWER);
        collectRoutine();
        clock.set(NOW);

        Outcome o = recheck(20);

        Inquiry q = stored(703L);
        assertThat(q.getStatus()).isEqualTo("UNANSWERED");
        assertThat(phaseOf(703L)).isEqualTo(InquiryWorkItemPhase.OPEN);
        assertThat(q.getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        assertThat(q.getCommentCheckAttemptedAt()).isEqualTo(NOW);
        assertThat(q.getCommentCheckedAt()).isEqualTo(NOW);
        assertThat(o.noShopComment()).isEqualTo(1);
    }

    // ---- failed looks ------------------------------------------------------------------------------------------

    @Test
    void aFailedReReadIsNotCheckedForTheBatchKeepsTheSuccessTimeAndStops() {
        mall.post(711L, OLD);
        mall.post(712L, OLD);
        collectRoutine();
        Instant success = stored(711L).getCommentCheckedAt();
        clock.set(NOW);
        mall.shopComments(711L, LATE_ANSWER);
        mall.failNext(Kind.BY_NUMBER, 500);

        Outcome o = recheck(20);

        for (long no : new long[] {711L, 712L}) {
            Inquiry q = stored(no);
            assertThat(q.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
            assertThat(q.getCommentCheckAttemptedAt()).isEqualTo(NOW);
            assertThat(q.getCommentCheckedAt()).as("a failure is not a confirmation").isEqualTo(success);
            assertThat(q.getStatus()).as("a failed look is never 'no answer' and never 'answered'").isEqualTo("UNANSWERED");
            assertThat(phaseOf(no)).isEqualTo(InquiryWorkItemPhase.OPEN);
        }
        assertThat(o.stop()).isEqualTo(Stop.CHANNEL_FAILED);
        assertThat(o.notCheckedAccount()).as("a failed batch re-read is not about either article").isEqualTo(2);
        assertThat(o.notCheckedArticle()).isZero();
        assertThat(o.looks()).extracting(Cafe24AnswerRecheckRunner.Look::cause)
                .containsOnly(Cafe24AnswerRecheckRunner.Cause.ACCOUNT);
    }

    @Test
    void aRateLimitStopsTheRunAndOnlyWhatWasAskedIsRecorded() {
        LongStream.rangeClosed(721L, 760L).forEach(no -> mall.post(no, OLD));
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.BY_NUMBER, 429);

        Outcome o = recheck(100);

        assertThat(o.stop()).isEqualTo(Stop.RATE_LIMITED);
        assertThat(o.attempted()).isEqualTo(Cafe24AnswerRecheckRunner.MAX_BATCH);
        long recorded = LongStream.rangeClosed(721L, 760L)
                .filter(no -> NOW.equals(stored(no).getCommentCheckAttemptedAt())).count();
        assertThat(recorded).isEqualTo(Cafe24AnswerRecheckRunner.MAX_BATCH);
        assertThat(o.notReached()).isEqualTo(40 - Cafe24AnswerRecheckRunner.MAX_BATCH);
        assertThat(mall.count(Kind.BY_NUMBER)).as("nothing more was asked after the 429").isEqualTo(1);
        assertThat(o.notCheckedAccount()).isEqualTo(Cafe24AnswerRecheckRunner.MAX_BATCH);
        assertThat(o.notCheckedArticle()).isZero();
    }

    @Test
    void aFailedDiscoveryLeavesTheStatusFactsAndRecordsNotChecked() {
        mall.post(731L, OLD);
        collectRoutine();
        Instant seenBefore = stored(731L).getLastSeenAt();
        clock.set(NOW);
        mall.failNext(Kind.DISCOVERY, 500);

        recheck(20);

        assertThat(stored(731L).getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(stored(731L).getCommentCheckAttemptedAt()).isEqualTo(NOW);
        assertThat(stored(731L).getLastSeenAt()).as("the re-read itself succeeded").isAfter(seenBefore);
        assertThat(phaseOf(731L)).isEqualTo(InquiryWorkItemPhase.OPEN);
    }

    @Test
    void aFailedDiscoveryIsTheAccountsFailureAndStopsTheRun() {
        LongStream.rangeClosed(731L, 760L).forEach(no -> mall.post(no, OLD));
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.DISCOVERY, 500);

        Outcome o = recheck(100);

        assertThat(o.stop()).isEqualTo(Stop.CHANNEL_FAILED);
        assertThat(o.attempted()).isEqualTo(Cafe24AnswerRecheckRunner.MAX_BATCH);
        assertThat(o.notCheckedAccount()).isEqualTo(Cafe24AnswerRecheckRunner.MAX_BATCH);
        assertThat(o.notCheckedArticle()).isZero();
        assertThat(mall.count(Kind.BY_NUMBER)).as("no second batch after the channel failed").isEqualTo(1);
    }

    @Test
    void anUnknownCommentAuthorIsNotCheckedNotAnswered() {
        mall.post(741L, OLD);
        collectRoutine();
        clock.set(NOW);
        mall.articles.get(741L).comments.add(new String[] {"", LATE_ANSWER});

        Outcome o = recheck(20);

        assertThat(stored(741L).getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(stored(741L).getStatus()).isEqualTo("UNANSWERED");
        assertThat(phaseOf(741L)).isEqualTo(InquiryWorkItemPhase.OPEN);
        assertThat(o.looks()).containsExactly(new Cafe24AnswerRecheckRunner.Look(stored(741L).getId(),
                Cafe24AnswerRecheckRunner.Seen.NOT_CHECKED, Cafe24AnswerRecheckRunner.Cause.ARTICLE));
        assertThat(o.stop()).as("one article's doubt does not stop the run").isEqualTo(Stop.DONE);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    void oneArticlesFailedCommentReadIsItsOwnAndTheRunGoesOn(int status) {
        mall.post(761L, OLD);
        mall.post(762L, OLD);
        mall.customerComments(761L, LATE_ANSWER);
        mall.customerComments(762L, LATE_ANSWER);
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.COMMENTS, status);

        Outcome o = recheck(20);

        assertThat(o.looks()).containsExactly(
                new Cafe24AnswerRecheckRunner.Look(stored(761L).getId(), Cafe24AnswerRecheckRunner.Seen.NOT_CHECKED,
                        Cafe24AnswerRecheckRunner.Cause.ARTICLE),
                new Cafe24AnswerRecheckRunner.Look(stored(762L).getId(), Cafe24AnswerRecheckRunner.Seen.NO_SHOP_COMMENT,
                        null));
        assertThat(o.stop()).isEqualTo(Stop.DONE);
    }

    @Test
    void aRateLimitBetweenCommentReadsIsTheAccountsForEveryoneItCutOff() {
        LongStream.rangeClosed(771L, 773L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.COMMENTS, 0, 429);

        Outcome o = recheck(20);

        assertThat(o.stop()).isEqualTo(Stop.RATE_LIMITED);
        assertThat(o.noShopComment()).isEqualTo(1);
        assertThat(o.notCheckedAccount()).isEqualTo(2);
        assertThat(o.notCheckedArticle()).isZero();
        assertThat(mall.count(Kind.COMMENTS) - 3).as("no comment read after the 429").isEqualTo(2);
    }

    /**
     * 30 old inquiries, each with a customer's comment, so every one needs a comment read — more than one batch. The
     * second comment read of the first batch fails as {@code failure}.
     */
    private Outcome commentReadFailsSecond(int failure) {
        LongStream.rangeClosed(801L, 830L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        clock.set(NOW);
        commentReadsBefore = mall.count(Kind.COMMENTS);
        byNumberBefore = mall.count(Kind.BY_NUMBER);
        mall.failNext(Kind.COMMENTS, 0, failure);
        return recheck(100);
    }

    private long commentReadsBefore;
    private long byNumberBefore;

    /** The first read is kept as what it found; the failed one and every one it cut off are the account's. */
    private Outcome stoppedAtOnceAsTheAccounts(int failure) {
        Outcome o = commentReadFailsSecond(failure);
        assertThat(mall.count(Kind.COMMENTS) - commentReadsBefore)
                .as("no comment request after the failed one").isEqualTo(2);
        assertThat(mall.count(Kind.BY_NUMBER) - byNumberBefore).as("no second batch").isEqualTo(1);
        assertThat(o.notReached()).as("the rest waits for another run").isPositive();
        assertThat(o.noShopComment()).as("what was already read is kept").isEqualTo(1);
        assertThat(o.notCheckedAccount()).isEqualTo(o.attempted() - 1);
        assertThat(o.notCheckedArticle()).as("nothing here is about one article").isZero();
        assertThat(o.looks()).filteredOn(l -> l.seen() == Cafe24AnswerRecheckRunner.Seen.NOT_CHECKED)
                .extracting(Cafe24AnswerRecheckRunner.Look::cause).containsOnly(Cafe24AnswerRecheckRunner.Cause.ACCOUNT);

        Cafe24AnswerRecheckRunner.Look read = o.looks().get(0);
        Cafe24AnswerRecheckRunner.Look failed = o.looks().get(1);
        assertThat(read.seen()).isEqualTo(Cafe24AnswerRecheckRunner.Seen.NO_SHOP_COMMENT);
        assertThat(inquiries.findById(read.inquiryId()).orElseThrow().getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        Inquiry cut = inquiries.findById(failed.inquiryId()).orElseThrow();
        assertThat(cut.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(cut.getCommentCheckAttemptedAt()).isEqualTo(NOW);
        assertThat(cut.getStatus()).isEqualTo("UNANSWERED");
        assertThat(workItems.findByInquiryId(cut.getId()).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.OPEN);
        return o;
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedTokenDuringCommentReadsIsTheAccountsAndStopsAtOnce(int status) {
        Outcome o = stoppedAtOnceAsTheAccounts(status);
        assertThat(o.stop()).isEqualTo(Stop.AUTH_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void anOutageDuringCommentReadsIsTheAccountsAndStopsAtOnce(int status) {
        Outcome o = stoppedAtOnceAsTheAccounts(status);
        assertThat(o.stop()).isEqualTo(Stop.CHANNEL_FAILED);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 418, FakeCafe24Board.TRANSPORT_FAILURE})
    void aCommentReadFailureOfUnknownKindIsTheAccountsAndStopsAtOnce(int failure) {
        Outcome o = stoppedAtOnceAsTheAccounts(failure);
        assertThat(o.stop()).isEqualTo(Stop.CHANNEL_FAILED);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedTokenOnTheReReadOrTheDiscoveryStopsTheRunAsRefused(int status) {
        LongStream.rangeClosed(841L, 870L).forEach(no -> mall.post(no, OLD));
        collectRoutine();
        clock.set(NOW);
        long byNumberAtStart = mall.count(Kind.BY_NUMBER);
        mall.failNext(Kind.BY_NUMBER, status);

        Outcome reRead = recheck(100);

        assertThat(reRead.stop()).isEqualTo(Stop.AUTH_REJECTED);
        assertThat(reRead.notCheckedAccount()).isEqualTo(Cafe24AnswerRecheckRunner.MAX_BATCH);
        assertThat(reRead.notCheckedArticle()).isZero();
        assertThat(mall.count(Kind.BY_NUMBER) - byNumberAtStart).isEqualTo(1);

        pastAccountWait(); // the refusal made the account rest (V186); asked again once that is over
        long discoveriesAtStart = mall.count(Kind.DISCOVERY);
        mall.failNext(Kind.DISCOVERY, status);

        Outcome discovery = recheck(100);

        assertThat(discovery.stop()).isEqualTo(Stop.AUTH_REJECTED);
        assertThat(discovery.notCheckedArticle()).isZero();
        assertThat(mall.count(Kind.DISCOVERY) - discoveriesAtStart).isEqualTo(1);
        assertThat(mall.count(Kind.COMMENTS)).as("no article here has a comment, and none was read").isZero();
    }

    @Test
    void noTokenMeansNothingWasAskedAndNothingIsWritten() {
        mall.post(781L, OLD);
        collectRoutine();
        Inquiry before = stored(781L);
        Result recordBefore = before.getCommentCheck();
        Instant attemptedBefore = before.getCommentCheckAttemptedAt();
        clock.set(NOW);
        mall.failNext(Kind.TOKEN, 500);

        Outcome o = recheck(20);

        assertThat(o.stop()).isEqualTo(Stop.AUTH_FAILED);
        assertThat(o.looks()).isEmpty();
        assertThat(o.attempted()).isZero();
        assertThat(stored(781L).getCommentCheck()).isEqualTo(recordBefore);
        assertThat(stored(781L).getCommentCheckAttemptedAt()).isEqualTo(attemptedBefore);
    }

    @Test
    void aCommentRequestTheLimitRefusedIsNotAttemptedAndNeverStored() {
        LongStream.rangeClosed(791L, 793L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        Cafe24RequestBudget budget = new Cafe24RequestBudget(new RoutingHttp(), 2); // the discovery and one read
        Cafe24InquiryAnswerObserver observer = new Cafe24InquiryAnswerObserver(new Cafe24BoardArticlesClient(budget),
                new Cafe24BoardCommentsClient(budget));

        Map<Long, Cafe24InquiryAnswerObserver.CommentCheck> checks =
                observer.observeExactChecks("access", "samplemall", 6, List.of(791L, 792L, 793L));

        assertThat(checks.get(791L).state()).isEqualTo(Cafe24InquiryAnswerObserver.CheckState.NO_SHOP_COMMENT);
        assertThat(checks.get(792L).notAttempted()).isTrue();
        assertThat(checks.get(793L).notAttempted()).isTrue();
        assertThat(budget.refusals()).isEqualTo(1);
        Cafe24BoardArticleRow row = new Cafe24BoardArticleRow(792L, "t", "c", null, null, OLD, null, "N");
        assertThat(Cafe24InquiryArticleMapper.toCanonicalInquiry(6, row, 1, checks.get(792L), NOW).commentCheck())
                .as("a look that was never sent is never stored").isNull();
    }

    @Test
    void anArticleTheSourceDoesNotReturnIsNotCheckedAndNothingElseMoves() {
        mall.post(751L, OLD);
        collectRoutine();
        Instant seen = stored(751L).getLastSeenAt();
        clock.set(NOW);
        mall.hidden.add(751L);

        Outcome o = recheck(20);
        assertThat(o.looks()).extracting(Cafe24AnswerRecheckRunner.Look::cause)
                .containsExactly(Cafe24AnswerRecheckRunner.Cause.ARTICLE);

        Inquiry q = stored(751L);
        assertThat(q.getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(q.getCommentCheckAttemptedAt()).isEqualTo(NOW);
        assertThat(q.getLastSeenAt()).as("absent is not seen").isEqualTo(seen);
        assertThat(q.getStatus()).isEqualTo("UNANSWERED");
        assertThat(q.getOperationalState()).isEqualTo(InquiryOperationalState.ACTIVE);
        assertThat(phaseOf(751L)).isEqualTo(InquiryWorkItemPhase.OPEN);
        assertThat(o.notFound()).isEqualTo(1);
    }

    // ---- the request limit -------------------------------------------------------------------------------------

    @Test
    void theLimitCountsEveryRequestAndWhatItLeavesOutGetsNoRecord() {
        LongStream.rangeClosed(801L, 830L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER); // every one costs a comment read
        });
        collectRoutine();
        Map<Long, Result> recordBefore = new java.util.HashMap<>();
        LongStream.rangeClosed(801L, 830L).forEach(no -> recordBefore.put(no, stored(no).getCommentCheck()));
        clock.set(NOW);
        int before = mall.requests.size();

        Outcome o = recheck(12);

        assertThat(o.requests()).isLessThanOrEqualTo(12);
        assertThat(mall.requests.size() - before).as("what the runner counted is what went out")
                .isEqualTo(o.requests());
        long looked = LongStream.rangeClosed(801L, 830L)
                .filter(no -> NOW.equals(stored(no).getCommentCheckAttemptedAt())).count();
        assertThat(looked).isEqualTo(o.attempted());
        assertThat(o.notReached()).isEqualTo(30 - o.attempted()).isPositive();
        assertThat(o.notChecked()).as("not reached is not a failed look").isZero();
        assertThat(o.refused()).as("batches are planned so the limit never has to refuse").isZero();
        assertThat(o.stop()).isEqualTo(Stop.LIMIT);
        LongStream.rangeClosed(801L, 830L).filter(no -> !NOW.equals(stored(no).getCommentCheckAttemptedAt()))
                .forEach(no -> {
                    assertThat(stored(no).getCommentCheck()).as("left exactly as the last sweep left it")
                            .isEqualTo(recordBefore.get(no));
                    assertThat(stored(no).getCommentCheckAttemptedAt()).isEqualTo(COLLECTED);
                });
    }

    @Test
    void aLimitTooSmallForOneLookSendsNothing() {
        mall.post(841L, OLD);
        collectRoutine();
        clock.set(NOW);
        int before = mall.requests.size();

        Outcome o = recheck(2);

        assertThat(mall.requests.size()).isEqualTo(before);
        assertThat(o.stop()).isEqualTo(Stop.LIMIT);
        assertThat(o.notReached()).isEqualTo(1);
        assertThat(stored(841L).getCommentCheckAttemptedAt()).isEqualTo(COLLECTED);
    }

    @Test
    void articlesThatKeepFailingCannotSpendTheOthersShare() {
        // Ten whose last look failed, four whose last look succeeded — all by collection.
        LongStream.rangeClosed(851L, 864L).forEach(no -> mall.post(no, OLD));
        LongStream.rangeClosed(861L, 864L).forEach(mall.hidden::add);
        mall.failNext(Kind.DISCOVERY, 500);
        collectRoutine();                                        // 851–860: the comment lane failed
        mall.hidden.clear();
        LongStream.rangeClosed(851L, 860L).forEach(mall.hidden::add);
        collectRoutine();                                        // 861–864: looked, no shop comment
        mall.hidden.clear();
        assertThat(stored(851L).getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        assertThat(stored(861L).getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
        clock.set(NOW);

        Outcome o = recheck(10);

        long othersLooked = LongStream.rangeClosed(861L, 864L)
                .filter(no -> NOW.equals(stored(no).getCommentCheckAttemptedAt())).count();
        long failedLooked = LongStream.rangeClosed(851L, 860L)
                .filter(no -> NOW.equals(stored(no).getCommentCheckAttemptedAt())).count();
        assertThat(othersLooked).as("the other half of the limit is theirs").isEqualTo(4);
        assertThat(failedLooked).as("the failed ones still get their half").isPositive();
        assertThat(o.requests()).isLessThanOrEqualTo(10);
        assertThat(o.notReached()).isEqualTo(10 - failedLooked).isPositive();
    }

    // ---- who is asked about ------------------------------------------------------------------------------------

    @Test
    void onlyOldOpenWorkOfThisAccountIsAskedAbout() {
        mall.post(901L, OLD);                                   // asked
        mall.post(902L, OLD);                                   // approved by the seller: not asked
        mall.post(903L, OLD);                                   // our send is in flight: not asked
        mall.post(904L, OLD);                                   // marked spam: not asked
        mall.post(905L, OLD).parent = 901L;                     // a thread reply: not asked
        mall.post(906L, OLD).replyStatus = "C";                 // already answered: not asked
        collectRoutine();
        clock.set(NOW);
        mall.post(907L, "2026-11-05T10:00:00+09:00");           // recent: the routine sweep's
        collectRoutine();
        setPhase(902L, InquiryWorkItemPhase.APPROVED);
        UUID sendingItem = workItems.findByInquiryId(stored(903L).getId()).orElseThrow().getId();
        com.sellerops.inquiry.publish.InquiryActionIntent intent = new com.sellerops.inquiry.publish.InquiryActionIntent();
        intent.setOrgId(org);
        intent.setWorkItemId(sendingItem);
        intent.setApprovedFingerprint("f".repeat(64));
        intent.setActionKind("REPLY_PUBLISH");
        intent = intents.save(intent);
        InquiryExecution inFlight = new InquiryExecution();
        inFlight.setOrgId(org);
        inFlight.setWorkItemId(sendingItem);
        inFlight.setActionIntentId(intent.getId());
        inFlight.setDispatchKey("k".repeat(64));
        inFlight.setStatus(InquiryExecutionStatus.DELIVERY_UNKNOWN);
        executions.save(inFlight);
        Inquiry spam = stored(904L);
        spam.setOperationalState(InquiryOperationalState.EXCLUDED_SPAM);
        inquiries.save(spam);
        // Another org holds the same article number on its own mall.
        otherMall.post(901L, OLD);
        clock.set(COLLECTED);
        collectRoutineFor(otherOrg, otherAccount, otherMall);
        clock.set(NOW);

        Cafe24AnswerRecheckRunner runner = runner();
        assertThat(runner.targets(org, account, channel)).extracting(Inquiry::getExternalId)
                .containsExactly(Cafe24InquiryArticleMapper.externalId(6, 901L));
        assertThat(runner.targets(org, account, otherChannel)).as("another channel").isEmpty();

        Instant otherBefore = storedFor(otherOrg, 901L).getCommentCheckAttemptedAt();
        runner.run(org, account, channel, 20);
        assertThat(storedFor(otherOrg, 901L).getCommentCheckAttemptedAt()).as("another org's same article")
                .isEqualTo(otherBefore);
        assertThat(otherMall.count(Kind.BY_NUMBER)).as("another org's mall is never asked").isZero();
    }

    @Test
    void anotherAccountsRunAsksItsOwnMallAboutItsOwnRows() {
        mall.post(911L, OLD);
        otherMall.post(911L, OLD);
        collectRoutine();
        collectRoutineFor(otherOrg, otherAccount, otherMall);
        clock.set(NOW);
        otherMall.shopComments(911L, LATE_ANSWER);

        Outcome o = runner().run(otherOrg, otherAccount, channel, 20);

        assertThat(o.answered()).isEqualTo(1);
        assertThat(storedFor(otherOrg, 911L).getStatus()).isEqualTo("ANSWERED");
        assertThat(stored(911L).getStatus()).as("this org's row is untouched").isEqualTo("UNANSWERED");
        assertThat(mall.count(Kind.BY_NUMBER)).isZero();
    }

    // ---- across days: retry waits per inquiry ------------------------------------------------------------------

    @Test
    void anInquiryThatKeepsFailingOnItsOwnWaitsOneTwoFourThenSevenDaysWhileTheOthersAreCheckedEveryDay() {
        LongStream.rangeClosed(1001L, 1003L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER); // every one costs a comment read
        });
        collectRoutine();
        mall.brokenComments.add(1001L); // its comment read answers 500 every time: its own lasting trouble

        // Failure n on day F waits waitDaysAfter(n) Korean days: asked on 0, 1 (+1), 3 (+2), 7 (+4), 14 (+7), 21 (+7).
        java.util.Set<Integer> askedOn = java.util.Set.of(0, 1, 3, 7, 14, 21);
        int failures = 0;
        Instant lastFailure = null;
        for (int day = 0; day <= 22; day++) {
            if (day == 21) {
                mall.brokenComments.remove(1001L); // the article reads again
            }
            // An hour earlier than day 0 on every later day: the wait is counted in days, not in 24-hour periods.
            clock.set(day == 0 ? NOW : NOW.plus(Duration.ofDays(day)).minus(Duration.ofHours(1)));
            long commentReads = mall.count(Kind.COMMENTS);

            Outcome o = recheck(100);

            boolean asked = askedOn.contains(day) || day == 22;
            assertThat(o.stop()).as("day %d", day).isEqualTo(Stop.DONE);
            assertThat(o.waiting()).as("day %d: waiting", day).isEqualTo(asked ? 0 : 1);
            assertThat(mall.count(Kind.COMMENTS) - commentReads).as("day %d: comment reads", day)
                    .isEqualTo(asked ? 3 : 2);
            assertThat(o.noShopComment()).as("day %d: the others are checked every day", day)
                    .isEqualTo(day >= 21 ? 3 : 2);
            if (asked && day < 21) {
                failures++;
                lastFailure = clock.instant();
                assertThat(o.notCheckedArticle()).as("day %d", day).isEqualTo(1);
            }
            Inquiry q = stored(1001L);
            assertThat(q.getAnswerRecheckFailures()).as("day %d: failures in a row", day)
                    .isEqualTo(day >= 21 ? 0 : failures);
            assertThat(q.getAnswerRecheckFailedAt()).as("day %d: the last own failure", day)
                    .isEqualTo(day >= 21 ? null : lastFailure);
            assertThat(stored(1002L).getAnswerRecheckFailures()).isZero();
        }
        assertThat(stored(1001L).getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
    }

    @Test
    void theRetryWaitIsTheSettingAndItsLastValueRepeats() {
        policy = new Cafe24RecheckPolicy(1_000, List.of(3), Cafe24RecheckPolicy.PROVISIONAL.runLease());
        mall.post(1051L, OLD);
        mall.customerComments(1051L, LATE_ANSWER);
        collectRoutine();
        mall.brokenComments.add(1051L);

        java.util.List<Integer> asked = new java.util.ArrayList<>();
        for (int day = 0; day <= 6; day++) {
            clock.set(NOW.plus(Duration.ofDays(day)));
            if (recheck(100).waiting() == 0) {
                asked.add(day);
            }
        }
        assertThat(asked).as("3 days after each failure, not 1 · 2 · 4").containsExactly(0, 3, 6);
        assertThat(stored(1051L).getAnswerRecheckFailures()).isEqualTo(3);
    }

    @Test
    void anAccountSideFailureOrARequestNeverSentNeitherCountsNorClearsNorMakesAnInquiryWait() {
        LongStream.rangeClosed(1101L, 1103L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        mall.brokenComments.add(1101L);

        // Day 0: 1101 fails on its own (500), then a 429 cuts 1102 and 1103 off — the account's.
        clock.set(NOW);
        mall.failNext(Kind.COMMENTS, 0, 429);
        Outcome o = recheck(100);
        assertThat(o.stop()).isEqualTo(Stop.RATE_LIMITED);
        assertThat(o.notCheckedArticle()).isEqualTo(1);
        assertThat(o.notCheckedAccount()).isEqualTo(2);
        assertThat(stored(1101L).getAnswerRecheckFailures()).isEqualTo(1);
        assertThat(stored(1101L).getAnswerRecheckFailedAt()).isEqualTo(NOW);
        for (long no : new long[] {1102L, 1103L}) {
            assertThat(stored(no).getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
            assertThat(stored(no).getAnswerRecheckFailures()).as("a 429 is not the inquiry's").isZero();
            assertThat(stored(no).getAnswerRecheckFailedAt()).isNull();
        }

        // Later the same day, once the account's rest after the 429 is over: the two the 429 cut off are not made to
        // wait; 1101 is.
        pastAccountWait();
        o = recheck(100);
        assertThat(o.waiting()).isEqualTo(1);
        assertThat(o.noShopComment()).isEqualTo(2);

        // Day 1: 1101 may be asked again. Each account-side stop on it leaves its count and its failure time alone.
        Instant firstFailure = NOW;
        clock.set(NOW.plus(Duration.ofDays(1)));
        mall.failNext(Kind.BY_NUMBER, 503);
        assertThat(recheck(100).stop()).isEqualTo(Stop.CHANNEL_FAILED);
        pastAccountWait();
        mall.failNext(Kind.COMMENTS, 401);
        assertThat(recheck(100).stop()).isEqualTo(Stop.AUTH_REJECTED);
        pastAccountWait();
        mall.failNext(Kind.COMMENTS, FakeCafe24Board.TRANSPORT_FAILURE);
        assertThat(recheck(100).stop()).isEqualTo(Stop.CHANNEL_FAILED);
        pastAccountWait();
        Outcome tooSmall = recheck(2);
        assertThat(tooSmall.stop()).as("not reached: nothing sent").isEqualTo(Stop.LIMIT);
        assertThat(tooSmall.requests()).isZero();
        assertThat(stored(1101L).getAnswerRecheckFailures()).isEqualTo(1);
        assertThat(stored(1101L).getAnswerRecheckFailedAt()).isEqualTo(firstFailure);

        // It reads again: a successful look clears the count.
        mall.brokenComments.remove(1101L);
        o = recheck(100);
        assertThat(o.noShopComment()).isEqualTo(3);
        assertThat(stored(1101L).getAnswerRecheckFailures()).isZero();
        assertThat(stored(1101L).getAnswerRecheckFailedAt()).isNull();
    }

    @Test
    void anArticleTheSourceNoLongerReturnsIsItsOwnFailureAndWaitsToo() {
        mall.post(1151L, OLD);
        mall.post(1152L, OLD);
        collectRoutine();
        mall.hidden.add(1151L);
        clock.set(NOW);

        Outcome o = recheck(100);

        assertThat(o.notFound()).isEqualTo(1);
        assertThat(stored(1151L).getAnswerRecheckFailures()).isEqualTo(1);
        assertThat(stored(1152L).getAnswerRecheckFailures()).isZero();
        clock.set(NOW.plus(Duration.ofHours(2)));
        assertThat(recheck(100).waiting()).isEqualTo(1);
    }

    @Test
    void aLateAnswerFoundAfterFailuresClearsThemAsItClosesTheTask() {
        mall.post(1171L, OLD);
        mall.customerComments(1171L, LATE_ANSWER);
        collectRoutine();
        mall.brokenComments.add(1171L);
        clock.set(NOW);
        recheck(100);
        assertThat(stored(1171L).getAnswerRecheckFailures()).isEqualTo(1);

        clock.set(NOW.plus(Duration.ofDays(1)));
        mall.brokenComments.remove(1171L);
        mall.shopComments(1171L, "2026-11-08T18:00:00+09:00");
        Outcome o = recheck(100);

        assertThat(o.answered()).isEqualTo(1);
        assertThat(stored(1171L).getStatus()).isEqualTo("ANSWERED");
        assertThat(stored(1171L).getAnswerRecheckFailures()).isZero();
        assertThat(phaseOf(1171L)).isEqualTo(InquiryWorkItemPhase.COMPLETED);
    }

    // ---- across runs: the day's budget -------------------------------------------------------------------------

    @Test
    void theDaysBudgetIsKeptAcrossARestartAndASecondCallTheSameDay() {
        policy = new Cafe24RecheckPolicy(8, Cafe24RecheckPolicy.PROVISIONAL.retryAfterDays(),
                Cafe24RecheckPolicy.PROVISIONAL.runLease());
        LongStream.rangeClosed(1201L, 1210L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        clock.set(NOW);

        Outcome first = runner().run(org, account, channel);

        // token 1 + one batch of 5 (re-read 1 + discovery 1 + 5 comment reads) = 8, all of the day
        assertThat(first.requests()).isEqualTo(8);
        assertThat(first.noShopComment()).isEqualTo(5);
        assertThat(first.stop()).isEqualTo(Stop.DAILY_LIMIT);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(8);
        assertThat(lockHolder(account)).as("the run let go of the account").isEmpty();

        // A restart: a new runner and a new ledger over the same database. And a caller asking for more than the day.
        long sentBefore = mall.requests.size();
        clock.set(NOW.plus(Duration.ofHours(3)));
        for (Outcome again : List.of(runner().run(org, account, channel), recheck(100))) {
            assertThat(again.requests()).isZero();
            assertThat(again.attempted()).isZero();
            assertThat(again.stop()).isEqualTo(Stop.DAILY_LIMIT);
            assertThat(again.refused()).as("planned, not refused at the wire").isZero();
        }
        assertThat(mall.requests.size()).as("nothing more went out today").isEqualTo(sentBefore);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(8);
    }

    @Test
    void aFailedRequestIsSpentFromTheDayToo() {
        policy = new Cafe24RecheckPolicy(8, Cafe24RecheckPolicy.PROVISIONAL.retryAfterDays(),
                Cafe24RecheckPolicy.PROVISIONAL.runLease());
        mall.post(1251L, OLD);
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.BY_NUMBER, 500);

        Outcome o = runner().run(org, account, channel);

        assertThat(o.stop()).isEqualTo(Stop.CHANNEL_FAILED);
        assertThat(o.requests()).as("token + the failed re-read").isEqualTo(2);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(2);
        pastAccountWait(); // the same Korean day, after the account's rest
        mall.failNext(Kind.TOKEN, 500);
        assertThat(runner().run(org, account, channel).stop()).isEqualTo(Stop.AUTH_FAILED);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8)))
                .as("a refused token refresh reached the channel: counted").isEqualTo(3);
    }

    @Test
    void aNewKoreanDayBringsANewBudgetEvenWhileTheUtcDateIsTheSame() {
        policy = new Cafe24RecheckPolicy(8, Cafe24RecheckPolicy.PROVISIONAL.retryAfterDays(),
                Cafe24RecheckPolicy.PROVISIONAL.runLease());
        LongStream.rangeClosed(1201L, 1210L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();

        clock.set(Instant.parse("2026-11-08T14:50:00Z")); // 23:50 on 8 November in Korea
        Outcome late = runner().run(org, account, channel);
        assertThat(late.stop()).isEqualTo(Stop.DAILY_LIMIT);
        List<UUID> lateIds = late.looks().stream().map(Cafe24AnswerRecheckRunner.Look::inquiryId).toList();

        clock.set(Instant.parse("2026-11-08T15:10:00Z")); // 00:10 on 9 November in Korea, still 8 November in UTC
        Outcome next = runner().run(org, account, channel);

        assertThat(next.requests()).isEqualTo(8);
        assertThat(next.noShopComment()).isEqualTo(5);
        assertThat(next.looks()).extracting(Cafe24AnswerRecheckRunner.Look::inquiryId)
                .as("the ones not reached yesterday first").doesNotContainAnyElementsOf(lateIds);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(8);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 9))).isEqualTo(8);
    }

    @Test
    void oneAccountsDayIsNotAnothersBudget() {
        policy = new Cafe24RecheckPolicy(8, Cafe24RecheckPolicy.PROVISIONAL.retryAfterDays(),
                Cafe24RecheckPolicy.PROVISIONAL.runLease());
        LongStream.rangeClosed(1201L, 1210L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        otherMall.post(1291L, OLD);
        collectRoutine();
        collectRoutineFor(otherOrg, otherAccount, otherMall);
        clock.set(NOW);
        assertThat(runner().run(org, account, channel).stop()).isEqualTo(Stop.DAILY_LIMIT);

        Outcome other = runner().run(otherOrg, otherAccount, channel);

        assertThat(other.stop()).isEqualTo(Stop.DONE);
        assertThat(ledger().usedOn(otherAccount, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(other.requests());
    }

    // ---- across runs: one run per account ---------------------------------------------------------------------

    @Test
    void aHeldAccountSendsNothingAndAHoldWhoseRunDiedIsTakenOverWhenItsLeaseRunsOut() {
        mall.post(1301L, OLD);
        otherMall.post(1391L, OLD);
        collectRoutine();
        collectRoutineFor(otherOrg, otherAccount, otherMall);
        clock.set(NOW);
        mall.shopComments(1301L, LATE_ANSWER);
        // Another run took the account and died without letting go.
        ledger().acquire(account, policy.runLease()).orElseThrow();
        long sentBefore = mall.requests.size();

        Outcome busy = recheck(20);

        assertThat(busy.stop()).isEqualTo(Stop.BUSY);
        assertThat(busy.requests()).isZero();
        assertThat(mall.requests.size()).isEqualTo(sentBefore);
        assertThat(stored(1301L).getStatus()).isEqualTo("UNANSWERED");
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isZero();
        assertThat(runner().run(otherOrg, otherAccount, channel, 20).stop())
                .as("another account is not held up").isEqualTo(Stop.DONE);

        clock.set(NOW.plus(policy.runLease()).minusSeconds(1));
        assertThat(recheck(20).stop()).as("the lease has not run out yet").isEqualTo(Stop.BUSY);

        clock.set(NOW.plus(policy.runLease()));
        Outcome after = recheck(20);
        assertThat(after.stop()).isEqualTo(Stop.DONE);
        assertThat(after.answered()).isEqualTo(1);
        assertThat(lockHolder(account)).isEmpty();
    }

    @Test
    void aRunWhoseLeaseWasTakenOverStopsSendingAndLeavesTheNewHoldAlone() {
        LongStream.rangeClosed(1401L, 1403L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        clock.set(NOW);
        java.util.concurrent.atomic.AtomicReference<com.sellerops.inquiry.recheck.InquiryRecheckLedger.Lease> taker =
                new java.util.concurrent.atomic.AtomicReference<>();
        mall.beforeEachRequest = kind -> {
            if (kind == Kind.COMMENTS && taker.get() == null) {
                // The run stalled past its lease during this read, and a second run took the account over.
                clock.set(NOW.plus(policy.runLease()).plusSeconds(1));
                taker.set(ledger().acquire(account, policy.runLease()).orElseThrow());
            }
        };

        Outcome o = recheck(100);

        assertThat(o.stop()).isEqualTo(Stop.BUSY);
        assertThat(o.looks()).extracting(Cafe24AnswerRecheckRunner.Look::seen).containsExactly(
                Cafe24AnswerRecheckRunner.Seen.NO_SHOP_COMMENT, Cafe24AnswerRecheckRunner.Seen.NOT_ATTEMPTED,
                Cafe24AnswerRecheckRunner.Seen.NOT_ATTEMPTED);
        assertThat(mall.count(Kind.COMMENTS)).as("the read under way finished; no further one went out")
                .isEqualTo(1 + 3 /* the routine sweep's */);
        assertThat(lockHolder(account))
                .as("the new holder keeps the account").contains(taker.get().holder());
        assertThat(stored(1402L).getCommentCheckAttemptedAt()).as("nothing written for what was not sent")
                .isEqualTo(COLLECTED);
    }

    @Test
    void aLeaseIsOnlyEverRenewedOrReleasedByItsOwnHolder() {
        com.sellerops.inquiry.recheck.InquiryRecheckLedger ledger = ledger();
        clock.set(NOW);
        var first = ledger.acquire(account, Duration.ofMinutes(10)).orElseThrow();
        assertThat(ledger.acquire(account, Duration.ofMinutes(10))).isEmpty();
        assertThat(ledger.acquire(otherAccount, Duration.ofMinutes(10))).as("per account").isPresent();

        clock.set(NOW.plus(Duration.ofMinutes(10)));
        var second = ledger.acquire(account, Duration.ofMinutes(10)).orElseThrow();
        assertThat(ledger.renew(first)).isFalse();
        ledger.release(first);
        assertThat(lockHolder(account))
                .contains(second.holder());
        assertThat(ledger.renew(second)).isTrue();
        ledger.release(second);
        assertThat(lockHolder(account)).isEmpty();
    }

    // ---- at the same moment, on real threads -------------------------------------------------------------------

    /**
     * Two runs on one account at once, a third on another account meanwhile, then many takers of one day's budget and of
     * one account's lock at the same instant. Not inside the test's transaction: each thread commits its own, as two
     * servers would — so this test writes committed rows and removes them itself ({@link #removeCommitted}).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runsAtTheSameMomentAskOnceAndNeverPassTheDay() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            LongStream.rangeClosed(1501L, 1503L).forEach(no -> {
                mall.post(no, OLD);
                mall.customerComments(no, LATE_ANSWER);
            });
            otherMall.post(1591L, OLD);
            collectRoutine();
            collectRoutineFor(otherOrg, otherAccount, otherMall);
            clock.set(NOW);
            otherMall.shopComments(1591L, LATE_ANSWER);
            long tokens = mall.count(Kind.TOKEN);
            long reReads = mall.count(Kind.BY_NUMBER);
            long commentReads = mall.count(Kind.COMMENTS);

            java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch goOn = new java.util.concurrent.CountDownLatch(1);
            mall.beforeEachRequest = kind -> {
                if (kind == Kind.BY_NUMBER && inside.getCount() > 0) {
                    inside.countDown();
                    try {
                        goOn.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            java.util.concurrent.Future<Outcome> first = pool.submit(() -> recheck(20));
            assertThat(inside.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // The first run holds the account, inside its re-read.
            Outcome second = pool.submit(() -> recheck(20)).get(30, java.util.concurrent.TimeUnit.SECONDS);
            Outcome otherAccounts = pool.submit(() -> runner().run(otherOrg, otherAccount, channel, 20))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            goOn.countDown();
            Outcome firstDone = first.get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(second.stop()).isEqualTo(Stop.BUSY);
            assertThat(second.requests()).isZero();
            assertThat(otherAccounts.stop()).as("not held up by this account's run").isEqualTo(Stop.DONE);
            assertThat(otherAccounts.answered()).isEqualTo(1);
            assertThat(firstDone.stop()).isEqualTo(Stop.DONE);
            assertThat(firstDone.noShopComment()).isEqualTo(3);
            assertThat(mall.count(Kind.TOKEN) - tokens).as("one token refresh").isEqualTo(1);
            assertThat(mall.count(Kind.BY_NUMBER) - reReads).as("one re-read").isEqualTo(1);
            assertThat(mall.count(Kind.COMMENTS) - commentReads).as("each article read once").isEqualTo(3);
            assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(firstDone.requests());

            // Sixteen takers of a 20-request day, ten tries each, all at once: exactly 20 granted.
            com.sellerops.inquiry.recheck.InquiryRecheckLedger ledger = ledger();
            clock.set(NOW.plus(Duration.ofDays(1)));
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> takers = new java.util.ArrayList<>();
            for (int t = 0; t < 16; t++) {
                takers.add(pool.submit(() -> {
                    start.await();
                    int granted = 0;
                    for (int i = 0; i < 10; i++) {
                        granted += ledger.take(account, 20) ? 1 : 0;
                    }
                    return granted;
                }));
            }
            start.countDown();
            int granted = 0;
            for (java.util.concurrent.Future<Integer> f : takers) {
                granted += f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(granted).isEqualTo(20);
            assertThat(ledger.usedOn(account, java.time.LocalDate.of(2026, 11, 9))).isEqualTo(20);

            // Eight runs reaching for one free account at once: exactly one holds it.
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> reaching = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++) {
                reaching.add(pool.submit(() -> {
                    go.await();
                    return ledger.acquire(account, Duration.ofMinutes(10)).isPresent();
                }));
            }
            go.countDown();
            int holders = 0;
            for (java.util.concurrent.Future<Boolean> f : reaching) {
                holders += f.get(60, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(holders).isEqualTo(1);
        } finally {
            mall.beforeEachRequest = null;
            pool.shutdownNow();
            removeCommitted();
        }
    }

    // ---- after each scheduled collection (2026-11-08) ----------------------------------------------------------

    @Test
    void afterEachScheduledCollectionALookThatSucceededTodayIsNotAskedAgainUntilTomorrow() {
        policy = Cafe24RecheckPolicy.PROVISIONAL;
        LongStream.rangeClosed(1601L, 1603L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        mall.brokenComments.add(1601L); // its own lasting trouble: it waits its retry interval

        clock.set(NOW); // 10:00 in Korea
        collectRoutine();
        Outcome first = collectionEnded().orElseThrow();
        assertThat(first.stop()).isEqualTo(Stop.DONE);
        assertThat(first.noShopComment()).isEqualTo(2);
        assertThat(first.notCheckedArticle()).isEqualTo(1);
        assertThat(first.requests()).as("token + re-read + discovery + 3 comment reads").isEqualTo(6);

        // Every later scheduled collection the same Korean day: nothing is asked again.
        for (int hours : new int[] {1, 5, 13}) { // 11:00, 15:00, 23:00
            clock.set(NOW.plus(Duration.ofHours(hours)));
            collectRoutine();
            long sent = mall.requests.size();
            Outcome again = collectionEnded().orElseThrow();
            assertThat(again.requests()).as("%d h later", hours).isZero();
            assertThat(again.targets()).isZero();
            assertThat(again.checkedToday()).as("seen today").isEqualTo(2);
            assertThat(again.waiting()).as("its own failure waits").isEqualTo(1);
            assertThat(again.stop()).isEqualTo(Stop.DONE);
            assertThat(mall.requests.size()).isEqualTo(sent);
        }
        assertThat(stored(1602L).getCommentCheckedAt()).isEqualTo(NOW);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(6);

        // The next Korean day, an hour earlier than yesterday: all three are asked again.
        clock.set(NOW.plus(Duration.ofDays(1)).minus(Duration.ofHours(1)));
        collectRoutine();
        Outcome nextDay = collectionEnded().orElseThrow();
        assertThat(nextDay.checkedToday()).isZero();
        assertThat(nextDay.waiting()).isZero();
        assertThat(nextDay.attempted()).isEqualTo(3);
        assertThat(nextDay.noShopComment()).isEqualTo(2);
        assertThat(stored(1601L).getAnswerRecheckFailures()).isEqualTo(2);
    }

    @Test
    void theDayASuccessCountsForIsTheKoreanDay() {
        policy = Cafe24RecheckPolicy.PROVISIONAL;
        mall.post(1651L, OLD);
        mall.customerComments(1651L, LATE_ANSWER);
        collectRoutine();

        clock.set(Instant.parse("2026-11-07T23:30:00Z")); // 08:30 on 8 November in Korea, still 7 November in UTC
        assertThat(collectionEnded().orElseThrow().noShopComment()).isEqualTo(1);
        clock.set(NOW); // 10:00 on 8 November in Korea
        Outcome sameKoreanDay = collectionEnded().orElseThrow();
        assertThat(sameKoreanDay.requests()).as("the same Korean day, though not the same UTC date").isZero();
        assertThat(sameKoreanDay.checkedToday()).isEqualTo(1);

        clock.set(Instant.parse("2026-11-08T14:50:00Z")); // 23:50 on 8 November in Korea
        assertThat(collectionEnded().orElseThrow().requests()).isZero();
        clock.set(Instant.parse("2026-11-08T15:10:00Z")); // 00:10 on 9 November in Korea, still 8 November in UTC
        Outcome nextKoreanDay = collectionEnded().orElseThrow();
        assertThat(nextKoreanDay.checkedToday()).isZero();
        assertThat(nextKoreanDay.noShopComment()).as("a new Korean day: asked again").isEqualTo(1);
    }

    @Test
    void aRunThatSentNothingDoesNotProveTheAccountAnswersAgain() {
        policy = Cafe24RecheckPolicy.PROVISIONAL;
        mall.post(1661L, OLD);
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.BY_NUMBER, 503);
        assertThat(recheck(20).stop()).isEqualTo(Stop.CHANNEL_FAILED);
        assertThat(ledger().accountFailures(account)).isEqualTo(1);

        pastAccountWait();
        Outcome nothingSent = recheck(2); // too small for one look: nothing goes out
        assertThat(nothingSent.stop()).isEqualTo(Stop.LIMIT);
        assertThat(nothingSent.requests()).isZero();
        assertThat(ledger().accountFailures(account)).as("nothing was asked, so nothing was learned").isEqualTo(1);

        Instant at = clock.instant();
        mall.failNext(Kind.BY_NUMBER, 503);
        assertThat(recheck(20).stop()).isEqualTo(Stop.CHANNEL_FAILED);
        assertThat(ledger().accountFailures(account)).as("still in a row").isEqualTo(2);
        assertThat(accountWaits.waitUntilOf(account)).contains(at.plus(Duration.ofMinutes(240)));
    }

    @Test
    void aLookTheAccountCutOffIsNotASuccessAndTheAccountRestsBeforeItIsAskedAgain() {
        policy = Cafe24RecheckPolicy.PROVISIONAL;
        LongStream.rangeClosed(1611L, 1613L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.COMMENTS, 0, 401); // the first comment read answers, the second is refused

        Outcome first = collectionEnded().orElseThrow();

        assertThat(first.stop()).isEqualTo(Stop.AUTH_REJECTED);
        assertThat(first.looks()).extracting(Cafe24AnswerRecheckRunner.Look::seen).containsExactly(
                Cafe24AnswerRecheckRunner.Seen.NO_SHOP_COMMENT, Cafe24AnswerRecheckRunner.Seen.NOT_CHECKED,
                Cafe24AnswerRecheckRunner.Seen.NOT_CHECKED);
        assertThat(first.notCheckedAccount()).as("the refused one and the one it cut off").isEqualTo(2);
        List<UUID> cutOff = first.looks().stream()
                .filter(l -> l.seen() != Cafe24AnswerRecheckRunner.Seen.NO_SHOP_COMMENT)
                .map(Cafe24AnswerRecheckRunner.Look::inquiryId).toList();
        for (UUID id : cutOff) {
            Inquiry q = inquiries.findById(id).orElseThrow();
            assertThat(q.getCommentCheckedAt()).as("refused or never sent: not a success today")
                    .isEqualTo(COLLECTED);
            assertThat(q.getAnswerRecheckFailures()).as("and not the inquiry's failure").isZero();
        }
        assertThat(ledger().accountFailures(account)).isEqualTo(1);
        assertThat(accountWaits.waitUntilOf(account)).contains(NOW.plus(Duration.ofMinutes(120)));
        int usedAfterFirst = ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8));

        // Scheduled collections end during the rest — after a restart too (every call is a new runner and ledger).
        for (int minutes : new int[] {30, 60, 119}) {
            clock.set(NOW.plus(Duration.ofMinutes(minutes)));
            long sent = mall.requests.size();
            Outcome resting = collectionEnded().orElseThrow();
            assertThat(resting.stop()).as("%d min", minutes).isEqualTo(Stop.ACCOUNT_WAIT);
            assertThat(resting.requests()).isZero();
            assertThat(mall.requests.size()).isEqualTo(sent);
            assertThat(lockHolder(account)).as("let go after resting").isEmpty();
        }
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(usedAfterFirst);

        // The rest is over: only the two the refusal cut off are asked; the one seen today is not.
        clock.set(NOW.plus(Duration.ofMinutes(120)));
        Outcome after = collectionEnded().orElseThrow();
        assertThat(after.stop()).isEqualTo(Stop.DONE);
        assertThat(after.checkedToday()).isEqualTo(1);
        assertThat(after.noShopComment()).isEqualTo(2);
        assertThat(after.looks()).extracting(Cafe24AnswerRecheckRunner.Look::inquiryId)
                .containsExactlyInAnyOrderElementsOf(cutOff);
        assertThat(ledger().accountFailures(account)).as("it answers again: the rest is cleared").isZero();
        assertThat(accountWaits.waitUntilOf(account)).isEmpty();
    }

    @Test
    void anOutageMakesTheAccountRestLongerEachTimeUntilItAnswersAndThenTheRestStartsOver() {
        policy = Cafe24RecheckPolicy.PROVISIONAL;
        mall.post(1621L, OLD);
        mall.customerComments(1621L, LATE_ANSWER);
        collectRoutine();

        Instant at = NOW; // 10:00 in Korea
        List<Long> rests = new java.util.ArrayList<>();
        int[] answers = {503, 503, 429};
        for (int i = 0; i < answers.length; i++) {
            clock.set(at);
            mall.failNext(Kind.BY_NUMBER, answers[i]);
            Outcome o = collectionEnded().orElseThrow();
            assertThat(o.stop()).isEqualTo(i == 2 ? Stop.RATE_LIMITED : Stop.CHANNEL_FAILED);
            assertThat(ledger().accountFailures(account)).isEqualTo(i + 1);
            Instant until = accountWaits.waitUntilOf(account).orElseThrow();
            rests.add(Duration.between(at, until).toMinutes());

            // A minute before the rest is over, after a restart: nothing goes out.
            clock.set(until.minusSeconds(60));
            long sent = mall.requests.size();
            assertThat(collectionEnded().orElseThrow().stop()).isEqualTo(Stop.ACCOUNT_WAIT);
            assertThat(mall.requests.size()).isEqualTo(sent);
            at = until;
        }
        assertThat(rests).as("the provisional rests: 2, 4, then 8 hours").containsExactly(120L, 240L, 480L);
        assertThat(at).as("10:00 + 2 h + 4 h + 8 h = midnight in Korea").isEqualTo(Instant.parse("2026-11-08T15:00:00Z"));
        assertThat(stored(1621L).getAnswerRecheckFailures()).as("the account's trouble is not the inquiry's").isZero();
        assertThat(stored(1621L).getCommentCheckedAt()).as("never a success").isEqualTo(COLLECTED);

        // It answers again, on the new Korean day's budget.
        clock.set(at);
        Outcome back = collectionEnded().orElseThrow();
        assertThat(back.stop()).isEqualTo(Stop.DONE);
        assertThat(back.noShopComment()).isEqualTo(1);
        assertThat(ledger().accountFailures(account)).isZero();
        assertThat(accountWaits.waitUntilOf(account)).isEmpty();
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 9))).isEqualTo(back.requests());

        // The next outage, a day later, rests the first length again.
        clock.set(at.plus(Duration.ofDays(1)));
        mall.failNext(Kind.BY_NUMBER, 503);
        assertThat(collectionEnded().orElseThrow().stop()).isEqualTo(Stop.CHANNEL_FAILED);
        assertThat(ledger().accountFailures(account)).isEqualTo(1);
        assertThat(accountWaits.waitUntilOf(account)).contains(at.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(120)));
    }

    @Test
    void aDayTheBudgetCutShortGoesOnTomorrowWithWhatItDidNotReach() {
        policy = Cafe24RecheckPolicy.PROVISIONAL; // 20 a day
        // Twenty: the routine sweep reads at most 20 comments, so every one starts from a successful look.
        LongStream.rangeClosed(1701L, 1720L).forEach(no -> {
            mall.post(no, OLD);
            mall.customerComments(no, LATE_ANSWER);
        });
        collectRoutine();
        clock.set(NOW);

        Outcome today = collectionEnded().orElseThrow();
        assertThat(today.requests()).isEqualTo(20);
        assertThat(today.stop()).isEqualTo(Stop.DAILY_LIMIT);
        assertThat(today.noShopComment()).as("token 1 + re-read 1 + discovery 1 + 17 reads").isEqualTo(17);
        assertThat(today.notReached()).isEqualTo(3);
        java.util.Set<UUID> seenToday = today.looks().stream().map(Cafe24AnswerRecheckRunner.Look::inquiryId)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(ledger().accountFailures(account)).as("a spent day is not the account's trouble").isZero();

        clock.set(NOW.plus(Duration.ofHours(4)));
        long sent = mall.requests.size();
        Outcome later = collectionEnded().orElseThrow();
        assertThat(later.stop()).isEqualTo(Stop.DAILY_LIMIT);
        assertThat(later.requests()).isZero();
        assertThat(later.checkedToday()).isEqualTo(17);
        assertThat(mall.requests.size()).isEqualTo(sent);

        clock.set(NOW.plus(Duration.ofDays(1)));
        Outcome tomorrow = collectionEnded().orElseThrow();
        List<UUID> notReached = LongStream.rangeClosed(1701L, 1720L).mapToObj(no -> stored(no).getId())
                .filter(id -> !seenToday.contains(id)).toList();
        assertThat(notReached).hasSize(3);
        assertThat(tomorrow.looks()).extracting(Cafe24AnswerRecheckRunner.Look::inquiryId)
                .as("what yesterday did not reach comes first").containsAll(notReached);
        assertThat(tomorrow.requests()).isEqualTo(20);
    }

    @Test
    void onlyACleanScheduledCafe24InquiryCollectionCallsForARecheck() {
        mall.post(1631L, OLD);
        collectRoutine();
        clock.set(NOW);
        mall.shopComments(1631L, LATE_ANSWER); // answered on the seller centre after the routine sweep last read it
        Cafe24AnswerRecheckAfterCollection followUp = new Cafe24AnswerRecheckAfterCollection(runner());
        long sent = mall.requests.size();

        List<com.sellerops.sync.SyncJob> notClean = List.of(
                scheduledJob(j -> j.setStatus("PARTIAL")),
                scheduledJob(j -> j.setStatus("FAILED")),
                scheduledJob(j -> j.setStatus("RUNNING")), // another run in flight was handed back, not finished
                scheduledJob(j -> j.setRateLimited(true)),
                scheduledJob(j -> j.setTrigger("MANUAL")),
                scheduledJob(j -> j.setTrigger("RETRY")),
                scheduledJob(j -> j.setDataType("REVIEW")),
                scheduledJob(j -> j.setJobType("MOCK_API")),
                // 2026-11-09: clean by its articles, but the comment look beside them was cut off by the account
                scheduledJob(j -> j.setAccountSideStop("AUTH_REJECTED")),
                scheduledJob(j -> j.setAccountSideStop("RATE_LIMITED")),
                scheduledJob(j -> j.setAccountSideStop("CHANNEL_UNAVAILABLE")),
                // and a job whose comment look is not known — read back, not the instance a finished run returned
                scheduledJob(j -> j.setAccountSideKnown(false)));
        for (com.sellerops.sync.SyncJob job : notClean) {
            assertThat(followUp.recheckAfter(job)).isEmpty();
            followUp.afterScheduledCollection(job);
        }
        assertThat(mall.requests.size()).as("nothing went out").isEqualTo(sent);
        assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isZero();
        assertThat(stored(1631L).getStatus()).isEqualTo("UNANSWERED");

        followUp.afterScheduledCollection(scheduledJob(j -> { }));
        assertThat(stored(1631L).getStatus()).isEqualTo("ANSWERED");
        assertThat(phaseOf(1631L)).isEqualTo(InquiryWorkItemPhase.COMPLETED);
    }

    /**
     * Two scheduled collections of one account ending at the same moment, on real threads — as two servers, or a slow
     * tick and the next. Committed rows, removed by the test itself ({@link #removeCommitted}).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoScheduledCollectionsEndingAtOnceRecheckTheAccountOnce() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            policy = Cafe24RecheckPolicy.PROVISIONAL;
            LongStream.rangeClosed(1641L, 1643L).forEach(no -> {
                mall.post(no, OLD);
                mall.customerComments(no, LATE_ANSWER);
            });
            collectRoutine();
            clock.set(NOW);
            long tokens = mall.count(Kind.TOKEN);
            long reReads = mall.count(Kind.BY_NUMBER);
            long commentReads = mall.count(Kind.COMMENTS);

            java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch goOn = new java.util.concurrent.CountDownLatch(1);
            mall.beforeEachRequest = kind -> {
                if (kind == Kind.BY_NUMBER && inside.getCount() > 0) {
                    inside.countDown();
                    try {
                        goOn.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            java.util.concurrent.Future<java.util.Optional<Outcome>> first = pool.submit(this::collectionEnded);
            assertThat(inside.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            Outcome second = pool.submit(this::collectionEnded).get(30, java.util.concurrent.TimeUnit.SECONDS)
                    .orElseThrow();
            goOn.countDown();
            Outcome firstDone = first.get(30, java.util.concurrent.TimeUnit.SECONDS).orElseThrow();

            assertThat(second.stop()).isEqualTo(Stop.BUSY);
            assertThat(second.requests()).isZero();
            assertThat(firstDone.stop()).isEqualTo(Stop.DONE);
            assertThat(firstDone.noShopComment()).isEqualTo(3);
            assertThat(mall.count(Kind.TOKEN) - tokens).isEqualTo(1);
            assertThat(mall.count(Kind.BY_NUMBER) - reReads).isEqualTo(1);
            assertThat(mall.count(Kind.COMMENTS) - commentReads).as("each article read once").isEqualTo(3);
            assertThat(ledger().accountFailures(account)).as("being busy is not the account's trouble").isZero();

            // A third collection ends later the same day: nothing is asked twice.
            mall.beforeEachRequest = null;
            clock.set(NOW.plus(Duration.ofHours(1)));
            Outcome third = collectionEnded().orElseThrow();
            assertThat(third.requests()).isZero();
            assertThat(third.checkedToday()).isEqualTo(3);
            assertThat(ledger().usedOn(account, java.time.LocalDate.of(2026, 11, 8))).isEqualTo(firstDone.requests());
        } finally {
            mall.beforeEachRequest = null;
            pool.shutdownNow();
            removeCommitted();
        }
    }

    // ---- 2026-11-09: a comment look the account cut off, and the wait belongs to whoever holds the account -------

    /** A recent inquiry, inside the routine window on {@link #NOW}, with a customer's comment for the look to read. */
    static final String RECENT = "2026-11-07T09:00:00+09:00";

    @Test
    void aCollectionWhoseCommentLookTheAccountCutOffSaysSoOnItsPageAndKeepsItsArticles() {
        clock.set(NOW);
        mall.post(1681L, RECENT);
        mall.customerComments(1681L, LATE_ANSWER);
        record Cut(Kind kind, int status, String stop) {
        }
        for (Cut cut : List.of(new Cut(Kind.COMMENTS, 401, "AUTH_REJECTED"), new Cut(Kind.COMMENTS, 429, "RATE_LIMITED"),
                new Cut(Kind.COMMENTS, 503, "CHANNEL_UNAVAILABLE"), new Cut(Kind.DISCOVERY, 401, "AUTH_REJECTED"),
                new Cut(Kind.DISCOVERY, 429, "RATE_LIMITED"), new Cut(Kind.DISCOVERY, 503, "DISCOVERY_FAILED"))) {
            mall.failNext(cut.kind(), cut.status());
            List<FetchPage> pages = routinePages();
            assertThat(pages).extracting(FetchPage::accountSideStop).as("%s %d", cut.kind(), cut.status())
                    .contains(cut.stop());
            assertThat(pages).as("the articles are kept").flatExtracting(FetchPage::records).hasSize(1);
            assertThat(pages).extracting(FetchPage::rateLimited).as("the sweep itself was not limited")
                    .containsOnly(false);
            assertThat(stored(1681L).getCommentCheck()).isEqualTo(Result.NOT_CHECKED);
        }

        // One article's own trouble is not the account's: nothing is carried.
        mall.brokenComments.add(1681L);
        assertThat(routinePages()).extracting(FetchPage::accountSideStop).containsOnlyNulls();
        mall.brokenComments.clear();
        assertThat(routinePages()).extracting(FetchPage::accountSideStop).containsOnlyNulls();
        assertThat(stored(1681L).getCommentCheck()).isEqualTo(Result.NO_SHOP_COMMENT);
    }

    @Test
    void onlyAFailureAboutTheAccountOrTheChannelCountsAsTheAccountsSide() {
        java.util.function.Function<Cafe24InquiryAnswerObserver.Reason, Cafe24InquiryAnswerObserver.Reason> alone =
                r -> Cafe24InquiryAnswerObserver.accountSideReason(
                        List.of(Cafe24InquiryAnswerObserver.CommentCheck.notChecked(r)));
        for (Cafe24InquiryAnswerObserver.Reason notTheAccounts : List.of(Cafe24InquiryAnswerObserver.Reason.READ_FAILED,
                Cafe24InquiryAnswerObserver.Reason.UNPROVEN, Cafe24InquiryAnswerObserver.Reason.CAPPED,
                Cafe24InquiryAnswerObserver.Reason.DISCOVERY_INCOMPLETE,
                Cafe24InquiryAnswerObserver.Reason.NOT_ATTEMPTED)) {
            assertThat(alone.apply(notTheAccounts)).as("%s", notTheAccounts).isNull();
        }
        for (Cafe24InquiryAnswerObserver.Reason theAccounts : List.of(Cafe24InquiryAnswerObserver.Reason.AUTH_REJECTED,
                Cafe24InquiryAnswerObserver.Reason.RATE_LIMITED, Cafe24InquiryAnswerObserver.Reason.CHANNEL_UNAVAILABLE,
                Cafe24InquiryAnswerObserver.Reason.DISCOVERY_FAILED, Cafe24InquiryAnswerObserver.Reason.UNEXPECTED,
                Cafe24InquiryAnswerObserver.Reason.UNSPECIFIED)) {
            assertThat(alone.apply(theAccounts)).as("%s", theAccounts).isEqualTo(theAccounts);
        }
        assertThat(Cafe24InquiryAnswerObserver.accountSideReason(List.of(
                new Cafe24InquiryAnswerObserver.CommentCheck(Cafe24InquiryAnswerObserver.CheckState.NO_SHOP_COMMENT, null),
                Cafe24InquiryAnswerObserver.CommentCheck.notChecked(Cafe24InquiryAnswerObserver.Reason.UNPROVEN),
                Cafe24InquiryAnswerObserver.CommentCheck.notChecked(Cafe24InquiryAnswerObserver.Reason.CHANNEL_UNAVAILABLE),
                Cafe24InquiryAnswerObserver.CommentCheck.notChecked(Cafe24InquiryAnswerObserver.Reason.AUTH_REJECTED))))
                .as("the gravest").isEqualTo(Cafe24InquiryAnswerObserver.Reason.AUTH_REJECTED);
        assertThat(Cafe24InquiryAnswerObserver.accountSideReason(List.of())).isNull();
    }

    @Test
    void aRunTakenOverWhileStalledDoesNotClearTheWaitTheRunThatTookOverRecorded() {
        policy = Cafe24RecheckPolicy.PROVISIONAL;
        mall.post(1671L, OLD); // no comments: the run's last request is its comment discovery
        collectRoutine();
        clock.set(NOW);
        Instant tookOverAt = NOW.plus(policy.runLease()).plusSeconds(1);
        java.util.concurrent.atomic.AtomicReference<Outcome> taker = new java.util.concurrent.atomic.AtomicReference<>();
        mall.beforeEachRequest = kind -> {
            if (kind == Kind.DISCOVERY && taker.get() == null) {
                // The first run stalls in its last request past its lease. A second run takes the account over, is
                // answered 429 and records the account's wait — all before the first run's request comes back.
                mall.beforeEachRequest = null;
                clock.set(tookOverAt);
                mall.failNext(Kind.BY_NUMBER, 429);
                taker.set(recheck(20));
            }
        };

        Outcome stalled = recheck(20);

        assertThat(taker.get().stop()).isEqualTo(Stop.RATE_LIMITED);
        assertThat(stalled.stop()).as("its last request answered; it ended as a run that reached the channel")
                .isEqualTo(Stop.DONE);
        assertThat(stalled.requests()).isPositive();
        assertThat(ledger().accountFailures(account)).as("the wait the run that took over recorded is kept").isEqualTo(1);
        assertThat(accountWaits.waitUntilOf(account)).contains(tookOverAt.plus(Duration.ofMinutes(120)));
        assertThat(lockHolder(account)).isEmpty();
    }

    @Test
    void aRunTakenOverWhileStalledDoesNotLengthenTheWaitTheRunThatTookOverRecorded() {
        policy = Cafe24RecheckPolicy.PROVISIONAL;
        mall.post(1672L, OLD);
        collectRoutine();
        clock.set(NOW);
        mall.failNext(Kind.DISCOVERY, 401); // what the first run's stalled last request will be answered
        Instant tookOverAt = NOW.plus(policy.runLease()).plusSeconds(1);
        java.util.concurrent.atomic.AtomicReference<Outcome> taker = new java.util.concurrent.atomic.AtomicReference<>();
        mall.beforeEachRequest = kind -> {
            if (kind == Kind.DISCOVERY && taker.get() == null) {
                mall.beforeEachRequest = null;
                clock.set(tookOverAt);
                mall.failNext(Kind.BY_NUMBER, 429);
                taker.set(recheck(20));
            }
        };

        Outcome stalled = recheck(20);

        assertThat(taker.get().stop()).isEqualTo(Stop.RATE_LIMITED);
        assertThat(stalled.stop()).isEqualTo(Stop.AUTH_REJECTED);
        assertThat(ledger().accountFailures(account)).as("not one more in a row from a run that no longer holds it")
                .isEqualTo(1);
        assertThat(accountWaits.waitUntilOf(account)).contains(tookOverAt.plus(Duration.ofMinutes(120)));
        assertThat(jdbcTemplate.queryForObject("select last_stop from inquiry_recheck_account_wait "
                + "where seller_account_id = ?", String.class, account)).isEqualTo("RATE_LIMITED");
        assertThat(lockHolder(account)).isEmpty();
    }

    @Test
    void onlyTheRunThatHoldsTheAccountMovesItsWait() {
        com.sellerops.inquiry.recheck.InquiryRecheckLedger ledger = ledger();
        java.util.function.IntFunction<Duration> hours = n -> Duration.ofHours(n);
        Duration length = Duration.ofMinutes(10);
        clock.set(NOW);

        // A lease that was never taken opens nothing.
        var never = new com.sellerops.inquiry.recheck.InquiryRecheckLedger.Lease(account, UUID.randomUUID(), length);
        assertThat(ledger.recordAccountFailure(never, "CHANNEL_FAILED", hours)).isZero();
        assertThat(accountWaits.waitUntilOf(account)).isEmpty();

        var first = ledger.acquire(account, length).orElseThrow();
        assertThat(ledger.recordAccountFailure(first, "CHANNEL_FAILED", hours)).isEqualTo(1);

        // Its lease runs out, nobody has taken over yet: it no longer holds the account, so it moves nothing.
        clock.set(NOW.plus(length));
        assertThat(ledger.recordAccountFailure(first, "CHANNEL_FAILED", hours)).isZero();
        assertThat(ledger.clearAccountWait(first)).isFalse();
        assertThat(ledger.accountFailures(account)).isEqualTo(1);

        var second = ledger.acquire(account, length).orElseThrow();
        assertThat(ledger.recordAccountFailure(second, "RATE_LIMITED", hours)).isEqualTo(2);
        Instant until = accountWaits.waitUntilOf(account).orElseThrow();
        assertThat(until).isEqualTo(NOW.plus(length).plus(Duration.ofHours(2)));

        // The first run comes back while the second holds the account.
        assertThat(ledger.recordAccountFailure(first, "AUTH_REJECTED", hours)).isZero();
        assertThat(ledger.clearAccountWait(first)).isFalse();
        // Another account's lock is no key to this one.
        var elsewhere = ledger.acquire(otherAccount, length).orElseThrow();
        var wrongKey = new com.sellerops.inquiry.recheck.InquiryRecheckLedger.Lease(account, elsewhere.holder(), length);
        assertThat(ledger.clearAccountWait(wrongKey)).isFalse();
        assertThat(ledger.recordAccountFailure(wrongKey, "AUTH_REJECTED", hours)).isZero();
        assertThat(ledger.accountFailures(account)).isEqualTo(2);
        assertThat(accountWaits.waitUntilOf(account)).contains(until);

        assertThat(ledger.clearAccountWait(second)).as("the holder can").isTrue();
        assertThat(ledger.accountFailures(account)).isZero();
        assertThat(ledger.clearAccountWait(second)).as("nothing left to clear, still the holder").isTrue();
        ledger.release(second);
        ledger.release(elsewhere);
    }

    /** The routine sweep's pages, ingested as {@link #collectRoutine} does, kept for what they say beside the rows. */
    List<FetchPage> routinePages() {
        RoutingHttp http = new RoutingHttp();
        Cafe24ApiConnector connector = new Cafe24ApiConnector(authorizer, new Cafe24OrdersClient(http),
                new Cafe24BoardArticlesClient(http), null,
                new Cafe24InquiryAnswerObserver(new Cafe24BoardArticlesClient(http), new Cafe24BoardCommentsClient(http)),
                clock);
        List<FetchPage> pages = new java.util.ArrayList<>();
        String cursor = null;
        do {
            FetchPage page = connector.fetch(new FetchRequest(org, account, "CAFE24", DataType.INQUIRY, cursor, 50));
            pages.add(page);
            @SuppressWarnings("unchecked")
            List<CanonicalInquiry> rows = (List<CanonicalInquiry>) page.records();
            ingestion.ingestInquiries(org, channel, account, rows);
            cursor = page.hasMore() ? page.nextCursorValue() : null;
        } while (cursor != null);
        return pages;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /**
     * For a test that commits: delete every row of this test's orgs and accounts, then prove none is left — counted again
     * over every table that has an {@code org_id} or a {@code seller_account_id} column, so a table this list forgot shows.
     */
    void removeCommitted() {
        List<UUID> orgs = List.of(org, otherOrg);
        List<UUID> accounts = List.of(account, otherAccount);
        for (UUID o : orgs) {
            jdbcTemplate.update("delete from inquiry_work_item_audit where work_item_id in "
                    + "(select id from inquiry_work_item where org_id = ?)", o);
            jdbcTemplate.update("delete from inquiry_work_item where org_id = ?", o);
        }
        for (UUID a : accounts) {
            jdbcTemplate.update("delete from inquiry_recheck_daily_usage where seller_account_id = ?", a);
            jdbcTemplate.update("delete from inquiry_recheck_run_lock where seller_account_id = ?", a);
            jdbcTemplate.update("delete from inquiry_recheck_account_wait where seller_account_id = ?", a);
        }
        for (UUID o : orgs) {
            jdbcTemplate.update("delete from inquiries where org_id = ?", o);
            jdbcTemplate.update("delete from connector_credentials where org_id = ?", o);
        }
        dropTenants();
        List<String> left = new java.util.ArrayList<>();
        for (String column : List.of("org_id", "seller_account_id")) {
            for (String table : jdbcTemplate.queryForList("select table_name from information_schema.columns "
                    + "where column_name = ? and lower(table_schema) = 'public'", String.class, column)) {
                for (UUID id : column.equals("org_id") ? orgs : accounts) {
                    Integer n = jdbcTemplate.queryForObject(
                            "select count(*) from " + table + " where " + column + " = ?", Integer.class, id);
                    if (n != null && n > 0) {
                        left.add(table + "." + column + "=" + n);
                    }
                }
            }
        }
        assertThat(left).as("committed rows left behind").isEmpty();
    }

    /** Move the clock to the moment this account's rest after an account-side stop is over (V186). */
    void pastAccountWait() {
        clock.set(accountWaits.waitUntilOf(account).orElseThrow());
    }

    /** Who holds the account now, read with SQL — the JPA cache does not see a bulk delete. */
    java.util.Optional<UUID> lockHolder(UUID sellerAccountId) {
        return jdbcTemplate.queryForList("select holder from inquiry_recheck_run_lock where seller_account_id = ?",
                UUID.class, sellerAccountId).stream().findFirst();
    }

    /** A scheduled Cafe24 INQUIRY collection of this account that just finished cleanly — changed by {@code edit}. */
    com.sellerops.sync.SyncJob scheduledJob(java.util.function.Consumer<com.sellerops.sync.SyncJob> edit) {
        com.sellerops.sync.SyncJob job = new com.sellerops.sync.SyncJob();
        job.setOrgId(org);
        job.setSellerAccountId(account);
        job.setChannelId(channel);
        job.setDataType("INQUIRY");
        job.setJobType(Cafe24ApiConnector.KIND);
        job.setTrigger("SCHEDULED");
        job.setStatus("SUCCESS");
        job.setAccountSideKnown(true); // as the instance a finished run returns
        edit.accept(job);
        return job;
    }

    /** The end of a clean scheduled collection, on a fresh runner and ledger — as after a restart. */
    java.util.Optional<Outcome> collectionEnded() {
        return new Cafe24AnswerRecheckAfterCollection(runner()).recheckAfter(scheduledJob(j -> { }));
    }

    Outcome recheck(int limit) {
        return runner().run(org, account, channel, limit);
    }

    Cafe24AnswerRecheckRunner runner() {
        return new Cafe24AnswerRecheckRunner(new RoutingHttp(), authorizer, inquiries, workItems, executions, ingestion,
                ledger(), policy, clock);
    }

    com.sellerops.inquiry.recheck.InquiryRecheckLedger ledger() {
        return new com.sellerops.inquiry.recheck.InquiryRecheckLedger(dailyUsage, runLocks, accountWaits, clock);
    }

    void collectRoutine() {
        collectRoutineFor(org, account, mall);
    }

    /** The routine sweep, as the scheduler runs it: no cursor → today's fortnight, with the comment lane. */
    void collectRoutineFor(UUID orgId, UUID accountId, FakeCafe24Board board) {
        RoutingHttp http = new RoutingHttp();
        Cafe24ApiConnector connector = new Cafe24ApiConnector(authorizer, new Cafe24OrdersClient(http),
                new Cafe24BoardArticlesClient(http), null,
                new Cafe24InquiryAnswerObserver(new Cafe24BoardArticlesClient(http), new Cafe24BoardCommentsClient(http)),
                clock);
        String cursor = null;
        do {
            FetchPage page = connector.fetch(new FetchRequest(orgId, accountId, "CAFE24", DataType.INQUIRY, cursor, 50));
            @SuppressWarnings("unchecked")
            List<CanonicalInquiry> rows = (List<CanonicalInquiry>) page.records();
            ingestion.ingestInquiries(orgId, channel, accountId, rows);
            cursor = page.hasMore() ? page.nextCursorValue() : null;
        } while (cursor != null);
    }

    Inquiry stored(long no) {
        return storedFor(org, no);
    }

    Inquiry storedFor(UUID orgId, long no) {
        return inquiries.findByOrgIdAndChannelIdAndExternalId(orgId, channel,
                Cafe24InquiryArticleMapper.externalId(6, no)).orElseThrow();
    }

    InquiryWorkItemPhase phaseOf(long no) {
        return workItems.findByInquiryId(stored(no).getId()).map(InquiryWorkItem::getPhase).orElse(null);
    }

    void setPhase(long no, InquiryWorkItemPhase phase) {
        InquiryWorkItem wi = workItems.findByInquiryId(stored(no).getId()).orElseThrow();
        wi.setPhase(phase);
        workItems.save(wi);
    }

    /** Sends each request to the mall its URL names — two sellers, two malls, one transport. */
    final class RoutingHttp implements Cafe24HttpClient {
        private FakeCafe24Board of(java.net.URI uri) {
            return uri.getHost().startsWith("othermall.") ? otherMall : mall;
        }

        @Override
        public Response postForm(java.net.URI uri, Map<String, String> headers, Map<String, String> form) {
            return of(uri).postForm(uri, headers, form);
        }

        @Override
        public Response get(java.net.URI uri, Map<String, String> headers) {
            return of(uri).get(uri, headers);
        }
    }

    static final class SettableClock extends Clock {
        private volatile Instant now;

        SettableClock(Instant start) {
            now = start;
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            SettableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
