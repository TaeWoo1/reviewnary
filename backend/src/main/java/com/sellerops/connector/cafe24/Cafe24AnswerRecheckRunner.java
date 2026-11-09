package com.sellerops.connector.cafe24;

import com.sellerops.community.CommunityReplyStatus;
import com.sellerops.ingest.IngestionService;
import com.sellerops.ingest.canonical.CanonicalInquiry;
import com.sellerops.ingest.canonical.CommentAnswerCheck;
import com.sellerops.ingest.canonical.SourceThreadRole;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.publish.InquiryExecution;
import com.sellerops.inquiry.publish.InquiryExecutionRepository;
import com.sellerops.inquiry.publish.InquiryExecutionStatus;
import com.sellerops.inquiry.recheck.InquiryRecheckLedger;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Re-ask Cafe24 about inquiries the routine sweep no longer reads</b> (2026-11-08, inquiry_operational_truth_v1 §10.1 ·
 * §10.2 · §10.3). The routine lane re-reads the last {@value Cafe24ApiConnector#ROUTINE_WINDOW_DAYS} days; an inquiry
 * older than that which the seller still holds as work is never asked about again, so an answer written later on the
 * seller centre — by comment or by reply status — never takes it off the list.
 *
 * <p><b>Who is asked about.</b> One seller account's board-6 inquiries that are still {@code UNANSWERED}, current
 * ({@code ACTIVE}, so not excluded as spam or otherwise), real, not thread replies, received before the routine window, with
 * a work item still {@code OPEN} or {@code PROPOSED} — never one past the seller's approval, and never one whose
 * registration by SellerOps is still in flight or awaiting verification (that verification re-reads it already). Named
 * one by one; no window, no offset, no cursor — the new-inquiry cursor is not touched.
 *
 * <p><b>How.</b> Batches of at most {@value #MAX_BATCH}: one re-read of the named articles
 * ({@link Cafe24BoardArticlesClient#fetchByArticleNumbers}) and, for those still unanswered there, the exact-set comment
 * look ({@link Cafe24InquiryAnswerObserver#observeExactChecks}). What comes back goes through the ordinary ingest
 * ({@link IngestionService#ingestInquiries}) — the same writer, the same closing rule as the routine sweep: a proven
 * answer closes the open task and nothing else closes or reopens anything.
 *
 * <p><b>What each article ends up with.</b>
 * <ul>
 *   <li>answered on the channel (reply status or proven shop comment) → {@code ANSWERED} through ingest, task closed;</li>
 *   <li>looked and no shop comment → {@code NO_SHOP_COMMENT}, task left open;</li>
 *   <li>the look failed → {@code NOT_CHECKED}, the success time kept. The record says only that; the run's
 *       {@link Look} also says <b>whose</b> failure it was ({@link Cause}): {@code ARTICLE} — this article's comment read
 *       failed with a 404, a 500 or an unreadable body, its comments proved no answer (unknown author, unreadable time), or the
 *       source did not return it by number; {@code ACCOUNT} — a 429, a 401/403, a 502/503/504, a failed batch re-read, a
 *       failed or incomplete discovery, and any failure of unknown kind. An account-side failure sends no further request
 *       and ends the run after its batch ({@link Stop});</li>
 *   <li>not reached, or re-read but no comment request sent, because the request limit refused → <b>nothing written</b>
 *       about its comments. Not trying is not a failed look.</li>
 * </ul>
 *
 * <p><b>The limit is HTTP requests</b> ({@link Cafe24RequestBudget}): the token refresh, each re-read, each discovery,
 * each comment read. A batch starts only when the worst case it can cost — {@code 2 + its size} — still fits, so the
 * limit never cuts a batch in half; if it ever did, the articles whose comment request was refused are
 * {@code NOT_ATTEMPTED} and get no record, and {@link Outcome#refused()} says so.
 * Up to half of the limit goes to articles whose last look failed ({@code NOT_CHECKED}), the rest first to the others,
 * and what one side leaves the other uses — so articles that keep failing cannot spend the whole run.
 *
 * <p><b>Across runs</b> (2026-11-08, V185 — {@link InquiryRecheckLedger}, numbers from {@link Cafe24RecheckPolicy}):
 * <ul>
 *   <li><b>one run per account.</b> A run first takes the account's lock; if another run holds it, this one sends nothing
 *       and ends {@link Stop#BUSY}. The lock is renewed before every request and let go at the end; a run that died loses
 *       it once its lease has run out. Other accounts are never held up;</li>
 *   <li><b>a daily budget in the database.</b> Every request is first taken out of the account's Korean-day budget, so a
 *       restart or a second call the same day spends from what is left, and the next Korean day starts afresh;</li>
 *   <li><b>retry waits per inquiry.</b> Only a failure that was the article's own ({@link Cause#ARTICLE}) adds to its
 *       failures in a row ({@code answer_recheck_failures}); a successful look clears them; an account-side failure and a
 *       request never sent change neither. An inquiry with failures waits {@link Cafe24RecheckPolicy#waitDaysAfter} Korean
 *       days from its last own failure, and the run asks about the others meanwhile ({@link Outcome#waiting()});</li>
 *   <li><b>once a Korean day per inquiry.</b> An inquiry whose last successful look ({@code comment_checked_at}) is from
 *       today is not asked about again today ({@link Outcome#checkedToday()}). Only a success moves that time: a look that
 *       failed on the account's side, or a request the limit never sent, leaves it, so such an inquiry is asked again;</li>
 *   <li><b>an account wait</b> (V186). A run that stops on the account's side ({@link #ACCOUNT_SIDE}) makes the account
 *       rest for {@link Cafe24RecheckPolicy#accountWaitAfter}; until then a run sends nothing and ends
 *       {@link Stop#ACCOUNT_WAIT}. A run that sent requests and ended without such a stop clears it.</li>
 * </ul>
 *
 * <p><b>Who calls it.</b> {@link Cafe24AnswerRecheckAfterCollection}, at the end of each successful scheduled Cafe24
 * inquiry collection — and only when {@code sellerops.inquiry.recheck.auto-enabled=true}, which is off unless set. Counts
 * only are logged — no article number, text, author or mall.
 */
public final class Cafe24AnswerRecheckRunner {

    private static final Logger log = LoggerFactory.getLogger(Cafe24AnswerRecheckRunner.class);

    static final int BOARD = 6;
    /** The exact-set comment look's own ceiling, and so the batch size. */
    static final int MAX_BATCH = Cafe24InquiryAnswerObserver.MAX_EXACT_COMMENT_READS;
    /** A batch's worst case beyond its size: the re-read and the comment discovery. */
    private static final int BATCH_OVERHEAD = 2;

    private static final Set<InquiryWorkItemPhase> RECHECKABLE =
            EnumSet.of(InquiryWorkItemPhase.OPEN, InquiryWorkItemPhase.PROPOSED);
    /** Stops that are about the account, not about any inquiry: each makes the account wait. */
    static final Set<Stop> ACCOUNT_SIDE =
            EnumSet.of(Stop.RATE_LIMITED, Stop.AUTH_FAILED, Stop.AUTH_REJECTED, Stop.CHANNEL_FAILED);
    /** Stops after which the account is known to answer: a run that sent requests and ended so clears the wait. */
    private static final Set<Stop> ANSWERING = EnumSet.of(Stop.DONE, Stop.LIMIT, Stop.DAILY_LIMIT);
    private static final Set<InquiryExecutionStatus> IN_FLIGHT = EnumSet.of(InquiryExecutionStatus.ACTION_PENDING,
            InquiryExecutionStatus.DISPATCHING, InquiryExecutionStatus.EXECUTED, InquiryExecutionStatus.DELIVERY_UNKNOWN);

    /** Why a run ended. */
    public enum Stop {
        DONE,
        /** This run's own request limit left targets unasked. */
        LIMIT,
        /** The account's budget for today (Korean day) left targets unasked. The next day's run goes on. */
        DAILY_LIMIT,
        /** Another run holds this account — at the start, so nothing was sent; or it took the account over mid-run because
         *  this run's lease ran out, and nothing more was sent. */
        BUSY,
        /** The account is resting after an earlier run stopped on its side (V186): nothing was read or sent. */
        ACCOUNT_WAIT,
        /** The channel answered 429. Account-wide: the rest of the run waits for another time. */
        RATE_LIMITED,
        /** No token for this account: nothing was asked. */
        AUTH_FAILED,
        /** The channel refused the account's token or permission (401/403) during the run. No further request went out;
         *  what was already looked at is kept. */
        AUTH_REJECTED,
        /** A call that is not about any one article failed — the re-read of a batch, the comment discovery, or a comment
         *  read that failed by outage or in a way of unknown kind. */
        CHANNEL_FAILED
    }

    /**
     * <b>Whose failure a failed look is</b> (2026-11-08). The record and the screen say only 「확인 못 함」 either way. Kept
     * apart here because spacing out retries per inquiry must count only failures that are about that inquiry.
     */
    public enum Cause {
        /** About this article and nothing else: its comment read failed, its comments did not prove an answer, or the
         *  source did not return it when asked by number. */
        ARTICLE,
        /** About the account or the channel: a 429, a refused token or permission, an outage, a failed batch re-read, a
         *  failed or incomplete discovery, or any failure not known to be the article's. */
        ACCOUNT
    }

    /** What one look at one inquiry came to. {@code cause} is set exactly for {@code NOT_CHECKED}. */
    public enum Seen { ANSWERED, NO_SHOP_COMMENT, NOT_CHECKED, NOT_ATTEMPTED }

    /**
     * One inquiry this run asked about. {@code NOT_ATTEMPTED}: its article was re-read but no comment request went out for
     * it, because the request limit refused — nothing was recorded about its comments. Inquiries the run never reached
     * have no entry at all ({@link Outcome#notReached()}).
     */
    public record Look(UUID inquiryId, Seen seen, Cause cause) {
        public Look {
            if (seen == null || (seen == Seen.NOT_CHECKED) != (cause != null)) {
                throw new IllegalArgumentException("확인 결과와 원인이 맞지 않습니다.");
            }
        }
    }

    /**
     * What one run did. Counts, plus one {@link Look} per inquiry asked about, in the order asked. {@code refused} is how
     * many requests the limit refused — zero when the batches were planned as they should be. {@code waiting}: open
     * inquiries left out because their retry wait has not passed — not targets, not counted in {@code notReached}.
     * {@code checkedToday}: open inquiries left out because a look at them already succeeded today (Korean day).
     */
    public record Outcome(int targets, int attempted, int requests, int refused, int answered, int noShopComment,
                          int notCheckedArticle, int notCheckedAccount, int notFound, int notReached, int waiting,
                          int checkedToday, Stop stop, List<Look> looks) {
        public int notChecked() {
            return notCheckedArticle + notCheckedAccount;
        }
    }

    private final Cafe24HttpClient http;
    private final Cafe24Authorizer authorizer;
    private final InquiryRepository inquiries;
    private final InquiryWorkItemRepository workItems;
    private final InquiryExecutionRepository executions;
    private final IngestionService ingestion;
    private final InquiryRecheckLedger ledger;
    private final Cafe24RecheckPolicy policy;
    private final Clock clock;

    public Cafe24AnswerRecheckRunner(Cafe24HttpClient http, Cafe24Authorizer authorizer, InquiryRepository inquiries,
                                     InquiryWorkItemRepository workItems, InquiryExecutionRepository executions,
                                     IngestionService ingestion, InquiryRecheckLedger ledger,
                                     Cafe24RecheckPolicy policy, Clock clock) {
        this.http = http;
        this.authorizer = authorizer;
        this.inquiries = inquiries;
        this.workItems = workItems;
        this.executions = executions;
        this.ingestion = ingestion;
        this.ledger = ledger;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Who a run would ask about, in order; how many open inquiries are waiting out a retry interval instead; and how many
     * were already looked at successfully today.
     */
    record Selection(List<Inquiry> targets, int waiting, int checkedToday) {
    }

    /** The inquiries a run for this account would ask about, in the order it would ask (failed-last-time ones apart). */
    List<Inquiry> targets(UUID orgId, UUID sellerAccountId, UUID channelId) {
        return select(orgId, sellerAccountId, channelId).targets();
    }

    private Selection select(UUID orgId, UUID sellerAccountId, UUID channelId) {
        LocalDate windowStart = LocalDate.now(clock.withZone(Cafe24BoardArticleMapper.CAFE24_ZONE))
                .minusDays(Cafe24ApiConnector.ROUTINE_WINDOW_DAYS);
        LocalDate today = LocalDate.ofInstant(clock.instant(), InquiryRecheckLedger.DAY_ZONE);
        List<Inquiry> out = new ArrayList<>();
        int waiting = 0;
        int checkedToday = 0;
        for (Inquiry q : inquiries.findActiveUnansweredForAccount(orgId, sellerAccountId)) {
            if (!channelId.equals(q.getChannelId()) || q.threadRole() == SourceThreadRole.REPLY
                    || articleNo(q.getExternalId()) == null || q.getReceivedAt() == null
                    || !q.getReceivedAt().atZone(Cafe24BoardArticleMapper.CAFE24_ZONE).toLocalDate().isBefore(windowStart)) {
                continue;
            }
            Optional<InquiryWorkItem> item = workItems.findByInquiryId(q.getId());
            if (item.isEmpty() || !RECHECKABLE.contains(item.get().getPhase())) {
                continue;
            }
            Optional<InquiryExecution> execution = executions.findByWorkItemId(item.get().getId());
            if (execution.isPresent() && IN_FLIGHT.contains(execution.get().getStatus())) {
                continue;
            }
            if (isWaiting(q, today)) {
                waiting++;
                continue;
            }
            if (checkedOn(q, today)) {
                checkedToday++;
                continue;
            }
            out.add(q);
        }
        // Longest since a successful look first; never successfully looked at before anything else; then as collected.
        out.sort(Comparator.comparing(Inquiry::getCommentCheckedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Inquiry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Inquiry::getId));
        return new Selection(out, waiting, checkedToday);
    }

    /**
     * A look at it already succeeded on this Korean day. {@code comment_checked_at} moves only on a success
     * ({@code IngestionService}): a look that failed for the account, or one never sent, leaves it where it was.
     */
    private static boolean checkedOn(Inquiry q, LocalDate today) {
        return q.getCommentCheckedAt() != null
                && LocalDate.ofInstant(q.getCommentCheckedAt(), InquiryRecheckLedger.DAY_ZONE).equals(today);
    }

    /**
     * Still waiting out its retry interval: it failed on its own account {@code n} times in a row, and fewer than
     * {@link Cafe24RecheckPolicy#waitDaysAfter waitDaysAfter(n)} Korean days have begun since the last of those failures.
     * Counted in days, not hours, so a daily run a little earlier than yesterday's does not skip a day.
     */
    private boolean isWaiting(Inquiry q, LocalDate today) {
        if (q.getAnswerRecheckFailures() <= 0 || q.getAnswerRecheckFailedAt() == null) {
            return false;
        }
        LocalDate failedOn = LocalDate.ofInstant(q.getAnswerRecheckFailedAt(), InquiryRecheckLedger.DAY_ZONE);
        return today.isBefore(failedOn.plusDays(policy.waitDaysAfter(q.getAnswerRecheckFailures())));
    }

    /** One run over one seller account, within the account's daily budget ({@link Cafe24RecheckPolicy}). */
    public Outcome run(UUID orgId, UUID sellerAccountId, UUID channelId) {
        return run(orgId, sellerAccountId, channelId, policy.dailyRequestLimit());
    }

    /**
     * One run over one seller account, spending at most {@code requestLimit} HTTP requests — and never more than is left of
     * the account's budget for today.
     */
    public Outcome run(UUID orgId, UUID sellerAccountId, UUID channelId, int requestLimit) {
        Optional<InquiryRecheckLedger.Lease> lease = ledger.acquire(sellerAccountId, policy.runLease());
        if (lease.isEmpty()) {
            // Another run is on this account. Nothing was read or sent, and nothing is written.
            return new Run(orgId, sellerAccountId, channelId, new Cafe24RequestBudget(http, 0), 0, 0, 0)
                    .finish(Stop.BUSY);
        }
        try {
            if (ledger.accountWaitUntil(sellerAccountId).isPresent()) {
                // The account is resting after an account-side stop. Nothing is read or sent, and nothing is written.
                return new Run(orgId, sellerAccountId, channelId, new Cafe24RequestBudget(http, 0), 0, 0, 0)
                        .finish(Stop.ACCOUNT_WAIT);
            }
            Outcome outcome = runHolding(orgId, sellerAccountId, channelId, requestLimit, lease.get());
            settleAccountWait(lease.get(), outcome);
            return outcome;
        } finally {
            ledger.release(lease.get());
        }
    }

    /**
     * Still holding the lease: an account-side stop makes the account wait (one more in a row); a run that reached the
     * channel and ended without one shows the account answers, and clears it. A run that sent nothing proves neither.
     * Both only while this run still holds the account (2026-11-09): a run that stalled past its lease and was taken over
     * leaves the wait as the run that took over left it.
     */
    private void settleAccountWait(InquiryRecheckLedger.Lease lease, Outcome outcome) {
        if (ACCOUNT_SIDE.contains(outcome.stop())) {
            int inARow = ledger.recordAccountFailure(lease, outcome.stop().name(), policy::accountWaitAfter);
            if (inARow == 0) {
                log.info("카페24 열린 문의 재확인 계정 대기 기록 생략: 잠금이 다른 실행으로 넘어감 이유={}", outcome.stop());
            } else {
                log.info("카페24 열린 문의 재확인 계정 대기: 연속={} 대기분={} 이유={}", inARow,
                        policy.accountWaitAfter(inARow).toMinutes(), outcome.stop());
            }
        } else if (outcome.requests() > 0 && ANSWERING.contains(outcome.stop())) {
            if (!ledger.clearAccountWait(lease)) {
                log.info("카페24 열린 문의 재확인 계정 대기 해제 생략: 잠금이 다른 실행으로 넘어감");
            }
        }
    }

    private Outcome runHolding(UUID orgId, UUID sellerAccountId, UUID channelId, int requestLimit,
                               InquiryRecheckLedger.Lease lease) {
        Selection selection = select(orgId, sellerAccountId, channelId);
        List<Inquiry> all = selection.targets();
        Deque<Inquiry> failedBefore = new ArrayDeque<>();
        Deque<Inquiry> others = new ArrayDeque<>();
        for (Inquiry q : all) {
            (q.getCommentCheck() == CommentAnswerCheck.Result.NOT_CHECKED ? failedBefore : others).add(q);
        }
        int dailyLimit = policy.dailyRequestLimit();
        Cafe24RequestBudget.Allowance day = new Cafe24RequestBudget.Allowance() {
            @Override
            public Cafe24RequestBudget.Refusal take() {
                if (!ledger.renew(lease)) {
                    return Cafe24RequestBudget.Refusal.LEASE_LOST;
                }
                return ledger.take(sellerAccountId, dailyLimit) ? null : Cafe24RequestBudget.Refusal.DAILY_LIMIT;
            }

            @Override
            public int remaining() {
                return ledger.remainingToday(sellerAccountId, dailyLimit);
            }
        };
        Run run = new Run(orgId, sellerAccountId, channelId, new Cafe24RequestBudget(http, requestLimit, day),
                all.size(), selection.waiting(), selection.checkedToday());
        run.day = day;
        if (all.isEmpty()) {
            return run.finish(Stop.DONE);
        }
        if (run.budget.remaining() < BATCH_OVERHEAD + 1) {
            return run.finish(limitStop(run));
        }
        try {
            run.auth = authorizer.authorize(orgId, sellerAccountId, new Cafe24TokenClient(run.budget));
        } catch (RuntimeException e) {
            // The account cannot be read at all: no article was asked about, so none gets a record.
            return run.finish(run.budget.refusals() > 0 ? stopFor(run.budget.lastRefusal()) : Stop.AUTH_FAILED);
        }
        run.articles = new Cafe24BoardArticlesClient(run.budget);
        run.observer = new Cafe24InquiryAnswerObserver(run.articles, new Cafe24BoardCommentsClient(run.budget));

        // Half of what this run may spend — its own limit or what is left of the day, whichever is smaller.
        int failedShare = (run.budget.used() + run.budget.remaining()) / 2;
        drain(run, failedBefore, failedShare);
        drain(run, others, Integer.MAX_VALUE);
        drain(run, failedBefore, Integer.MAX_VALUE);
        return run.finish(run.stop != null ? run.stop
                : failedBefore.isEmpty() && others.isEmpty() ? Stop.DONE : limitStop(run));
    }

    /** Targets left for want of requests: whose limit it was — the day's when it is no larger, else the run's own. */
    private static Stop limitStop(Run run) {
        if (run.budget.lastRefusal() != null) {
            return stopFor(run.budget.lastRefusal());
        }
        int own = run.budget.limit() - run.budget.used();
        return run.day != null && run.day.remaining() <= own ? Stop.DAILY_LIMIT : Stop.LIMIT;
    }

    static Stop stopFor(Cafe24RequestBudget.Refusal refusal) {
        if (refusal == null) {
            return Stop.LIMIT;
        }
        return switch (refusal) {
            case RUN_LIMIT -> Stop.LIMIT;
            case DAILY_LIMIT -> Stop.DAILY_LIMIT;
            case LEASE_LOST -> Stop.BUSY;
        };
    }

    private void drain(Run run, Deque<Inquiry> queue, int share) {
        int startedAt = run.budget.used();
        while (run.stop == null && !queue.isEmpty()) {
            int room = Math.min(run.budget.remaining(), share == Integer.MAX_VALUE ? Integer.MAX_VALUE
                    : share - (run.budget.used() - startedAt));
            int size = Math.min(Math.min(MAX_BATCH, queue.size()), room - BATCH_OVERHEAD);
            if (size < 1) {
                return;
            }
            List<Inquiry> batch = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                batch.add(queue.poll());
            }
            batch(run, batch);
        }
    }

    private void batch(Run run, List<Inquiry> batch) {
        Instant at = clock.instant();
        Map<Long, Inquiry> byNo = new LinkedHashMap<>();
        batch.forEach(q -> byNo.put(articleNo(q.getExternalId()), q));
        run.attempted += batch.size();

        List<Cafe24BoardArticleRow> rows;
        try {
            rows = run.articles.fetchByArticleNumbers(run.auth.accessToken(), run.auth.mallId(), BOARD,
                    new ArrayList<>(byNo.keySet()));
        } catch (Cafe24RequestBudget.Exhausted e) {
            run.attempted -= batch.size(); // nothing went out for them: not reached, and nothing written
            run.stop = stopFor(e.refusal());
            return;
        } catch (RuntimeException e) {
            // Asked, and the answer did not come — for the whole batch at once, so about the account or the channel and
            // not about any one article. Still a failed look for each ("확인 못 함"), never "no answer".
            batch.forEach(q -> failed(run, q, at, Cause.ACCOUNT));
            run.stop = e instanceof Cafe24RateLimitedException ? Stop.RATE_LIMITED
                    : e instanceof Cafe24ReadFailedException f && f.kind() == Cafe24ReadFailedException.Kind.AUTH_REJECTED
                    ? Stop.AUTH_REJECTED : Stop.CHANNEL_FAILED;
            return;
        }

        Map<Long, Cafe24BoardArticleRow> returned = new LinkedHashMap<>();
        for (Cafe24BoardArticleRow row : rows) {
            if (row != null && row.articleNo() != null && byNo.containsKey(row.articleNo())) {
                returned.putIfAbsent(row.articleNo(), row);
            }
        }

        List<Long> candidates = new ArrayList<>();
        returned.forEach((no, row) -> {
            boolean answered = CommunityReplyStatus.normalize(row.replyStatus()) == CommunityReplyStatus.ANSWERED;
            if (!answered && !row.isThreadReply()) {
                candidates.add(no);
            }
        });
        Map<Long, Cafe24InquiryAnswerObserver.CommentCheck> checks =
                run.observer.observeExactChecks(run.auth.accessToken(), run.auth.mallId(), BOARD, candidates);

        List<CanonicalInquiry> records = new ArrayList<>();
        List<Look> looks = new ArrayList<>();
        Stop stopAfter = null;
        int position = 0;
        for (Map.Entry<Long, Cafe24BoardArticleRow> e : returned.entrySet()) {
            Cafe24InquiryAnswerObserver.CommentCheck check = checks.get(e.getKey());
            // The mapper stores no record for a look whose request was never sent.
            CanonicalInquiry record = Cafe24InquiryArticleMapper.toCanonicalInquiry(BOARD, e.getValue(), ++position,
                    check, at);
            records.add(record);
            UUID inquiryId = byNo.get(e.getKey()).getId();
            if ("ANSWERED".equals(record.status())) {
                run.answered++;
                looks.add(new Look(inquiryId, Seen.ANSWERED, null));
            } else if (check == null) {
                // A thread reply now, or answered by status: nothing to look for. Not a comment look.
                continue;
            } else if (check.notAttempted()) {
                looks.add(new Look(inquiryId, Seen.NOT_ATTEMPTED, null));
                stopAfter = graver(stopAfter, stopFor(run.budget.lastRefusal()));
            } else if (check.state() == Cafe24InquiryAnswerObserver.CheckState.NO_SHOP_COMMENT) {
                run.noShopComment++;
                looks.add(new Look(inquiryId, Seen.NO_SHOP_COMMENT, null));
            } else {
                Cause cause = causeOf(check.reason());
                count(run, cause);
                looks.add(new Look(inquiryId, Seen.NOT_CHECKED, cause));
                if (cause == Cause.ACCOUNT) {
                    stopAfter = graver(stopAfter, stopFor(check.reason()));
                }
            }
        }
        ingestion.ingestInquiries(run.orgId, run.channelId, run.sellerAccountId, records);
        run.looks.addAll(looks);
        // After the ingest, so the record a failure count belongs to is already written. Only the article's own outcome
        // moves the count: a success clears it, an ARTICLE failure adds one; ACCOUNT and NOT_ATTEMPTED leave it.
        for (Look look : looks) {
            if (look.seen() == Seen.ANSWERED || look.seen() == Seen.NO_SHOP_COMMENT) {
                inquiries.clearRecheckFailures(look.inquiryId());
            } else if (look.cause() == Cause.ARTICLE) {
                inquiries.countRecheckFailure(look.inquiryId(), at);
            }
        }

        // Named, and not in the answer: not a deletion and not an answer — a failed look at that article.
        byNo.forEach((no, q) -> {
            if (!returned.containsKey(no)) {
                run.notFound++;
                failed(run, q, at, Cause.ARTICLE);
            }
        });
        if (stopAfter != null) {
            run.stop = stopAfter;
        }
    }

    /**
     * Which side a failed comment look falls on. Only a failure the article itself produced counts against it; anything
     * that is not evidence about this article — including a reason this runner does not know — is the account's.
     */
    static Cause causeOf(Cafe24InquiryAnswerObserver.Reason reason) {
        if (reason == Cafe24InquiryAnswerObserver.Reason.READ_FAILED
                || reason == Cafe24InquiryAnswerObserver.Reason.UNPROVEN) {
            return Cause.ARTICLE;
        }
        return Cause.ACCOUNT;
    }

    /** The stop an account-side failure of this kind calls for. */
    static Stop stopFor(Cafe24InquiryAnswerObserver.Reason reason) {
        return switch (reason) {
            case AUTH_REJECTED -> Stop.AUTH_REJECTED;
            case RATE_LIMITED -> Stop.RATE_LIMITED;
            default -> Stop.CHANNEL_FAILED;
        };
    }

    /** Of two reasons to stop, the one that says more about what is wrong with the account. */
    private static Stop graver(Stop current, Stop next) {
        return current == null || rank(next) > rank(current) ? next : current;
    }

    private static int rank(Stop stop) {
        return switch (stop) {
            case AUTH_REJECTED -> 5;
            case RATE_LIMITED -> 4;
            case CHANNEL_FAILED -> 3;
            case BUSY, ACCOUNT_WAIT -> 2;
            case DAILY_LIMIT, LIMIT -> 1;
            default -> 0;
        };
    }

    private static void count(Run run, Cause cause) {
        if (cause == Cause.ARTICLE) {
            run.notCheckedArticle++;
        } else {
            run.notCheckedAccount++;
        }
    }

    private void failed(Run run, Inquiry q, Instant at, Cause cause) {
        if (ingestion.recordFailedCommentLook(run.orgId, run.channelId, q.getExternalId(), at)) {
            count(run, cause);
            run.looks.add(new Look(q.getId(), Seen.NOT_CHECKED, cause));
            if (cause == Cause.ARTICLE) {
                inquiries.countRecheckFailure(q.getId(), at);
            }
        }
    }


    /** The article number of a board-6 inquiry's external id, or null for anything else. */
    static Long articleNo(String externalId) {
        String prefix = "cafe24:b" + BOARD + ":a";
        if (externalId == null || !externalId.startsWith(prefix)) {
            return null;
        }
        try {
            long no = Long.parseLong(externalId.substring(prefix.length()));
            return no > 0 && Cafe24InquiryArticleMapper.externalId(BOARD, no).equals(externalId) ? no : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Run {
        final UUID orgId;
        final UUID sellerAccountId;
        final UUID channelId;
        final Cafe24RequestBudget budget;
        final int targets;
        final int waiting;
        final int checkedToday;
        Cafe24RequestBudget.Allowance day;
        Cafe24Authorizer.Authorized auth;
        Cafe24BoardArticlesClient articles;
        Cafe24InquiryAnswerObserver observer;
        Stop stop;
        int attempted;
        int answered;
        int noShopComment;
        int notCheckedArticle;
        int notCheckedAccount;
        int notFound;
        final List<Look> looks = new ArrayList<>();

        Run(UUID orgId, UUID sellerAccountId, UUID channelId, Cafe24RequestBudget budget, int targets, int waiting,
            int checkedToday) {
            this.orgId = orgId;
            this.sellerAccountId = sellerAccountId;
            this.channelId = channelId;
            this.budget = budget;
            this.targets = targets;
            this.waiting = waiting;
            this.checkedToday = checkedToday;
        }

        Outcome finish(Stop how) {
            Outcome o = new Outcome(targets, attempted, budget.used(), budget.refusals(), answered, noShopComment,
                    notCheckedArticle, notCheckedAccount, notFound, targets - attempted, waiting, checkedToday, how,
                    List.copyOf(looks));
            // Counts only — never an article number, a text, an author or a mall.
            log.info("카페24 열린 문의 재확인: 대상={} 시도={} 요청={}/{} 거절={} 답변확인={} 댓글없음={} 확인못함(문의)={} "
                            + "확인못함(계정·채널)={} 글없음={} 미시도={} 재시도대기={} 오늘확인됨={} 오늘남은요청={} 끝={}",
                    o.targets(), o.attempted(), o.requests(), budget.limit(), o.refused(), o.answered(),
                    o.noShopComment(), o.notCheckedArticle(), o.notCheckedAccount(), o.notFound(), o.notReached(),
                    o.waiting(), o.checkedToday(), day == null ? "-" : day.remaining(), o.stop());
            return o;
        }
    }
}
