package com.sellerops.vendor.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.TestTotp;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.review.ExportWorkbooks;
import com.sellerops.vendor.review.NaverReviewExport;
import com.sellerops.vendor.review.StoreReviews;
import com.sellerops.vendor.store.CheckedStores;
import com.sellerops.vendor.store.Stores;
import com.sellerops.vendor.task.ReplyTasks;
import com.sellerops.vendor.task.ReplyTasks.Failure;
import com.sellerops.vendor.task.ReplyTasks.Meta;
import com.sellerops.vendor.task.ReplyTasks.Outcome;
import com.sellerops.vendor.task.ReplyTasks.ReportMethod;
import com.sellerops.vendor.task.ReplyTasks.State;
import com.sellerops.vendor.task.ReplyTasks.TaskException;
import com.sellerops.vendor.task.ReplyTasks.TaskView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * <b>What the server holds is deleted on the day its retention ends, not a day before, and stays deleted</b> —
 * contract §6, §10-5.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetentionTest {

    private static final String PASSWORD = "a long enough password";
    private static final Duration NINETY_DAYS = Duration.ofDays(90);
    private static final Duration SECOND = Duration.ofSeconds(1);

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private SellerAccounts accounts;
    @Autowired
    private Delegates delegates;

    private MutableClock clock;
    private Retention retention;
    private StoreReviews reviews;
    private ReplyTasks tasks;
    private UUID account;
    private UUID store;
    private UUID me;
    private UUID install;
    private int commands;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-20T03:00:00Z"));
        retention = new Retention(jdbc, transactions, clock);
        reviews = new StoreReviews(jdbc, clock);
        tasks = new ReplyTasks(jdbc, clock);
        Stores stores = new Stores(jdbc, clock);
        account = accounts.redeem(accounts.issueInvite("s-" + UUID.randomUUID() + "@example.com"), PASSWORD);
        store = stores.add(account, Stores.Channel.NAVER, "보관 스토어");
        me = delegate("d-" + UUID.randomUUID() + "@example.com");
        stores.assign(store, me);
        CheckedStores.checked(jdbc, store, me);
        install = UUID.randomUUID();
        jdbc.sql("insert into installs (id, account_id, token_hash, public_key, device_name, created_at, expires_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)")
                .params(install, account, Codes.hash(install.toString()), new byte[32], "Seller Mac",
                        Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plus(Duration.ofDays(3650))))
                .update();
        upload(ExportWorkbooks.line("1", Map.of()), ExportWorkbooks.line("2", Map.of()),
                ExportWorkbooks.line("3", Map.of()), ExportWorkbooks.line("4", Map.of()));
        reviews.ack(store, install, reviews.feed(store, 0, 100).cursor());
    }

    @Test
    void aNoReplyReviewGoesNinetyDaysAfterTheLaterOfSavedAndJudged() {
        UUID one = review("1");
        clock.advance(Duration.ofDays(1));
        reviews.markNoReplyNeeded(store, one);
        Instant deadline = clock.instant().plus(NINETY_DAYS);

        clock.advance(NINETY_DAYS.minus(SECOND));
        retention.purge();
        assertThat(reviews.idOf(store, "1")).as("a second before").isPresent();

        clock.advance(SECOND);
        assertThat(clock.instant()).isEqualTo(deadline);
        retention.purge();
        assertThat(reviews.idOf(store, "1")).as("on the day").isEmpty();
        assertThat(count("store_review_events where review_id = ?", one)).isZero();
        assertThat(reviews.feed(store, 0, 100).events()).extracting(e -> e.review().reviewNo())
                .containsExactly("2", "3", "4");
    }

    @Test
    void aReviewNobodyDecidedAboutIsWorkInProgressAndStays() {
        clock.advance(Duration.ofDays(3650));
        retention.purge();
        assertThat(List.of("1", "2", "3", "4")).allSatisfy(no -> assertThat(reviews.idOf(store, no)).isPresent());
    }

    @Test
    void aCompletedTaskAndItsReviewGoNinetyDaysAfterTheTaskEnded() {
        TaskView approved = approve(submit("2", "감사합니다."));
        tasks.claim(approved.id(), me, approved.stateVersion());
        clock.advance(Duration.ofDays(2));
        TaskView posted = tasks.report(approved.id(), me, Outcome.POSTED, approved.approvedFingerprint(),
                clock.instant(), ReportMethod.SELLER_CENTER_SCREEN, command());
        assertThat(posted.state()).isEqualTo(State.POSTED);
        assertThat(count("task_commands where task_id = ?", approved.id())).isPositive();

        clock.advance(NINETY_DAYS.minus(SECOND));
        retention.purge();
        assertThat(tasks.get(approved.id())).as("a second before").isPresent();

        clock.advance(SECOND);
        Retention.Purged purged = retention.purge();
        assertThat(purged.reviews()).isPositive();
        assertThat(tasks.get(approved.id())).isEmpty();
        assertThat(reviews.idOf(store, "2")).isEmpty();
        assertThat(count("task_commands where task_id = ?", approved.id())).isZero();
        assertThat(count("reply_task_changes where task_id = ?", approved.id())).isZero();
        assertThat(tasks.changes(store, 0, 100).changes()).as("a PC syncing from the start still can").isEmpty();
    }

    @Test
    void anInvalidatedTaskEndsWhenTheReviewChangesAndCountsFromThen() throws Exception {
        TaskView waiting = submit("3", "초안");
        clock.advance(Duration.ofDays(5));
        upload(ExportWorkbooks.line("3", Map.of("리뷰상세내용", "고쳐 쓴 리뷰")));
        assertThat(tasks.get(waiting.id()).orElseThrow().state()).isEqualTo(State.INVALIDATED);
        Instant ended = clock.instant();

        clock.advance(NINETY_DAYS.minus(SECOND));
        retention.purge();
        assertThat(reviews.idOf(store, "3")).isPresent();
        clock.advance(SECOND);
        retention.purge();
        assertThat(reviews.idOf(store, "3")).isEmpty();
        assertThat(clock.instant()).isEqualTo(ended.plus(NINETY_DAYS));
    }

    @Test
    void runningWorkIsNeverDeletedWhateverElseIsDue() {
        UUID four = review("4");
        reviews.markNoReplyNeeded(store, four);
        TaskView open = submit("4", "그래도 답글을 쓰기로 함");
        clock.advance(Duration.ofDays(3650));
        retention.purge();
        assertThat(reviews.idOf(store, "4")).isPresent();
        assertThat(tasks.get(open.id())).isPresent();
    }

    @Test
    void whenBothRulesApplyTheLaterOneWins() {
        TaskView cancelled = tasks.cancel(submit("1", "초안").id(), command());
        assertThat(cancelled.state()).isEqualTo(State.CANCELLED);
        clock.advance(Duration.ofDays(60));
        reviews.markNoReplyNeeded(store, review("1"));

        clock.advance(Duration.ofDays(31));
        retention.purge();
        assertThat(reviews.idOf(store, "1")).as("the task's 90 days are over, the no-reply 90 are not").isPresent();

        clock.advance(Duration.ofDays(59));
        retention.purge();
        assertThat(reviews.idOf(store, "1")).isEmpty();
    }

    @Test
    void aDeletedReviewIsNotCollectedAgainAndALateNoReplyIsTakenAsDone() throws Exception {
        UUID one = review("1");
        reviews.markNoReplyNeeded(store, one);
        long cursor = reviews.feed(store, 0, 100).cursor();
        clock.advance(NINETY_DAYS);
        retention.purge();
        assertThat(reviews.idOf(store, "1")).isEmpty();
        assertThat(jdbc.sql("select review_no_hash from purged_reviews where review_id = ?").param(one)
                .query(String.class).single()).as("the number is kept only as its hash")
                .isEqualTo(Codes.hash("1")).isNotEqualTo("1");

        StoreReviews.Imported again = reviews.importExport(store, me, NaverReviewExport.read(ExportWorkbooks.workbook(
                List.of(ExportWorkbooks.line("1", Map.of("리뷰상세내용", "다시 실린 리뷰")),
                        ExportWorkbooks.line("5", Map.of())))));
        assertThat(again).extracting(StoreReviews.Imported::added, StoreReviews.Imported::unchanged,
                StoreReviews.Imported::purged).containsExactly(1, 1, 1);
        assertThat(reviews.idOf(store, "1")).isEmpty();
        assertThat(reviews.feed(store, cursor, 100).events()).extracting(e -> e.review().reviewNo())
                .containsExactly("5");

        assertThat(reviews.markNoReplyNeeded(store, one)).as("a PC deciding late is not refused forever").isTrue();
        assertThat(reviews.markNoReplyNeeded(store, UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> tasks.submitDraft(store, one, "늦은 초안",
                new Meta(Codes.hash("x"), clock.instant(), "k1", "p1", "v1")))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.REVIEW_PURGED));
    }

    @Test
    void expiredInvitesAndLoggedOutSessionsGo() {
        String sellerOld = Codes.newCode();
        String sellerLive = Codes.newCode();
        String delegateOld = Codes.newCode();
        String sessionOld = "s-" + UUID.randomUUID();
        String sessionLive = "s-" + UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("insert into seller_invites (code_hash, email, expires_at) values (?, ?, ?), (?, ?, ?)")
                .params(Codes.hash(sellerOld), "old@example.com", Timestamp.from(now.minus(SECOND)),
                        Codes.hash(sellerLive), "live@example.com", Timestamp.from(now.plus(Duration.ofDays(1))))
                .update();
        jdbc.sql("insert into delegate_invites (code_hash, email, role, expires_at, staged_password) "
                        + "values (?, ?, ?, ?, ?)")
                .params(Codes.hash(delegateOld), "staged@example.com", "DELEGATE", Timestamp.from(now.minus(SECOND)),
                        "{bcrypt}staged")
                .update();
        jdbc.sql("insert into revoked_sessions (jti, expires_at) values (?, ?), (?, ?)")
                .params(sessionOld, Timestamp.from(now.minus(SECOND)), sessionLive,
                        Timestamp.from(now.plus(Duration.ofHours(1))))
                .update();

        UUID codeOld = UUID.randomUUID();
        UUID codeYoung = UUID.randomUUID();
        jdbc.sql("insert into step_up_codes (id, account_id, session_id, purpose, target_hash, code_hash, issued_at, "
                        + "expires_at) values (?, ?, 's', 'PC_LINK', ?, ?, ?, ?), (?, ?, 's', 'PC_LINK', ?, ?, ?, ?)")
                .params(codeOld, account, Codes.hash("a"), Codes.hash("b"),
                        Timestamp.from(now.minus(Duration.ofDays(1)).minus(SECOND)), Timestamp.from(now),
                        codeYoung, account, Codes.hash("a"), Codes.hash("c"),
                        Timestamp.from(now.minus(Duration.ofHours(23))), Timestamp.from(now))
                .update();

        assertThat(retention.purge().stepUps()).isEqualTo(1);
        assertThat(count("step_up_codes where id = ?", codeOld)).isZero();
        assertThat(count("step_up_codes where id = ?", codeYoung)).as("still inside a cap's look-back").isOne();
        assertThat(count("seller_invites where code_hash = ?", Codes.hash(sellerOld))).isZero();
        assertThat(count("seller_invites where code_hash = ?", Codes.hash(sellerLive))).isOne();
        assertThat(count("delegate_invites where code_hash = ?", Codes.hash(delegateOld))).isZero();
        assertThat(count("revoked_sessions where jti = ?", sessionOld)).isZero();
        assertThat(count("revoked_sessions where jti = ?", sessionLive)).as("still refused until it expires")
                .isOne();
    }

    /**
     * §10-5: rows that come back with a restored backup are deleted again by the next pass, because the dates that
     * made them due came back with them. The "backup" here is a copy of this store's rows taken before the deletion,
     * written back afterwards — the PostgreSQL dump/restore round trip is proved against a real server.
     */
    @Test
    void whatARestoredBackupBringsBackIsDeletedAgain() {
        reviews.markNoReplyNeeded(store, review("1"));
        TaskView cancelled = tasks.cancel(submit("2", "초안").id(), command());
        Map<String, List<Map<String, Object>>> backup = backup();

        clock.advance(NINETY_DAYS);
        retention.purge();
        assertThat(reviews.idOf(store, "1")).isEmpty();
        assertThat(reviews.idOf(store, "2")).isEmpty();

        restore(backup);
        assertThat(reviews.idOf(store, "1")).as("the backup brought them back").isPresent();
        assertThat(tasks.get(cancelled.id())).isPresent();

        retention.purge();
        assertThat(reviews.idOf(store, "1")).isEmpty();
        assertThat(reviews.idOf(store, "2")).isEmpty();
        assertThat(tasks.get(cancelled.id())).isEmpty();
        assertThat(reviews.idOf(store, "3")).as("work in progress came back and stays").isPresent();
        assertThat(count("purged_reviews where store_id = ?", store)).isEqualTo(2);
    }

    @Test
    void thePassRunsBeforeTheWebServerOpensButNotInAnOperatorCommand() {
        Retention web = mock(Retention.class);
        new PurgeSchedule(web, mock(WebServerApplicationContext.class)).afterSingletonsInstantiated();
        verify(web).purge();

        Retention command = mock(Retention.class);
        new PurgeSchedule(command, mock(ApplicationContext.class)).afterSingletonsInstantiated();
        verify(command, never()).purge();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Parents first, so the copy goes back in the order its references need. */
    private static final List<String> BACKED_UP = List.of("store_reviews", "store_review_events", "reply_tasks",
            "reply_task_changes", "task_commands");

    private Map<String, List<Map<String, Object>>> backup() {
        Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        for (String table : BACKED_UP) {
            String where = table.equals("task_commands")
                    ? " where task_id in (select id from reply_tasks where store_id = ?)" : " where store_id = ?";
            copy.put(table, jdbc.sql("select * from " + table + where).param(store).query().listOfRows());
        }
        // The backup was taken before anything was deleted: no deletion record of this store in it either.
        copy.put("purged_reviews", List.of());
        return copy;
    }

    /** A restore replaces what is there: this store's rows as they are now go first, children before parents. */
    private void restore(Map<String, List<Map<String, Object>>> backup) {
        jdbc.sql("delete from task_commands where task_id in (select id from reply_tasks where store_id = ?)")
                .param(store).update();
        for (String table : List.of("reply_task_changes", "reply_tasks", "store_review_events", "store_reviews",
                "purged_reviews")) {
            jdbc.sql("delete from " + table + " where store_id = ?").param(store).update();
        }
        for (Map.Entry<String, List<Map<String, Object>>> table : backup.entrySet()) {
            for (Map<String, Object> row : table.getValue()) {
                List<String> columns = new ArrayList<>(row.keySet());
                jdbc.sql("insert into " + table.getKey() + " (" + String.join(", ", columns) + ") values ("
                                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")")
                        .params(columns.stream().map(row::get).toList())
                        .update();
            }
        }
    }

    private TaskView submit(String reviewNo, String body) {
        String fingerprint = jdbc.sql("select body_fingerprint from store_reviews where store_id = ? and review_no = ?")
                .params(store, reviewNo).query(String.class).single();
        return tasks.submitDraft(store, review(reviewNo), body,
                new Meta(fingerprint, clock.instant(), "k1", "p1", "v1"));
    }

    private TaskView approve(TaskView draft) {
        return tasks.approve(draft.id(), account, draft.draftVersion(), draft.draftFingerprint(), command());
    }

    private String command() {
        return "ret-" + UUID.randomUUID() + "-" + (commands++);
    }

    private UUID review(String reviewNo) {
        return reviews.idOf(store, reviewNo).orElseThrow();
    }

    private int count(String fromWhere, Object param) {
        return jdbc.sql("select count(*) from " + fromWhere).param(param).query(Integer.class).single();
    }

    @SafeVarargs
    private void upload(Map<String, String>... lines) throws Exception {
        reviews.importExport(store, me, NaverReviewExport.read(ExportWorkbooks.workbook(List.of(lines))));
    }

    private UUID delegate(String email) {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        Delegates.Staged staged = delegates.stage(invite, PASSWORD);
        return delegates.confirm(invite, TestTotp.code(staged.secret(), Instant.now(), 0)).delegateId();
    }
}
