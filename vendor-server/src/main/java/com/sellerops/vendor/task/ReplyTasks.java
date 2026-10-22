package com.sellerops.vendor.task;

import com.sellerops.vendor.review.ReviewAttachment;
import com.sellerops.vendor.review.StoreReviews;
import com.sellerops.vendor.store.Stores;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The review-reply delegation task, and the only place its state changes</b> — contract §4.
 *
 * <pre>
 * AWAITING_APPROVAL ──approve──▶ APPROVED ──claim──▶ CLAIMED ──report──▶ POSTED · FAILED · UNKNOWN
 *   │   ▲                          │                    │
 *   │   └─new version (PC/phone)   │cancel              │cancel
 *   ├─policy differs─▶ RECHECK_NEEDED                  ▼
 *   ├─cancel─▶ CANCELLED       CANCELLED          CANCEL_REQUESTED ──report──▶ CANCELLED · POSTED
 *   └─review changed · removed · answered ─▶ INVALIDATED   (from APPROVED too)
 * </pre>
 *
 * <ul>
 *   <li><b>Every transition is one conditional UPDATE</b> on {@code state} and {@code state_version}, so of two
 *       racing requests exactly one wins and the other is told the task moved ({@link Failure#STALE}).</li>
 *   <li><b>An approval binds to one exact text.</b> It names the draft version and its fingerprint; any edit makes
 *       a new version that must be approved again. A delegate's {@code POSTED} must name that same fingerprint —
 *       a delegate does not reword an approved reply.</li>
 *   <li><b>A review has at most one open task</b> ({@code open_review_id} is unique), and none at all once the
 *       channel says it is answered.</li>
 *   <li><b>The server judges no draft.</b> It carries the PC's provenance fingerprints and its knowledge-free
 *       {@link ReplyText#checks}; a policy change only sends unapproved drafts back for a recheck and only
 *       <i>suggests</i> withdrawing an approved one — the seller's decision is theirs.</li>
 *   <li><b>{@code UNKNOWN} is never retried.</b> It stays open on its review until a delegate confirms what
 *       happened — or, when that delegate was suspended, an admin does ({@link #settleByOperator}).</li>
 * </ul>
 *
 * <p>Callers decide who may touch which store; the delegate methods additionally check assignment and claim.
 */
@Service
public class ReplyTasks {

    public enum State {
        AWAITING_APPROVAL, RECHECK_NEEDED, APPROVED, CLAIMED, CANCEL_REQUESTED, POSTED, FAILED, UNKNOWN, CANCELLED,
        INVALIDATED;

        /** States in which the task no longer holds its review open. */
        static final Set<State> ENDED = EnumSet.of(POSTED, FAILED, CANCELLED, INVALIDATED);
    }

    /** What a delegate reports after looking at Seller Center. */
    public enum Outcome { POSTED, FAILED, UNKNOWN }

    /**
     * How it was confirmed: the claiming delegate looked at the review in Seller Center, or — for a suspended
     * delegate's task — an admin did ({@link #settleByOperator}).
     */
    public enum ReportMethod { SELLER_CENTER_SCREEN, OPERATOR_SELLER_CENTER_SCREEN }

    /**
     * An {@code UNKNOWN} task whose claimer is suspended: nobody can settle it on the delegate desk any more. The
     * approved text is what the operator compares with Seller Center.
     */
    public record InDoubt(UUID taskId, String storeLabel, String reviewNo, String approvedBody, Instant since) {}

    public enum Failure {
        NOT_FOUND, REVIEW_CHANGED, ALREADY_REPLIED, TASK_IN_PROGRESS, ALREADY_POSTED, STALE, WRONG_STATE,
        FINGERPRINT_MISMATCH, INVALID_BODY, INVALID_META, COMMAND_REUSED, NOT_ASSIGNED, NOT_CLAIMER, POLICY_CHANGED,
        /** The review was deleted when its retention ran out (§6); the server no longer holds anything to answer. */
        REVIEW_PURGED
    }

    public static final class TaskException extends RuntimeException {
        private final Failure failure;

        TaskException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** The PC's statement of what a draft was made from (§17.9 초안 메타). */
    public record Meta(String reviewBodyFingerprint, Instant generatedAt, String knowledgeFp, String policyFp,
                       String voiceFp) {}

    /**
     * A task as the seller and the PC see it, with the review as it stands now — the phone approves from this alone
     * (§4.3 「리뷰 원문 + 초안 + 메타」). Who claimed it is not shown — only that someone has.
     */
    public record TaskView(UUID id, UUID storeId, UUID reviewId, String reviewNo, TaskReview review, State state,
                           int stateVersion, int draftVersion, String draftBody, String draftFingerprint,
                           String reviewBodyFingerprint, Instant generatedAt, String knowledgeFp, String policyFp,
                           String voiceFp, boolean editedOnMobile, List<String> serverChecks,
                           Integer pcRecheckVersion, String pcRecheck, Integer approvedVersion,
                           String approvedFingerprint, Instant approvedAt, boolean claimed, Outcome outcome,
                           Instant checkedAt, String reportMethod, Instant updatedAt) {}

    /** The review a task answers, as the latest upload states it. */
    public record TaskReview(String productName, int rating, String body, ReviewAttachment attachment,
                             Instant writtenAt, String displayState, boolean replied) {}

    /** What a delegate needs to post by hand: the review and the exact approved text. */
    public record DelegateTask(UUID id, UUID storeId, String storeLabel, State state, int stateVersion,
                               String reviewNo, String productName, int rating, String reviewBody,
                               String approvedBody, String approvedFingerprint, Instant approvedAt,
                               boolean mine) {}

    /**
     * The last look before saving in Seller Center. {@code clearToPost} is true only while the task is still the
     * delegate's to post and nothing about the review moved since the draft — its text, its display state (hidden
     * or removed) or its reply flag; anything else means do not post.
     */
    public record Preflight(UUID taskId, State state, String approvedBody, String approvedFingerprint,
                            boolean reviewChanged, boolean displayChanged, boolean alreadyReplied,
                            boolean cancelRequested, boolean clearToPost) {}

    public record PolicyCheck(List<UUID> sentBackForRecheck, List<UUID> withdrawalSuggested) {}

    public record Change(long seq, State state, TaskView task) {}

    public record Changes(List<Change> changes, long cursor, boolean more) {}

    public static final int MAX_PAGE = 200;
    /** A draft dated further ahead than this is not believed. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PROVENANCE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern CHECK_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern COMMAND_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public ReplyTasks(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public ReplyTasks(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /* ── PC: drafts ─────────────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * The PC's draft for a review. A first draft opens a task; a different draft for a task still awaiting approval
     * (or sent back for a recheck) becomes its next version; the same draft again changes nothing. A draft made
     * under other fingerprints than the store's last policy check is not approvable: it lands in
     * {@code RECHECK_NEEDED}.
     */
    @Transactional
    public TaskView submitDraft(UUID storeId, UUID reviewId, String rawBody, Meta meta) {
        String body = body(rawBody);
        validate(meta);
        Optional<ReviewState> review = jdbc.sql("select body_fingerprint, replied, display_state from store_reviews "
                        + "where id = ? and store_id = ?")
                .params(reviewId, storeId)
                .query((rs, n) -> new ReviewState(rs.getString(1), rs.getBoolean(2), rs.getString(3)))
                .optional();
        if (review.isEmpty()) {
            throw new TaskException(StoreReviews.purged(jdbc, storeId, reviewId) ? Failure.REVIEW_PURGED
                    : Failure.NOT_FOUND);
        }
        if (review.get().replied()) {
            throw new TaskException(Failure.ALREADY_REPLIED);
        }
        if (!review.get().bodyFingerprint().equals(meta.reviewBodyFingerprint())) {
            throw new TaskException(Failure.REVIEW_CHANGED);
        }
        Instant now = clock.instant();
        State lands = underCurrentPolicy(storeId, meta.knowledgeFp(), meta.policyFp(), meta.voiceFp())
                ? State.AWAITING_APPROVAL : State.RECHECK_NEEDED;
        Optional<TaskView> open = jdbc.sql(SELECT + " where t.open_review_id = ?").param(reviewId)
                .query(ReplyTasks::view).optional();
        if (open.isEmpty()) {
            if (jdbc.sql("select count(*) from reply_tasks where review_id = ? and state = ?")
                    .params(reviewId, State.POSTED.name()).query(Integer.class).single() > 0) {
                throw new TaskException(Failure.ALREADY_POSTED);
            }
            UUID id = UUID.randomUUID();
            try {
                jdbc.sql("insert into reply_tasks (id, store_id, review_id, state, state_version, draft_version, "
                                + "draft_body, draft_fingerprint, review_body_fingerprint, generated_at, knowledge_fp, "
                                + "policy_fp, voice_fp, edited_on_mobile, server_checks, created_at, updated_at, "
                                + "open_review_id, review_display_state) "
                                + "values (?, ?, ?, ?, 0, 1, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?, ?, ?)")
                        .params(id, storeId, reviewId, lands.name(), body,
                                ReplyText.fingerprint(body), meta.reviewBodyFingerprint(), ts(meta.generatedAt()),
                                meta.knowledgeFp(), meta.policyFp(), meta.voiceFp(), joined(ReplyText.checks(body)),
                                ts(now), ts(now), reviewId, review.get().displayState())
                        .update();
            } catch (DuplicateKeyException e) {
                // Another PC opened a task on this review a moment ago.
                throw new TaskException(Failure.TASK_IN_PROGRESS);
            }
            change(storeId, id, lands, now);
            return get(id).orElseThrow();
        }
        TaskView task = open.get();
        if (task.state() != State.AWAITING_APPROVAL && task.state() != State.RECHECK_NEEDED) {
            throw new TaskException(Failure.TASK_IN_PROGRESS);
        }
        if (task.state() == lands && task.draftBody().equals(body) && sameMeta(task, meta)) {
            return task;
        }
        // The same text drafted again under new fingerprints (a sent-back draft the PC checked again) is still the
        // phone's words if it was: the mark stays, and the PC's check of the new version is recorded after it.
        return newVersion(task, body, meta, task.draftBody().equals(body) && task.editedOnMobile(), lands, now);
    }

    /**
     * A new version written on the phone or the PC. {@code baseVersion} must be the current one. Editing the text is
     * not a recheck: a draft sent back for one stays sent back until the PC drafts it again under the current policy.
     */
    @Transactional
    public TaskView editDraft(UUID taskId, int baseVersion, String rawBody, boolean onMobile) {
        String body = body(rawBody);
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        if (task.state() != State.AWAITING_APPROVAL && task.state() != State.RECHECK_NEEDED) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        if (task.draftVersion() != baseVersion) {
            throw new TaskException(Failure.STALE);
        }
        Meta same = new Meta(task.reviewBodyFingerprint(), task.generatedAt(), task.knowledgeFp(), task.policyFp(),
                task.voiceFp());
        State lands = task.state() == State.AWAITING_APPROVAL && underCurrentPolicy(task)
                ? State.AWAITING_APPROVAL : State.RECHECK_NEEDED;
        return newVersion(task, body, same, onMobile, lands, clock.instant());
    }

    /** The PC's knowledge-based check of a version (typically one edited on the phone). */
    @Transactional
    public TaskView recordPcRecheck(UUID taskId, int draftVersion, boolean passed, List<String> codes) {
        if (codes.size() > 10 || !codes.stream().allMatch(c -> CHECK_CODE.matcher(c).matches())
                || (passed && !codes.isEmpty())) {
            throw new TaskException(Failure.INVALID_META);
        }
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        if (task.state() != State.AWAITING_APPROVAL && task.state() != State.RECHECK_NEEDED) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        if (task.draftVersion() != draftVersion) {
            throw new TaskException(Failure.STALE);
        }
        String result = passed ? "PASSED" : "FLAGGED:" + String.join(",", codes);
        int moved = jdbc.sql("update reply_tasks set pc_recheck_version = ?, pc_recheck = ?, updated_at = ? "
                        + "where id = ? and state_version = ?")
                .params(draftVersion, result, ts(clock.instant()), taskId, task.stateVersion())
                .update();
        if (moved != 1) {
            throw new TaskException(Failure.STALE);
        }
        return get(taskId).orElseThrow();
    }

    /**
     * The PC's current policy fingerprints. Unapproved drafts made under others go back for a recheck; approved
     * ones are only listed as a suggestion to withdraw; claimed ones are left alone.
     */
    @Transactional
    public PolicyCheck policyCheck(UUID storeId, String knowledgeFp, String policyFp, String voiceFp) {
        for (String fp : Arrays.asList(knowledgeFp, policyFp, voiceFp)) {
            if (fp == null || !PROVENANCE.matcher(fp).matches()) {
                throw new TaskException(Failure.INVALID_META);
            }
        }
        Instant now = clock.instant();
        recordPolicy(storeId, knowledgeFp, policyFp, voiceFp, now);
        List<UUID> sentBack = new ArrayList<>();
        List<UUID> suggested = new ArrayList<>();
        for (TaskView task : list(storeId, null)) {
            boolean differs = !task.knowledgeFp().equals(knowledgeFp) || !task.policyFp().equals(policyFp)
                    || !task.voiceFp().equals(voiceFp);
            if (!differs) {
                continue;
            }
            if (task.state() == State.AWAITING_APPROVAL
                    && transition(task, State.AWAITING_APPROVAL, State.RECHECK_NEEDED, "", now)) {
                sentBack.add(task.id());
            } else if (task.state() == State.APPROVED) {
                suggested.add(task.id());
            }
        }
        return new PolicyCheck(List.copyOf(sentBack), List.copyOf(suggested));
    }

    /* ── Seller: approve, cancel ────────────────────────────────────────────────────────────────────────────────── */

    /** Approves exactly {@code version} with {@code fingerprint}. The same {@code commandId} again is a no-op. */
    @Transactional
    public TaskView approve(UUID taskId, UUID accountId, int version, String fingerprint, String commandId) {
        Optional<TaskView> repeat = repeated(commandId, taskId, "APPROVE");
        if (repeat.isPresent()) {
            return repeat.get();
        }
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        if (task.state() != State.AWAITING_APPROVAL) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        if (task.draftVersion() != version) {
            throw new TaskException(Failure.STALE);
        }
        if (!task.draftFingerprint().equals(fingerprint)) {
            throw new TaskException(Failure.FINGERPRINT_MISMATCH);
        }
        if (!underCurrentPolicy(task)) {
            throw new TaskException(Failure.POLICY_CHANGED);
        }
        Instant now = clock.instant();
        if (!transition(task, State.AWAITING_APPROVAL, State.APPROVED, ", approved_version = ?, "
                + "approved_fingerprint = ?, approved_by = ?, approved_at = ?", now, version, fingerprint, accountId,
                ts(now))) {
            throw new TaskException(Failure.STALE);
        }
        remember(commandId, taskId, "APPROVE", now);
        return get(taskId).orElseThrow();
    }

    /** Cancels at once until a delegate has claimed it; after that, asks the delegate to stop. */
    @Transactional
    public TaskView cancel(UUID taskId, String commandId) {
        Optional<TaskView> repeat = repeated(commandId, taskId, "CANCEL");
        if (repeat.isPresent()) {
            return repeat.get();
        }
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        Instant now = clock.instant();
        boolean moved = switch (task.state()) {
            case AWAITING_APPROVAL, RECHECK_NEEDED, APPROVED -> transition(task, task.state(), State.CANCELLED, "", now);
            case CLAIMED -> transition(task, State.CLAIMED, State.CANCEL_REQUESTED, "", now);
            default -> throw new TaskException(Failure.WRONG_STATE);
        };
        if (!moved) {
            throw new TaskException(Failure.STALE);
        }
        remember(commandId, taskId, "CANCEL", now);
        return get(taskId).orElseThrow();
    }

    /* ── Delegate: claim, preflight, report, release ────────────────────────────────────────────────────────────── */

    /**
     * Tasks a delegate may act on, in the stores assigned to them now. {@code APPROVED} lists everyone's; the
     * other states list only the delegate's own.
     */
    @Transactional(readOnly = true)
    public List<DelegateTask> forDelegate(UUID delegateId, State state) {
        String own = state == State.APPROVED ? "" : " and t.claimed_by = ?";
        Object[] params = state == State.APPROVED ? new Object[] {delegateId, state.name()}
                : new Object[] {delegateId, state.name(), delegateId};
        return jdbc.sql("select t.id, t.store_id, s.label, t.state, t.state_version, r.review_no, r.product_name, "
                        + "r.rating, r.body, t.draft_body, t.approved_fingerprint, t.approved_at, t.claimed_by "
                        + "from reply_tasks t join stores s on s.id = t.store_id "
                        + "join store_reviews r on r.id = t.review_id "
                        + "join store_assignments a on a.store_id = t.store_id "
                        + "where a.delegate_id = ? and " + Stores.WORKING + " and t.state = ?" + own + " order by t.approved_at, t.id")
                .params(params)
                .query((rs, n) -> new DelegateTask(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), State.valueOf(rs.getString(4)), rs.getInt(5), rs.getString(6),
                        rs.getString(7), rs.getInt(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        instant(rs.getTimestamp(12)), delegateId.equals(rs.getObject(13, UUID.class))))
                .list();
    }

    /** Takes an approved task. Of two delegates, one wins; the other is told it moved. */
    @Transactional
    public TaskView claim(UUID taskId, UUID delegateId, int stateVersion) {
        TaskView task = assigned(taskId, delegateId);
        if (task.state() != State.APPROVED) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        if (task.stateVersion() != stateVersion) {
            throw new TaskException(Failure.STALE);
        }
        Instant now = clock.instant();
        if (!transition(task, State.APPROVED, State.CLAIMED, ", claimed_by = ?, claimed_at = ?", now, delegateId,
                ts(now))) {
            throw new TaskException(Failure.STALE);
        }
        return get(taskId).orElseThrow();
    }

    /** Hands back a claimed task untouched. */
    @Transactional
    public TaskView release(UUID taskId, UUID delegateId) {
        TaskView task = claimedBy(taskId, delegateId);
        if (task.state() != State.CLAIMED) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        if (!transition(task, State.CLAIMED, State.APPROVED, ", claimed_by = null, claimed_at = null",
                clock.instant())) {
            throw new TaskException(Failure.STALE);
        }
        return get(taskId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Preflight preflight(UUID taskId, UUID delegateId) {
        TaskView task = claimedBy(taskId, delegateId);
        ReviewState review = jdbc.sql("select body_fingerprint, replied, display_state from store_reviews where id = ?")
                .param(task.reviewId())
                .query((rs, n) -> new ReviewState(rs.getString(1), rs.getBoolean(2), rs.getString(3)))
                .single();
        String displayedThen = jdbc.sql("select review_display_state from reply_tasks where id = ?").param(taskId)
                .query((rs, n) -> rs.getString(1)).list().get(0);
        boolean changed = !review.bodyFingerprint().equals(task.reviewBodyFingerprint());
        boolean displayChanged = !Objects.equals(review.displayState(), displayedThen);
        boolean cancelRequested = task.state() == State.CANCEL_REQUESTED;
        boolean clear = task.state() == State.CLAIMED && !changed && !displayChanged && !review.replied();
        return new Preflight(task.id(), task.state(), task.draftBody(), task.approvedFingerprint(), changed,
                displayChanged, review.replied(), cancelRequested, clear);
    }

    /**
     * What the delegate saw in Seller Center. {@code POSTED} must name the approved text's fingerprint. From a
     * cancel request, {@code FAILED} (not posted) settles as {@code CANCELLED}. An {@code UNKNOWN} may later be
     * settled by the same delegate.
     */
    @Transactional
    public TaskView report(UUID taskId, UUID delegateId, Outcome outcome, String postedFingerprint, Instant checkedAt,
                           ReportMethod method, String commandId) {
        // Who may ask comes before what was asked: a repeat answers with the review, so it is answered only to
        // the claimer while the store is still theirs — never to one whose assignment has since ended.
        TaskView task = claimedBy(taskId, delegateId);
        Optional<TaskView> repeat = repeated(commandId, taskId, "REPORT");
        if (repeat.isPresent()) {
            return repeat.get();
        }
        Instant now = clock.instant();
        if (checkedAt == null || checkedAt.isAfter(now.plus(CLOCK_SKEW)) || method == null) {
            throw new TaskException(Failure.INVALID_META);
        }
        if (outcome == Outcome.POSTED && !task.approvedFingerprint().equals(postedFingerprint)) {
            throw new TaskException(Failure.FINGERPRINT_MISMATCH);
        }
        State to = switch (task.state()) {
            case CLAIMED -> State.valueOf(outcome.name());
            case CANCEL_REQUESTED -> outcome == Outcome.FAILED ? State.CANCELLED : State.valueOf(outcome.name());
            case UNKNOWN -> {
                if (outcome == Outcome.UNKNOWN) {
                    throw new TaskException(Failure.WRONG_STATE);
                }
                yield State.valueOf(outcome.name());
            }
            default -> throw new TaskException(Failure.WRONG_STATE);
        };
        if (!transition(task, task.state(), to, ", outcome = ?, posted_fingerprint = ?, checked_at = ?, "
                + "reported_at = ?, report_method = ?", now, outcome.name(),
                outcome == Outcome.POSTED ? postedFingerprint : null, ts(checkedAt), ts(now), method.name())) {
            throw new TaskException(Failure.STALE);
        }
        remember(commandId, taskId, "REPORT", now);
        return get(taskId).orElseThrow();
    }

    /**
     * An admin settles a suspended delegate's {@code UNKNOWN} after looking at the review in Seller Center — §4.2. The
     * suspended account stays shut out; only {@code POSTED} (the approved text is there, word for word) or
     * {@code FAILED} (no reply of ours is there) settles it. Whatever cannot be confirmed stays {@code UNKNOWN}, and
     * so the review stays closed to a second reply. Who settled it and when are kept with the task.
     */
    @Transactional
    public TaskView settleByOperator(UUID taskId, UUID adminId, Outcome outcome) {
        if (outcome == Outcome.UNKNOWN) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        boolean admin = jdbc.sql("select count(*) from delegates where id = ? and role = 'ADMIN' and status = 'ACTIVE'")
                .param(adminId).query(Integer.class).single() > 0;
        if (!admin) {
            throw new TaskException(Failure.NOT_ASSIGNED);
        }
        TaskView task = inDoubt().stream().filter(t -> t.taskId().equals(taskId)).findFirst()
                .flatMap(t -> get(taskId)).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        Instant now = clock.instant();
        if (!transition(task, State.UNKNOWN, State.valueOf(outcome.name()), ", outcome = ?, posted_fingerprint = ?, "
                        + "checked_at = ?, reported_at = ?, report_method = ?, settled_by = ?", now, outcome.name(),
                outcome == Outcome.POSTED ? task.approvedFingerprint() : null, ts(now), ts(now),
                ReportMethod.OPERATOR_SELLER_CENTER_SCREEN.name(), adminId)) {
            throw new TaskException(Failure.STALE);
        }
        return get(taskId).orElseThrow();
    }

    /** Every {@code UNKNOWN} task whose claimer is suspended, oldest first. */
    @Transactional(readOnly = true)
    public List<InDoubt> inDoubt() {
        return jdbc.sql("select t.id, s.label, r.review_no, t.draft_body, t.updated_at from reply_tasks t "
                        + "join stores s on s.id = t.store_id join store_reviews r on r.id = t.review_id "
                        + "join delegates d on d.id = t.claimed_by "
                        + "where t.state = 'UNKNOWN' and d.status = 'SUSPENDED' order by t.updated_at, t.id")
                .query((rs, n) -> new InDoubt(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), instant(rs.getTimestamp(5))))
                .list();
    }

    /* ── reads ──────────────────────────────────────────────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Optional<TaskView> get(UUID taskId) {
        return jdbc.sql(SELECT + " where t.id = ?").param(taskId).query(ReplyTasks::view).optional();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> storeOf(UUID taskId) {
        return jdbc.sql("select store_id from reply_tasks where id = ?").param(taskId).query(UUID.class).optional();
    }

    /** A store's tasks, newest first; {@code state} null for all. */
    @Transactional(readOnly = true)
    public List<TaskView> list(UUID storeId, State state) {
        return state == null
                ? jdbc.sql(SELECT + " where t.store_id = ? order by t.created_at desc, t.id").param(storeId)
                        .query(ReplyTasks::view).list()
                : jdbc.sql(SELECT + " where t.store_id = ? and t.state = ? order by t.created_at desc, t.id")
                        .params(storeId, state.name()).query(ReplyTasks::view).list();
    }

    @Transactional(readOnly = true)
    public Changes changes(UUID storeId, long cursor, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        List<Object[]> rows = jdbc.sql("select seq, task_id, state from reply_task_changes "
                        + "where store_id = ? and seq > ? order by seq limit ?")
                .params(storeId, cursor, size + 1)
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3)})
                .list();
        boolean more = rows.size() > size;
        List<Change> changes = new ArrayList<>();
        for (Object[] row : rows.subList(0, Math.min(rows.size(), size))) {
            changes.add(new Change((Long) row[0], State.valueOf((String) row[2]), get((UUID) row[1]).orElseThrow()));
        }
        long last = changes.isEmpty() ? cursor : changes.get(changes.size() - 1).seq();
        return new Changes(List.copyOf(changes), last, more);
    }

    /* ── events from the review lane and the delegate lane ──────────────────────────────────────────────────────── */

    /**
     * A review changed, was removed from display or was answered on the channel: an unclaimed task on it can no
     * longer be posted as approved. A claimed one is left to the delegate, whose preflight now says not to post.
     */
    public static void invalidateOpen(JdbcClient jdbc, UUID reviewId, Instant at) {
        List<Object[]> open = jdbc.sql("select id, store_id from reply_tasks where open_review_id = ? and state in "
                        + "('AWAITING_APPROVAL', 'RECHECK_NEEDED', 'APPROVED')")
                .param(reviewId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
                .list();
        for (Object[] task : open) {
            int moved = jdbc.sql("update reply_tasks set state = ?, state_version = state_version + 1, updated_at = ?, "
                            + "open_review_id = null, ended_at = ? where id = ? and state in "
                            + "('AWAITING_APPROVAL', 'RECHECK_NEEDED', 'APPROVED')")
                    .params(State.INVALIDATED.name(), ts(at), ts(at), task[0])
                    .update();
            if (moved == 1) {
                insertChange(jdbc, (UUID) task[1], (UUID) task[0], State.INVALIDATED, at);
            }
        }
    }

    /** A suspended delegate's claimed work becomes UNKNOWN — never silently handed to someone else (§2). */
    public static void unknownForSuspended(JdbcClient jdbc, UUID delegateId, Instant at) {
        List<Object[]> claimed = jdbc.sql("select id, store_id from reply_tasks where claimed_by = ? and state in "
                        + "('CLAIMED', 'CANCEL_REQUESTED')")
                .param(delegateId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
                .list();
        for (Object[] task : claimed) {
            int moved = jdbc.sql("update reply_tasks set state = ?, state_version = state_version + 1, updated_at = ? "
                            + "where id = ? and state in ('CLAIMED', 'CANCEL_REQUESTED')")
                    .params(State.UNKNOWN.name(), ts(at), task[0])
                    .update();
            if (moved == 1) {
                insertChange(jdbc, (UUID) task[1], (UUID) task[0], State.UNKNOWN, at);
            }
        }
    }

    /* ── internals ──────────────────────────────────────────────────────────────────────────────────────────────── */

    private static final String SELECT = "select t.*, r.review_no, r.product_name as r_product_name, "
            + "r.rating as r_rating, r.body as r_body, r.attachment_url as r_attachment_url, "
            + "r.attachment_kind as r_attachment_kind, r.written_at as r_written_at, "
            + "r.display_state as r_display_state, r.replied as r_replied from reply_tasks t "
            + "join store_reviews r on r.id = t.review_id";

    private record ReviewState(String bodyFingerprint, boolean replied, String displayState) {}

    /** True unless the store's PC has reported other draft fingerprints since this draft was made. */
    private boolean underCurrentPolicy(UUID storeId, String knowledgeFp, String policyFp, String voiceFp) {
        return jdbc.sql("select knowledge_fp, policy_fp, voice_fp from store_draft_policies where store_id = ?")
                .param(storeId)
                .query((rs, n) -> rs.getString(1).equals(knowledgeFp) && rs.getString(2).equals(policyFp)
                        && rs.getString(3).equals(voiceFp))
                .optional()
                .orElse(true);
    }

    private boolean underCurrentPolicy(TaskView task) {
        return underCurrentPolicy(task.storeId(), task.knowledgeFp(), task.policyFp(), task.voiceFp());
    }

    private void recordPolicy(UUID storeId, String knowledgeFp, String policyFp, String voiceFp, Instant at) {
        String update = "update store_draft_policies set knowledge_fp = ?, policy_fp = ?, voice_fp = ?, "
                + "checked_at = ? where store_id = ?";
        if (jdbc.sql(update).params(knowledgeFp, policyFp, voiceFp, ts(at), storeId).update() == 1) {
            return;
        }
        try {
            jdbc.sql("insert into store_draft_policies (store_id, knowledge_fp, policy_fp, voice_fp, checked_at) "
                            + "values (?, ?, ?, ?, ?)")
                    .params(storeId, knowledgeFp, policyFp, voiceFp, ts(at))
                    .update();
        } catch (DuplicateKeyException e) {
            // Another check of the same store inserted first; this one is the later word.
            jdbc.sql(update).params(knowledgeFp, policyFp, voiceFp, ts(at), storeId).update();
        }
    }

    private TaskView newVersion(TaskView task, String body, Meta meta, boolean onMobile, State lands, Instant now) {
        int moved = jdbc.sql("update reply_tasks set state = ?, state_version = state_version + 1, "
                        + "draft_version = draft_version + 1, draft_body = ?, draft_fingerprint = ?, "
                        + "review_body_fingerprint = ?, generated_at = ?, knowledge_fp = ?, policy_fp = ?, voice_fp = ?, "
                        + "edited_on_mobile = ?, server_checks = ?, pc_recheck_version = null, pc_recheck = null, "
                        + "updated_at = ? where id = ? and state = ? and state_version = ?")
                .params(lands.name(), body, ReplyText.fingerprint(body),
                        meta.reviewBodyFingerprint(), ts(meta.generatedAt()), meta.knowledgeFp(), meta.policyFp(),
                        meta.voiceFp(), onMobile, joined(ReplyText.checks(body)), ts(now), task.id(),
                        task.state().name(), task.stateVersion())
                .update();
        if (moved != 1) {
            throw new TaskException(Failure.STALE);
        }
        change(task.storeId(), task.id(), lands, now);
        return get(task.id()).orElseThrow();
    }

    /**
     * One conditional UPDATE; {@code extra} is a ", col = ?, …" tail whose values follow {@code now}. Entering an
     * ended state releases the review and starts the task's retention (§6) — the first time only.
     */
    private boolean transition(TaskView task, State from, State to, String extra, Instant now, Object... values) {
        boolean ends = State.ENDED.contains(to);
        String open = ends ? ", open_review_id = null, ended_at = coalesce(ended_at, ?)" : "";
        List<Object> params = new ArrayList<>();
        params.add(to.name());
        params.add(ts(now));
        params.addAll(Arrays.asList(values));
        if (ends) {
            params.add(ts(now));
        }
        params.add(task.id());
        params.add(from.name());
        params.add(task.stateVersion());
        params.add(task.storeId());
        int moved = jdbc.sql("update reply_tasks set state = ?, state_version = state_version + 1, updated_at = ?"
                        + extra + open + " where id = ? and state = ? and state_version = ? and store_id = ?")
                .params(params)
                .update();
        if (moved == 1) {
            change(task.storeId(), task.id(), to, now);
        }
        return moved == 1;
    }

    private TaskView assigned(UUID taskId, UUID delegateId) {
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        boolean assigned = jdbc.sql("select count(*) from store_assignments a join stores s on s.id = a.store_id "
                        + "where a.store_id = ? and a.delegate_id = ? and " + Stores.WORKING)
                .params(task.storeId(), delegateId).query(Integer.class).single() > 0;
        if (!assigned) {
            // The same answer as a task that does not exist: an unassigned delegate learns nothing about it.
            throw new TaskException(Failure.NOT_FOUND);
        }
        return task;
    }

    private TaskView claimedBy(UUID taskId, UUID delegateId) {
        TaskView task = assigned(taskId, delegateId);
        UUID claimer = jdbc.sql("select claimed_by from reply_tasks where id = ?").param(taskId)
                .query((rs, n) -> rs.getObject(1, UUID.class)).list().get(0);
        if (!delegateId.equals(claimer)) {
            throw new TaskException(Failure.NOT_CLAIMER);
        }
        return task;
    }

    /** The task as it is now when this exact command was already applied to it; empty when it is new. */
    private Optional<TaskView> repeated(String commandId, UUID taskId, String action) {
        if (commandId == null || !COMMAND_ID.matcher(commandId).matches()) {
            throw new TaskException(Failure.INVALID_META);
        }
        Optional<Object[]> seen = jdbc.sql("select task_id, action from task_commands where command_id = ?")
                .param(commandId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)})
                .optional();
        if (seen.isEmpty()) {
            return Optional.empty();
        }
        if (!seen.get()[0].equals(taskId) || !seen.get()[1].equals(action)) {
            throw new TaskException(Failure.COMMAND_REUSED);
        }
        return get(taskId);
    }

    private void remember(String commandId, UUID taskId, String action, Instant at) {
        jdbc.sql("insert into task_commands (command_id, task_id, action, at) values (?, ?, ?, ?)")
                .params(commandId, taskId, action, ts(at))
                .update();
    }

    private void change(UUID storeId, UUID taskId, State state, Instant at) {
        insertChange(jdbc, storeId, taskId, state, at);
    }

    private static void insertChange(JdbcClient jdbc, UUID storeId, UUID taskId, State state, Instant at) {
        jdbc.sql("insert into reply_task_changes (store_id, task_id, state, at) values (?, ?, ?, ?)")
                .params(storeId, taskId, state.name(), ts(at))
                .update();
    }

    private String body(String raw) {
        String body = ReplyText.normalize(raw);
        if (!ReplyText.storable(body)) {
            throw new TaskException(Failure.INVALID_BODY);
        }
        return body;
    }

    private void validate(Meta meta) {
        if (meta == null || meta.reviewBodyFingerprint() == null
                || !HEX64.matcher(meta.reviewBodyFingerprint()).matches() || meta.generatedAt() == null
                || meta.generatedAt().isAfter(clock.instant().plus(CLOCK_SKEW))) {
            throw new TaskException(Failure.INVALID_META);
        }
        for (String fp : Arrays.asList(meta.knowledgeFp(), meta.policyFp(), meta.voiceFp())) {
            if (fp == null || !PROVENANCE.matcher(fp).matches()) {
                throw new TaskException(Failure.INVALID_META);
            }
        }
    }

    private static boolean sameMeta(TaskView task, Meta meta) {
        return task.reviewBodyFingerprint().equals(meta.reviewBodyFingerprint())
                && task.generatedAt().equals(meta.generatedAt()) && task.knowledgeFp().equals(meta.knowledgeFp())
                && task.policyFp().equals(meta.policyFp()) && task.voiceFp().equals(meta.voiceFp());
    }

    private static String joined(List<String> flags) {
        return String.join(",", flags);
    }

    private static TaskView view(ResultSet rs, int n) throws SQLException {
        String checks = rs.getString("server_checks");
        int recheck = rs.getInt("pc_recheck_version");
        Integer recheckVersion = rs.wasNull() ? null : recheck;
        int approved = rs.getInt("approved_version");
        Integer approvedVersion = rs.wasNull() ? null : approved;
        String outcome = rs.getString("outcome");
        String attachmentUrl = rs.getString("r_attachment_url");
        TaskReview review = new TaskReview(rs.getString("r_product_name"), rs.getInt("r_rating"),
                rs.getString("r_body"), attachmentUrl == null ? null : new ReviewAttachment(attachmentUrl,
                        ReviewAttachment.Kind.valueOf(rs.getString("r_attachment_kind"))),
                instant(rs.getTimestamp("r_written_at")), rs.getString("r_display_state"),
                rs.getBoolean("r_replied"));
        return new TaskView(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("review_id", UUID.class), rs.getString("review_no"), review,
                State.valueOf(rs.getString("state")),
                rs.getInt("state_version"), rs.getInt("draft_version"), rs.getString("draft_body"),
                rs.getString("draft_fingerprint"), rs.getString("review_body_fingerprint"),
                instant(rs.getTimestamp("generated_at")), rs.getString("knowledge_fp"), rs.getString("policy_fp"),
                rs.getString("voice_fp"), rs.getBoolean("edited_on_mobile"),
                checks.isEmpty() ? List.of() : List.of(checks.split(",")), recheckVersion, rs.getString("pc_recheck"),
                approvedVersion, rs.getString("approved_fingerprint"), instant(rs.getTimestamp("approved_at")),
                rs.getObject("claimed_by") != null, outcome == null ? null : Outcome.valueOf(outcome),
                instant(rs.getTimestamp("checked_at")), rs.getString("report_method"),
                instant(rs.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
