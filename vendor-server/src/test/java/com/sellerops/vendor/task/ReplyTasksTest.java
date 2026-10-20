package com.sellerops.vendor.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.TestTotp;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.review.ExportWorkbooks;
import com.sellerops.vendor.review.NaverReviewExport;
import com.sellerops.vendor.review.StoreReviews;
import com.sellerops.vendor.store.Stores;
import com.sellerops.vendor.task.ReplyTasks.Failure;
import com.sellerops.vendor.task.ReplyTasks.Meta;
import com.sellerops.vendor.task.ReplyTasks.Outcome;
import com.sellerops.vendor.task.ReplyTasks.ReportMethod;
import com.sellerops.vendor.task.ReplyTasks.State;
import com.sellerops.vendor.task.ReplyTasks.TaskException;
import com.sellerops.vendor.task.ReplyTasks.TaskView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** <b>A reply is posted only as approved, by one delegate, and every other path ends visibly</b> — contract §4. */
@SpringBootTest
@ActiveProfiles("test")
class ReplyTasksTest {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SellerAccounts accounts;
    @Autowired
    private Delegates delegates;
    @Autowired
    private ReplyTasks transactional;

    private MutableClock clock;
    private ReplyTasks tasks;
    private StoreReviews reviews;
    private Stores stores;
    private UUID account;
    private UUID store;
    private UUID me;
    private UUID other;
    private String myEmail;
    private int commands;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-20T03:00:00Z"));
        tasks = new ReplyTasks(jdbc, clock);
        reviews = new StoreReviews(jdbc, clock);
        stores = new Stores(jdbc, clock);
        account = accounts.redeem(accounts.issueInvite("s-" + UUID.randomUUID() + "@example.com"), PASSWORD);
        store = stores.add(account, Stores.Channel.NAVER, "데모 스토어");
        myEmail = "d-" + UUID.randomUUID() + "@example.com";
        me = delegate(myEmail);
        other = delegate("o-" + myEmail);
        stores.assign(store, me);
        stores.assign(store, other);
        upload(ExportWorkbooks.line("1", Map.of()), ExportWorkbooks.line("2", Map.of()));
    }

    @Test
    void aDraftIsApprovedClaimedPostedAndTheReviewIsThenClosed() {
        TaskView draft = submit("1", "소중한 리뷰 감사합니다.");
        assertThat(draft.state()).isEqualTo(State.AWAITING_APPROVAL);
        assertThat(draft.draftVersion()).isEqualTo(1);
        assertThat(draft.draftFingerprint()).isEqualTo(ReplyText.fingerprint("소중한 리뷰 감사합니다."));
        assertThat(draft.review()).as("the phone approves from the task alone").satisfies(r -> {
            assertThat(r.body()).isEqualTo("합성 리뷰 1");
            assertThat(r.rating()).isEqualTo(5);
            assertThat(r.productName()).isEqualTo("합성 상품");
            assertThat(r.displayState()).isEqualTo("노출");
            assertThat(r.replied()).isFalse();
        });

        TaskView approved = approve(draft);
        assertThat(approved.state()).isEqualTo(State.APPROVED);
        assertThat(approved.approvedFingerprint()).isEqualTo(draft.draftFingerprint());
        assertThat(tasks.forDelegate(me, State.APPROVED)).singleElement().satisfies(t -> {
            assertThat(t.approvedBody()).isEqualTo("소중한 리뷰 감사합니다.");
            assertThat(t.reviewBody()).isEqualTo("합성 리뷰 1");
            assertThat(t.storeLabel()).isEqualTo("데모 스토어");
        });

        TaskView claimed = tasks.claim(draft.id(), me, approved.stateVersion());
        assertThat(claimed.state()).isEqualTo(State.CLAIMED);
        assertThat(tasks.forDelegate(other, State.CLAIMED)).as("only the claimer lists it").isEmpty();
        assertThat(tasks.preflight(draft.id(), me).clearToPost()).isTrue();

        TaskView posted = report(draft.id(), me, Outcome.POSTED, approved.approvedFingerprint());
        assertThat(posted.state()).isEqualTo(State.POSTED);
        assertThat(posted.outcome()).isEqualTo(Outcome.POSTED);
        assertThatThrownBy(() -> submit("1", "한 번 더")).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.failure()).isEqualTo(Failure.ALREADY_POSTED));
        assertThat(tasks.changes(store, 0, 100).changes()).extracting(ReplyTasks.Change::state)
                .containsExactly(State.AWAITING_APPROVAL, State.APPROVED, State.CLAIMED, State.POSTED);
    }

    @Test
    void anApprovalBindsToOneExactVersion() {
        TaskView v1 = submit("1", "첫 초안");
        assertThat(submit("1", "  첫 초안 \r\n").draftVersion()).as("the same text again changes nothing").isEqualTo(1);
        TaskView v2 = submit("1", "고친 초안");
        assertThat(v2.draftVersion()).isEqualTo(2);
        assertThatThrownBy(() -> tasks.approve(v1.id(), account, 1, v1.draftFingerprint(), command()))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.failure()).isEqualTo(Failure.STALE));
        assertThatThrownBy(() -> tasks.approve(v2.id(), account, 2, v1.draftFingerprint(), command()))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.FINGERPRINT_MISMATCH));

        TaskView edited = tasks.editDraft(v2.id(), 2, "휴대폰에서 고친 초안", true);
        assertThat(edited.draftVersion()).isEqualTo(3);
        assertThat(edited.editedOnMobile()).isTrue();
        assertThatThrownBy(() -> tasks.editDraft(v2.id(), 2, "늦은 수정", true))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.failure()).isEqualTo(Failure.STALE));
        assertThat(tasks.recordPcRecheck(v2.id(), 3, false, List.of("UNSUPPORTED_CLAIM")).pcRecheck())
                .isEqualTo("FLAGGED:UNSUPPORTED_CLAIM");

        TaskView approved = approve(edited);
        assertThatThrownBy(() -> submit("1", "승인 뒤 새 초안")).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.failure()).isEqualTo(Failure.TASK_IN_PROGRESS));
        assertThatThrownBy(() -> tasks.editDraft(v2.id(), 3, "승인 뒤 수정", true))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.WRONG_STATE));
        claimAndExpectMismatch(approved);
    }

    @Test
    void aSentTwiceCommandHappensOnceAndACommandIdIsNotReused() {
        TaskView draft = submit("1", "초안");
        String command = command();
        TaskView once = tasks.approve(draft.id(), account, 1, draft.draftFingerprint(), command);
        TaskView twice = tasks.approve(draft.id(), account, 1, draft.draftFingerprint(), command);
        assertThat(twice.stateVersion()).isEqualTo(once.stateVersion());
        assertThatThrownBy(() -> tasks.cancel(draft.id(), command)).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.failure()).isEqualTo(Failure.COMMAND_REUSED));
    }

    @Test
    void cancellingBeforeAClaimEndsItAndAfterOneAsksTheDelegate() {
        TaskView early = approve(submit("1", "초안 1"));
        assertThat(tasks.cancel(early.id(), command()).state()).isEqualTo(State.CANCELLED);
        assertThat(submit("1", "다시 쓴 초안").state()).as("a cancelled task frees its review")
                .isEqualTo(State.AWAITING_APPROVAL);

        TaskView late = approve(submit("2", "초안 2"));
        tasks.claim(late.id(), me, late.stateVersion());
        assertThat(tasks.cancel(late.id(), command()).state()).isEqualTo(State.CANCEL_REQUESTED);
        ReplyTasks.Preflight look = tasks.preflight(late.id(), me);
        assertThat(look.cancelRequested()).isTrue();
        assertThat(look.clearToPost()).isFalse();
        assertThat(report(late.id(), me, Outcome.FAILED, null).state()).as("not posted → cancelled")
                .isEqualTo(State.CANCELLED);
    }

    @Test
    void aCancelThatArrivesAfterThePostingSettlesAsPosted() {
        TaskView task = approve(submit("1", "초안"));
        tasks.claim(task.id(), me, task.stateVersion());
        tasks.cancel(task.id(), command());
        assertThat(report(task.id(), me, Outcome.POSTED, task.approvedFingerprint()).state())
                .isEqualTo(State.POSTED);
    }

    @Test
    void aChangedOrAnsweredReviewInvalidatesUnclaimedTasksAndWarnsAClaimedOne() throws Exception {
        TaskView waiting = submit("1", "초안 1");
        TaskView claimed = approve(submit("2", "초안 2"));
        tasks.claim(claimed.id(), me, claimed.stateVersion());
        String before = currentFingerprint("1");

        clock.advance(Duration.ofHours(1));
        upload(ExportWorkbooks.line("1", Map.of("리뷰상세내용", "고쳐 쓴 리뷰")),
                ExportWorkbooks.line("2", Map.of("답글여부", "Y", "답글등록일시", "2026.10.20. 13:00:00")));
        assertThat(tasks.get(waiting.id()).orElseThrow().state()).isEqualTo(State.INVALIDATED);
        assertThat(tasks.get(claimed.id()).orElseThrow().state()).as("claimed work is the delegate's to settle")
                .isEqualTo(State.CLAIMED);
        ReplyTasks.Preflight look = tasks.preflight(claimed.id(), me);
        assertThat(look.alreadyReplied()).isTrue();
        assertThat(look.clearToPost()).isFalse();

        assertThatThrownBy(() -> tasks.submitDraft(store, review("1"), "옛 리뷰에 맞춘 초안",
                new Meta(before, clock.instant(), "k1", "p1", "v1")))
                .as("a draft made from the old text").isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.REVIEW_CHANGED));
        assertThat(tasks.submitDraft(store, review("1"), "새 리뷰에 맞춘 초안",
                new Meta(currentFingerprint("1"), clock.instant(), "k1", "p1", "v1")).state())
                .isEqualTo(State.AWAITING_APPROVAL);
        report(claimed.id(), me, Outcome.FAILED, null);
        assertThatThrownBy(() -> submit("2", "이미 답글 달린 리뷰")).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.failure()).isEqualTo(Failure.ALREADY_REPLIED));
    }

    @Test
    void aPolicyChangeSendsUnapprovedDraftsBackAndOnlySuggestsWithdrawingApprovedOnes() {
        TaskView waiting = submit("1", "초안 1");
        TaskView approved = approve(submit("2", "초안 2"));
        ReplyTasks.PolicyCheck check = tasks.policyCheck(store, "k1", "p2", "v1");
        assertThat(check.sentBackForRecheck()).containsExactly(waiting.id());
        assertThat(check.withdrawalSuggested()).containsExactly(approved.id());
        assertThat(tasks.get(approved.id()).orElseThrow().state()).isEqualTo(State.APPROVED);
        TaskView rechecked = tasks.get(waiting.id()).orElseThrow();
        assertThat(rechecked.state()).isEqualTo(State.RECHECK_NEEDED);
        assertThatThrownBy(() -> tasks.approve(waiting.id(), account, 1, rechecked.draftFingerprint(), command()))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.WRONG_STATE));
        TaskView redone = tasks.submitDraft(store, review("1"), "초안 1",
                new Meta(currentFingerprint("1"), clock.instant(), "k1", "p2", "v1"));
        assertThat(redone.state()).isEqualTo(State.AWAITING_APPROVAL);
        assertThat(redone.draftVersion()).isEqualTo(2);
    }

    @Test
    void editingADraftOnThePhoneIsNotAPolicyRecheck() {
        TaskView sentBack = submit("1", "초안 1");
        tasks.policyCheck(store, "k1", "p2", "v1");
        TaskView edited = tasks.editDraft(sentBack.id(), 1, "휴대폰에서 고친 초안", true);
        assertThat(edited.state()).as("new text, old policy").isEqualTo(State.RECHECK_NEEDED);
        assertThat(edited.policyFp()).isEqualTo("p1");
        assertThatThrownBy(() -> tasks.approve(edited.id(), account, 2, edited.draftFingerprint(), command()))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.failure()).isEqualTo(Failure.WRONG_STATE));

        TaskView stale = tasks.submitDraft(store, review("1"), "옛 정책의 PC가 낸 초안", meta("1"));
        assertThat(stale.state()).as("a PC still on the old policy cannot clear it either")
                .isEqualTo(State.RECHECK_NEEDED);
        TaskView redone = tasks.submitDraft(store, review("1"), "휴대폰에서 고친 초안",
                new Meta(currentFingerprint("1"), clock.instant(), "k1", "p2", "v1"));
        assertThat(redone.state()).isEqualTo(State.AWAITING_APPROVAL);
        assertThat(approve(redone).state()).isEqualTo(State.APPROVED);

        TaskView fresh = tasks.submitDraft(store, review("2"), "처음부터 옛 정책인 초안", meta("2"));
        assertThat(fresh.state()).as("opened under an outdated policy").isEqualTo(State.RECHECK_NEEDED);
        TaskView current = tasks.submitDraft(store, review("2"), "지금 정책의 초안",
                new Meta(currentFingerprint("2"), clock.instant(), "k1", "p2", "v1"));
        assertThat(tasks.editDraft(current.id(), current.draftVersion(), "현재 정책 아래 휴대폰 수정", true).state())
                .as("an edit under the current policy stays approvable").isEqualTo(State.AWAITING_APPROVAL);
    }

    @Test
    void aClaimedReviewThatIsHiddenIsNotClearToPost() throws Exception {
        TaskView claimed = approve(submit("1", "초안 1"));
        tasks.claim(claimed.id(), me, claimed.stateVersion());
        TaskView waiting = approve(submit("2", "초안 2"));
        assertThat(tasks.preflight(claimed.id(), me).clearToPost()).isTrue();

        upload(ExportWorkbooks.line("1", Map.of("전시상태", "비노출")),
                ExportWorkbooks.line("2", Map.of("전시상태", "비노출")));
        assertThat(tasks.get(waiting.id()).orElseThrow().state()).as("unclaimed: ended")
                .isEqualTo(State.INVALIDATED);
        ReplyTasks.Preflight look = tasks.preflight(claimed.id(), me);
        assertThat(look.reviewChanged()).as("the text is the same").isFalse();
        assertThat(look.displayChanged()).isTrue();
        assertThat(look.clearToPost()).isFalse();
    }

    @Test
    void aSuspendedDelegatesClaimBecomesUnknownAndIsNeverRetriedUntilSettled() {
        TaskView task = approve(submit("1", "초안"));
        tasks.claim(task.id(), me, task.stateVersion());
        delegates.suspend(myEmail);
        assertThat(tasks.get(task.id()).orElseThrow().state()).isEqualTo(State.UNKNOWN);
        assertThatThrownBy(() -> submit("1", "새 초안")).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.failure()).isEqualTo(Failure.TASK_IN_PROGRESS));
        delegates.reinstate(myEmail);
        stores.assign(store, me);
        assertThat(report(task.id(), me, Outcome.POSTED, task.approvedFingerprint()).state())
                .isEqualTo(State.POSTED);
    }

    @Test
    void onlyAnAssignedDelegateClaimsAndOnlyTheClaimerReports() {
        TaskView task = approve(submit("1", "초안"));
        UUID stranger = delegate("x-" + myEmail);
        assertThatThrownBy(() -> tasks.claim(task.id(), stranger, task.stateVersion()))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.failure()).isEqualTo(Failure.NOT_FOUND));
        tasks.claim(task.id(), me, task.stateVersion());
        assertThatThrownBy(() -> report(task.id(), other, Outcome.POSTED, task.approvedFingerprint()))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.NOT_CLAIMER));
        assertThat(tasks.release(task.id(), me).state()).isEqualTo(State.APPROVED);
        assertThat(tasks.forDelegate(other, State.APPROVED)).hasSize(1);
    }

    @Test
    void ofTwoDelegatesClaimingAtOnceExactlyOneWins() throws Exception {
        TaskView task = approve(submit("1", "초안"));
        List<Callable<Boolean>> claims = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (UUID delegate : List.of(me, other, me, other)) {
            claims.add(() -> {
                start.await();
                try {
                    transactional.claim(task.id(), delegate, task.stateVersion());
                    return true;
                } catch (TaskException e) {
                    return false;
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(claims.size());
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> claim : claims) {
                results.add(pool.submit(claim));
            }
            start.countDown();
            int won = 0;
            for (Future<Boolean> result : results) {
                won += result.get() ? 1 : 0;
            }
            assertThat(won).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(tasks.get(task.id()).orElseThrow().state()).isEqualTo(State.CLAIMED);
    }

    @Test
    void cancelAndClaimRacingLeaveOneConsistentEnd() throws Exception {
        for (int round = 0; round < 5; round++) {
            String no = "r" + round;
            upload(ExportWorkbooks.line(String.valueOf(100 + round), Map.of()));
            TaskView task = approve(submit(String.valueOf(100 + round), "초안 " + no));
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> claim = pool.submit(() -> {
                    start.await();
                    try {
                        transactional.claim(task.id(), me, task.stateVersion());
                    } catch (TaskException ignored) {
                        // lost the race
                    }
                    return null;
                });
                Future<?> cancel = pool.submit(() -> {
                    start.await();
                    try {
                        transactional.cancel(task.id(), command());
                    } catch (TaskException ignored) {
                        // lost the race
                    }
                    return null;
                });
                start.countDown();
                claim.get();
                cancel.get();
            } finally {
                pool.shutdownNow();
            }
            State end = tasks.get(task.id()).orElseThrow().state();
            // Either the cancel came first (CANCELLED, nobody holds it) or the claim did (then the cancel became a
            // request to the delegate, or lost and left it CLAIMED). Never APPROVED with a stale claim, never both.
            assertThat(end).as("round " + round).isIn(State.CANCELLED, State.CANCEL_REQUESTED, State.CLAIMED);
            TaskView after = tasks.get(task.id()).orElseThrow();
            assertThat(after.claimed()).isEqualTo(end != State.CANCELLED);
        }
    }

    @Test
    void aDelegateCannotRewordAnApprovedReply() {
        TaskView task = approve(submit("1", "승인된 문장"));
        tasks.claim(task.id(), me, task.stateVersion());
        assertThatThrownBy(() -> report(task.id(), me, Outcome.POSTED, ReplyText.fingerprint("살짝 바꾼 문장")))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.FINGERPRINT_MISMATCH));
    }

    @Test
    void malformedInputIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> submit("1", "   ")).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.failure()).isEqualTo(Failure.INVALID_BODY));
        assertThatThrownBy(() -> tasks.submitDraft(store, review("1"), "초안",
                new Meta(currentFingerprint("1"), clock.instant().plus(Duration.ofHours(1)), "k1", "p1", "v1")))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.failure()).isEqualTo(Failure.INVALID_META));
        assertThatThrownBy(() -> tasks.submitDraft(store, review("1"), "초안",
                new Meta(currentFingerprint("1"), clock.instant(), "k 1", "p1", "v1")))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.failure()).isEqualTo(Failure.INVALID_META));
        assertThatThrownBy(() -> tasks.submitDraft(store, UUID.randomUUID(), "초안", meta("1")))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.failure()).isEqualTo(Failure.NOT_FOUND));
        assertThat(tasks.list(store, null)).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private void claimAndExpectMismatch(TaskView approved) {
        tasks.claim(approved.id(), me, approved.stateVersion());
        assertThat(tasks.preflight(approved.id(), me).approvedBody()).isEqualTo("휴대폰에서 고친 초안");
    }

    private TaskView submit(String reviewNo, String body) {
        return tasks.submitDraft(store, review(reviewNo), body, meta(reviewNo));
    }

    private Meta meta(String reviewNo) {
        return new Meta(currentFingerprint(reviewNo), Instant.parse("2026-10-20T02:00:00Z"), "k1", "p1", "v1");
    }

    private TaskView approve(TaskView draft) {
        return tasks.approve(draft.id(), account, draft.draftVersion(), draft.draftFingerprint(), command());
    }

    private TaskView report(UUID task, UUID delegate, Outcome outcome, String fingerprint) {
        return tasks.report(task, delegate, outcome, fingerprint, clock.instant(), ReportMethod.SELLER_CENTER_SCREEN,
                command());
    }

    private String command() {
        return "cmd-" + UUID.randomUUID() + "-" + (commands++);
    }

    private UUID review(String reviewNo) {
        return reviews.idOf(store, reviewNo).orElseThrow();
    }

    private String currentFingerprint(String reviewNo) {
        return jdbc.sql("select body_fingerprint from store_reviews where store_id = ? and review_no = ?")
                .params(store, reviewNo).query(String.class).single();
    }

    @SafeVarargs
    private void upload(Map<String, String>... lines) throws Exception {
        reviews.importExport(store, me != null ? me : delegate("u-" + UUID.randomUUID() + "@example.com"),
                NaverReviewExport.read(ExportWorkbooks.workbook(List.of(lines))));
    }

    private UUID delegate(String email) {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        Delegates.Staged staged = delegates.stage(invite, PASSWORD);
        return delegates.confirm(invite, TestTotp.code(staged.secret(), Instant.now(), 0)).delegateId();
    }
}
