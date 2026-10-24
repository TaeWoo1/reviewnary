package com.sellerops.vendor.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
 *   └─review changed · removed · answered ─▶ INVALIDATED   (from APPROVED and HELD too)
 *
 * APPROVED ──PC: approved text conflicts with the current basis──▶ HELD ──new version──▶ AWAITING_APPROVAL · RECHECK_NEEDED
 * CLAIMED  ──same──▶ HOLD_REQUESTED ──report not posted · release──▶ HELD      (POSTED · UNKNOWN as reported)
 *
 * APPROVED, phone version the PC has not checked ──PC check passed──▶ (claimable) · ──PC check found something──▶ HELD
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
 *   <li><b>A phone version reaches no delegate before the PC has checked it</b> (2026-10-23 external review). The
 *       seller may approve it at once — the approval is kept — but it is not listed, claimed or cleared to post
 *       until the PC's knowledge check of that exact version is recorded ({@link #awaitingPcCheck}); a finding then
 *       holds it, since the seller approved without seeing it.</li>
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
        INVALIDATED,
        /** An approval held because the approved text conflicts with what replies are now written from (2026-10-23). */
        HELD,
        /** The same hold after a delegate claimed it: the delegate's preflight says stop, and they confirm. */
        HOLD_REQUESTED;

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
        REVIEW_PURGED,
        /** The PC found a phrase the company never uses in this version; it is fixed before it is approved. */
        FORBIDDEN_PHRASE,
        /** A phone version the PC has not checked yet: approved, but no delegate takes it until the check is in. */
        PC_CHECK_PENDING
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

    /**
     * <b>Why a version was stopped, as the PC that checked it says</b> (owner decision 2026-10-23: a hold always
     * shows what changed, which sentence it touches and what to do). The server judges nothing in it: it keeps the
     * PC's closed codes, the documents that changed, the promise words that lost their ground, the company's
     * forbidden phrases the text carries (2026-10-23) and the sentences that carry them — and refuses a note whose
     * sentences or phrases are not in the task's own text.
     */
    public record BasisNote(List<String> codes, List<ChangedSource> sources, List<String> promises,
                            List<String> sentences, List<String> phrases) {

        public BasisNote(List<String> codes, List<ChangedSource> sources, List<String> promises,
                         List<String> sentences) {
            this(codes, sources, promises, sentences, List.of());
        }
    }

    /** A product or company document a version was written from that has since been edited or taken out of use. */
    public record ChangedSource(String kind, String title, Instant changedAt, boolean removed) {}

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
                           Instant checkedAt, String reportMethod, Instant updatedAt, BasisNote basisNote,
                           Integer basisNoteVersion) {}

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
                            boolean cancelRequested, boolean holdRequested, BasisNote holdNote,
                            boolean clearToPost) {}

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
    static final Set<String> SOURCE_KINDS = Set.of("PRODUCT_KNOWLEDGE", "ORG_POLICY");
    static final int NOTE_MAX_SOURCES = 5;
    static final int NOTE_MAX_TITLE = 120;
    static final int NOTE_MAX_PROMISES = 12;
    static final int NOTE_MAX_PROMISE = 20;
    static final int NOTE_MAX_PHRASE = 60;
    static final int NOTE_MAX_SENTENCES = 3;
    static final int NOTE_MAX_SENTENCE = 300;
    private static final ObjectMapper NOTE_JSON = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

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
        return submitDraft(storeId, reviewId, rawBody, meta, null);
    }

    /**
     * {@link #submitDraft(UUID, UUID, String, Meta)} that refuses to replace a version the PC never saw (2026-10-25:
     * every PC draft is mirrored to the phone, so the phone's edit may be newer). {@code baseVersion} is the open task's
     * version the PC last saw, {@code 0} when it saw none; a mismatch is {@code stale} unless the text is already the
     * current one. Null is the unconditional submit.
     */
    @Transactional
    public TaskView submitDraft(UUID storeId, UUID reviewId, String rawBody, Meta meta, Integer baseVersion) {
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
        if (baseVersion != null && open.map(t -> t.draftVersion() != baseVersion && !t.draftBody().equals(body))
                .orElse(baseVersion != 0)) {
            throw new TaskException(Failure.STALE);
        }
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
        if (!REVISABLE.contains(task.state())) {
            throw new TaskException(Failure.TASK_IN_PROGRESS);
        }
        if (task.state() == lands && task.draftBody().equals(body) && sameMeta(task, meta)) {
            return task;
        }
        // The same text drafted again under new fingerprints (a sent-back draft the PC checked again) is still the
        // phone's words if it was: the mark stays, and the PC's check of the new version is recorded after it.
        return newVersion(task, body, meta, task.draftBody().equals(body) && task.editedOnMobile(), lands, now);
    }

    /** States whose approval is standing: the text is the seller's approved one. */
    static final Set<State> APPROVAL_STANDS = EnumSet.of(State.APPROVED, State.CLAIMED);
    /**
     * True while {@code task}'s current version was written on the phone and the PC has not yet recorded its check
     * of that exact version. Until then no delegate lists, claims or is cleared to post it.
     */
    public static boolean awaitingPcCheck(TaskView task) {
        return task.editedOnMobile() && !Integer.valueOf(task.draftVersion()).equals(task.pcRecheckVersion());
    }

    /** The same rule as {@link #awaitingPcCheck}, as SQL on {@code reply_tasks t}. */
    static final String PC_CHECKED_SQL = "not (t.edited_on_mobile and (t.pc_recheck_version is null "
            + "or t.pc_recheck_version <> t.draft_version))";

    /** States in which the PC may record its check of the current version. */
    static final Set<State> CHECKABLE = EnumSet.of(State.AWAITING_APPROVAL, State.RECHECK_NEEDED, State.APPROVED,
            State.CLAIMED);

    /** States a new version may be written in: not yet approved, sent back, or held after approval. */
    static final Set<State> REVISABLE = EnumSet.of(State.AWAITING_APPROVAL, State.RECHECK_NEEDED, State.HELD);

    /**
     * A new version written on the phone or the PC. {@code baseVersion} must be the current one. Editing the text is
     * not a recheck: a draft sent back for one — or held after approval — goes back for one until the PC drafts it
     * again under the current policy.
     */
    @Transactional
    public TaskView editDraft(UUID taskId, int baseVersion, String rawBody, boolean onMobile) {
        String body = body(rawBody);
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        if (!REVISABLE.contains(task.state())) {
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

    /**
     * The PC's knowledge-based check of a version (typically one edited on the phone). A held version may carry
     * {@code note} — why, for the seller; a passed one may not. An approved or claimed version may be checked too,
     * while its approval stands. A phone version's first check is the one the delegate waits for ({@link
     * #awaitingPcCheck}): passed,
     * it frees the approval; a finding holds it in the same update — {@code APPROVED} to {@code HELD}, a legacy
     * {@code CLAIMED} to {@code HOLD_REQUESTED} — because the seller approved before the finding existed and so never
     * saw it (2026-10-23 external review). A version already checked is only recorded again, moving nothing.
     */
    @Transactional
    public TaskView recordPcRecheck(UUID taskId, int draftVersion, boolean passed, List<String> codes,
                                    BasisNote note) {
        if (codes.size() > 10 || !codes.stream().allMatch(c -> CHECK_CODE.matcher(c).matches())
                || (passed && !codes.isEmpty()) || (passed && note != null)) {
            throw new TaskException(Failure.INVALID_META);
        }
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        if (!CHECKABLE.contains(task.state())) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        if (task.draftVersion() != draftVersion || (APPROVAL_STANDS.contains(task.state())
                && !Integer.valueOf(draftVersion).equals(task.approvedVersion()))) {
            throw new TaskException(Failure.STALE);
        }
        String noteJson = note == null ? null : noteJson(note, task.draftBody());
        String result = passed ? "PASSED" : "FLAGGED:" + String.join(",", codes);
        Instant now = clock.instant();
        if (!passed && APPROVAL_STANDS.contains(task.state()) && awaitingPcCheck(task)) {
            State to = task.state() == State.APPROVED ? State.HELD : State.HOLD_REQUESTED;
            if (!transition(task, task.state(), to, ", pc_recheck_version = ?, pc_recheck = ?, basis_note = ?, "
                    + "basis_note_version = ?", now, draftVersion, result, noteJson,
                    noteJson == null ? null : draftVersion)) {
                throw new TaskException(Failure.STALE);
            }
            return get(taskId).orElseThrow();
        }
        int moved = jdbc.sql("update reply_tasks set pc_recheck_version = ?, pc_recheck = ?, basis_note = ?, "
                        + "basis_note_version = ?, updated_at = ? where id = ? and state_version = ?")
                .params(draftVersion, result, noteJson, noteJson == null ? null : draftVersion,
                        ts(now), taskId, task.stateVersion())
                .update();
        if (moved != 1) {
            throw new TaskException(Failure.STALE);
        }
        return get(taskId).orElseThrow();
    }

    /**
     * The PC's current policy fingerprints. Unapproved drafts made under others go back for a recheck; approved and
     * claimed ones are only listed — the PC checks their text ({@link #checkApproved}) and the server moves nothing
     * on a fingerprint alone.
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
            } else if (task.state() == State.APPROVED || task.state() == State.CLAIMED) {
                suggested.add(task.id());
            }
        }
        return new PolicyCheck(List.copyOf(sentBack), List.copyOf(suggested));
    }

    /**
     * <b>The PC's check of an approved text against what replies are now written from</b> (owner decision
     * 2026-10-23). {@code hold} is the PC's finding of a substantive conflict: an {@code APPROVED} task is held for
     * a new approval, a {@code CLAIMED} one asks its delegate to stop first. Without {@code hold} the note is only
     * kept — a document edit that touches nothing the reply promises leaves the approval standing. Either way the
     * note must name the approved version. The same {@code commandId} again is a no-op.
     */
    @Transactional
    public TaskView checkApproved(UUID taskId, int approvedVersion, boolean hold, BasisNote note, String commandId) {
        Optional<TaskView> repeat = repeated(commandId, taskId, "CHECK_APPROVED");
        if (repeat.isPresent()) {
            return repeat.get();
        }
        if (note == null) {
            throw new TaskException(Failure.INVALID_META);
        }
        TaskView task = get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
        if (task.state() != State.APPROVED && task.state() != State.CLAIMED) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        if (task.approvedVersion() == null || task.approvedVersion() != approvedVersion
                || task.draftVersion() != approvedVersion) {
            throw new TaskException(Failure.STALE);
        }
        String noteJson = noteJson(note, task.draftBody());
        Instant now = clock.instant();
        boolean moved;
        if (hold) {
            State to = task.state() == State.APPROVED ? State.HELD : State.HOLD_REQUESTED;
            moved = transition(task, task.state(), to, ", basis_note = ?, basis_note_version = ?", now, noteJson,
                    approvedVersion);
        } else {
            moved = jdbc.sql("update reply_tasks set basis_note = ?, basis_note_version = ?, updated_at = ? "
                            + "where id = ? and state = ? and state_version = ?")
                    .params(noteJson, approvedVersion, ts(now), taskId, task.state().name(), task.stateVersion())
                    .update() == 1;
        }
        if (!moved) {
            throw new TaskException(Failure.STALE);
        }
        remember(commandId, taskId, "CHECK_APPROVED", now);
        return get(taskId).orElseThrow();
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
        // The server judges no text; it honours the PC's finding on this exact version (owner decision 2026-10-23).
        if (Integer.valueOf(version).equals(task.pcRecheckVersion()) && task.pcRecheck() != null
                && task.pcRecheck().startsWith("FLAGGED:")
                && List.of(task.pcRecheck().substring("FLAGGED:".length()).split(",")).contains("FORBIDDEN_PHRASE")) {
            throw new TaskException(Failure.FORBIDDEN_PHRASE);
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
            case AWAITING_APPROVAL, RECHECK_NEEDED, APPROVED, HELD ->
                    transition(task, task.state(), State.CANCELLED, "", now);
            case CLAIMED, HOLD_REQUESTED -> transition(task, task.state(), State.CANCEL_REQUESTED, "", now);
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
        // An approved phone version waits for the PC's check before any delegate sees it.
        String own = state == State.APPROVED ? " and " + PC_CHECKED_SQL : " and t.claimed_by = ?";
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
        if (awaitingPcCheck(task)) {
            throw new TaskException(Failure.PC_CHECK_PENDING);
        }
        Instant now = clock.instant();
        if (!transition(task, State.APPROVED, State.CLAIMED, ", claimed_by = ?, claimed_at = ?", now, delegateId,
                ts(now))) {
            throw new TaskException(Failure.STALE);
        }
        return get(taskId).orElseThrow();
    }

    /** Hands back a claimed task untouched — to the other delegates, or to the seller when it was held meanwhile. */
    @Transactional
    public TaskView release(UUID taskId, UUID delegateId) {
        TaskView task = claimedBy(taskId, delegateId);
        if (task.state() != State.CLAIMED && task.state() != State.HOLD_REQUESTED) {
            throw new TaskException(Failure.WRONG_STATE);
        }
        State to = task.state() == State.CLAIMED ? State.APPROVED : State.HELD;
        if (!transition(task, task.state(), to, ", claimed_by = null, claimed_at = null", clock.instant())) {
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
        boolean holdRequested = task.state() == State.HOLD_REQUESTED;
        // A claim made before this rule existed may still name an unchecked phone version: not cleared either.
        boolean clear = task.state() == State.CLAIMED && !changed && !displayChanged && !review.replied()
                && !awaitingPcCheck(task);
        return new Preflight(task.id(), task.state(), task.draftBody(), task.approvedFingerprint(), changed,
                displayChanged, review.replied(), cancelRequested, holdRequested,
                holdRequested ? task.basisNote() : null, clear);
    }

    /**
     * What the delegate saw in Seller Center. {@code POSTED} must name the approved text's fingerprint. From a
     * cancel request, {@code FAILED} (not posted) settles as {@code CANCELLED}; from a hold request it returns the
     * task to the seller as {@code HELD}, unclaimed and with no outcome — it was stopped, not tried. An
     * {@code UNKNOWN} may later be settled by the same delegate.
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
        if (task.state() == State.HOLD_REQUESTED && outcome == Outcome.FAILED) {
            if (!transition(task, State.HOLD_REQUESTED, State.HELD, ", claimed_by = null, claimed_at = null, "
                    + "checked_at = ?, reported_at = ?, report_method = ?", now, ts(checkedAt), ts(now),
                    method.name())) {
                throw new TaskException(Failure.STALE);
            }
            remember(commandId, taskId, "REPORT", now);
            return get(taskId).orElseThrow();
        }
        State to = switch (task.state()) {
            case CLAIMED, HOLD_REQUESTED -> State.valueOf(outcome.name());
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
                        + "('AWAITING_APPROVAL', 'RECHECK_NEEDED', 'APPROVED', 'HELD')")
                .param(reviewId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
                .list();
        for (Object[] task : open) {
            int moved = jdbc.sql("update reply_tasks set state = ?, state_version = state_version + 1, updated_at = ?, "
                            + "open_review_id = null, ended_at = ? where id = ? and state in "
                            + "('AWAITING_APPROVAL', 'RECHECK_NEEDED', 'APPROVED', 'HELD')")
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
                        + "('CLAIMED', 'CANCEL_REQUESTED', 'HOLD_REQUESTED')")
                .param(delegateId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
                .list();
        for (Object[] task : claimed) {
            int moved = jdbc.sql("update reply_tasks set state = ?, state_version = state_version + 1, updated_at = ? "
                            + "where id = ? and state in ('CLAIMED', 'CANCEL_REQUESTED', 'HOLD_REQUESTED')")
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
                        + "basis_note = null, basis_note_version = null, "
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
                instant(rs.getTimestamp("updated_at")), note(rs.getString("basis_note")), noteVersion(rs));
    }

    private static Integer noteVersion(ResultSet rs) throws SQLException {
        int v = rs.getInt("basis_note_version");
        return rs.wasNull() ? null : v;
    }

    private static BasisNote note(String json) {
        if (json == null) {
            return null;
        }
        try {
            return NOTE_JSON.readValue(json, BasisNote.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored basis note unreadable", e);
        }
    }

    /**
     * The note as stored, after its shape is checked: closed codes, bounded lists and lengths, known document kinds,
     * no control characters — and every quoted sentence found, whitespace aside, in {@code text}, the version it is
     * about. The server cannot judge the PC's finding; it can refuse a note that quotes something the task never
     * said.
     */
    static String noteJson(BasisNote note, String text) {
        List<String> codes = note.codes() == null ? List.of() : note.codes();
        List<ChangedSource> sources = note.sources() == null ? List.of() : note.sources();
        List<String> promises = note.promises() == null ? List.of() : note.promises();
        List<String> sentences = note.sentences() == null ? List.of() : note.sentences();
        List<String> phrases = note.phrases() == null ? List.of() : note.phrases();
        boolean ok = !codes.isEmpty() && codes.size() <= 4 && codes.stream().allMatch(c -> c != null
                && CHECK_CODE.matcher(c).matches())
                && sources.size() <= NOTE_MAX_SOURCES && sources.stream().allMatch(ReplyTasks::sourceOk)
                && promises.size() <= NOTE_MAX_PROMISES
                && promises.stream().allMatch(p -> plain(p, NOTE_MAX_PROMISE))
                && sentences.size() <= NOTE_MAX_SENTENCES
                && sentences.stream().allMatch(x -> plain(x, NOTE_MAX_SENTENCE) && squeezed(text).contains(squeezed(x)))
                && phrases.size() <= NOTE_MAX_PROMISES
                && phrases.stream().allMatch(x -> plain(x, NOTE_MAX_PHRASE)
                        && squeezed(text).toLowerCase(java.util.Locale.ROOT)
                                .contains(squeezed(x).toLowerCase(java.util.Locale.ROOT)));
        if (!ok) {
            throw new TaskException(Failure.INVALID_META);
        }
        try {
            return NOTE_JSON.writeValueAsString(new BasisNote(List.copyOf(codes), List.copyOf(sources),
                    List.copyOf(promises), List.copyOf(sentences), List.copyOf(phrases)));
        } catch (JsonProcessingException e) {
            throw new TaskException(Failure.INVALID_META);
        }
    }

    private static boolean sourceOk(ChangedSource s) {
        return s != null && SOURCE_KINDS.contains(s.kind()) && plain(s.title(), NOTE_MAX_TITLE)
                && (s.removed() || s.changedAt() != null);
    }

    private static boolean plain(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String squeezed(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
