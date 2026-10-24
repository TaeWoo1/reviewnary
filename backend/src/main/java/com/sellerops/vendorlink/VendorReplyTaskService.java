package com.sellerops.vendorlink;

import com.sellerops.attention.VocItemRef;
import com.sellerops.attention.reply.DelegatedReplyLane;
import com.sellerops.attention.reply.OperatorOutcome;
import com.sellerops.attention.reply.ReviewReplyApprovalService;
import com.sellerops.attention.reply.ReviewReplyApprovalState;
import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftService;
import com.sellerops.attention.reply.ReviewReplyFingerprint;
import com.sellerops.attention.reply.ReviewReplyOutcomeService;
import com.sellerops.attention.reply.ReviewReplyValidation;
import com.sellerops.attention.reply.dto.ReviewReplyDelegationView;
import com.sellerops.attention.triage.ReviewTriage;
import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.attention.triage.TriageDisposition;
import com.sellerops.common.ApiException;
import com.sellerops.common.ReviewBodyFingerprint;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewDraftBasis;
import com.sellerops.review.draft.ReviewDraftBasis.Fingerprints;
import com.sellerops.review.draft.ReviewDraftRecheck;
import com.sellerops.vendorlink.VendorServerClient.DraftMeta;
import com.sellerops.vendorlink.VendorServerClient.TaskChange;
import com.sellerops.vendorlink.VendorServerClient.TaskChanges;
import com.sellerops.vendorlink.VendorServerClient.TaskSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * <b>The PC's half of reply delegation</b> — contract {@code docs/vendor_server_contract_v1.md} §4, with the owner's
 * decisions of 2026-10-21:
 *
 * <ol>
 *   <li><b>One draft, either device</b> (owner decision 2026-10-25, revising D7). Every draft this PC writes for a
 *       review received from the server goes up on its own ({@link #mirror}), so the phone's 「할 일」 shows what this
 *       PC shows; an edit made on the phone comes back as this PC's draft; when both changed, the phone's words lead
 *       and this PC's are set aside for the seller to choose, never dropped. Nothing is <i>approved</i> on its own:
 *       {@link #handOver} is 「답글 등록 요청」 — the version on the seller's screen approved here and now (D9).</li>
 *   <li><b>Only the seller's own 'no reply' is told to the server.</b> {@link #reportNoReply} sends a review whose
 *       triage a person set to {@code NO_ACTION}; an automated judgement is never sent.</li>
 *   <li><b>The seller can take it back from here</b> ({@link #cancel}, D10) as well as from the phone.</li>
 * </ol>
 *
 * <p>The server is the state of record. {@link #sync} copies its task changes here ({@link VendorReplyTask}); while a
 * task is open this PC's own approve and guided-run road stays shut ({@link DelegatedReplyLane}), so one review never
 * gets two replies. A delegate's {@code POSTED} is recorded once in the ordinary reply lane as the existing pair
 * {@code OPERATOR_REPORTED_SUBMITTED} + {@code UNVERIFIED} (v1.14 ⑨) — never as a completion.
 *
 * <p><b>An approval here is the seller's word, as on any other road</b> (2026-10-22 external review). When this PC
 * first sees a task approved — on the phone, with or without an edit, or by 「승인하고 맡기기」 — the approved text
 * becomes the review's draft head in the seller's name and is remembered as {@code USER_APPROVED}, exactly what
 * {@code ReviewReplyService.decideApproval} does for an approval made here. The version is kept on the task
 * ({@code approvedDraftVersion}) for past-reply search. A delegate's post adds no stronger memory: it is a report,
 * not a read-back.
 *
 * <p><b>Draft fingerprints and the recheck</b> (2026-10-22 external review; interview question 34). Every draft goes
 * out with the fingerprints of what review drafts are written from here — the seller's product knowledge, company
 * rules and reply wording ({@link ReviewDraftBasis}). Each pull first reports them when they changed ({@link
 * #reportBasis}), so the server sends unapproved drafts made under others back ({@code RECHECK_NEEDED}); after the
 * sync this PC checks each sent-back text again without a model ({@link ReviewDraftRecheck}). A text that still
 * stands goes back to waiting for approval, unchanged, under the current fingerprints; one that does not is held
 * with closed codes and a reason for the seller.
 *
 * <p><b>An approval is held only for a substantive conflict</b> (owner decision 2026-10-23). After the same pull this
 * PC checks each approved or claimed text that was drafted under other fingerprints ({@link #recheckApproved}). Until a
 * check of meaning exists, "substantive" is what this PC can prove: the approved words promise something (교환 · 환불
 * …) the current evidence no longer supports. That holds the reply for a new approval, and a claimed one asks its
 * delegate to stop first. A cited document that was only edited leaves the approval standing, with a note saying so.
 * Every hold carries what changed, the sentences it touches and the words that lost their ground. A promise the seller
 * already saw flagged and approved anyway is not held again: this PC checks each phone edit under the basis it was
 * written in ({@link #checkPhoneEdits}) before the seller approves it, and that finding is the baseline. A phone version
 * the seller approved before this PC checked it reaches no delegate until the check is on the server, and a finding
 * then holds it (2026-10-23 external review); a check the server refused is not a check and is asked again. Without {@link
 * ReviewDraftBasis} (a test wiring this lane alone) drafts say {@value #UNVERSIONED} and nothing is reported, as
 * before.
 */
@Service
public class VendorReplyTaskService implements DelegatedReplyLane {

    private static final Logger log = LoggerFactory.getLogger(VendorReplyTaskService.class);

    static final String UNVERSIONED = "unversioned";
    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES = 25;
    /** Who wrote what this lane records: the vendor's delegate, reported through the server. */
    static final String ACTOR = "VENDOR_DELEGATE";
    /** A draft may (again) be sent while the review has no task, or its task still awaits approval or has ended unposted. */
    static final Set<String> SENDABLE = Set.of("AWAITING_APPROVAL", "RECHECK_NEEDED", "HELD", "FAILED", "CANCELLED",
            "INVALIDATED");
    /** States in which the seller has approved the task's text — the approval happened, whatever came after. */
    static final Set<String> APPROVED_ONCE = Set.of("APPROVED", "CLAIMED", "CANCEL_REQUESTED", "POSTED", "FAILED",
            "UNKNOWN", "HELD", "HOLD_REQUESTED");
    /**
     * What 「요청 취소」 takes back (§4.2): a request the seller made. A draft only waiting on the phone was never
     * requested (2026-10-25), so there is nothing to cancel.
     */
    static final Set<String> CANCELLABLE = Set.of("APPROVED", "CLAIMED", "HELD", "HOLD_REQUESTED");
    /** Open tasks whose text either device may still change (2026-10-25). */
    static final Set<String> REVISABLE = Set.of("AWAITING_APPROVAL", "RECHECK_NEEDED", "HELD");
    /** Open tasks the seller has not approved: they wait on the phone and do not hold the review here. */
    static final Set<String> UNAPPROVED = Set.of("AWAITING_APPROVAL", "RECHECK_NEEDED");
    /** Who wrote a draft version this PC took over from the phone. */
    static final String PHONE_ACTOR = "SELLER_PHONE";
    /** How long a review's panel read waits before asking the server about it again. */
    static final java.time.Duration ASK_AGAIN = java.time.Duration.ofSeconds(20);
    /** Approved texts this PC checks again when what replies are written from changed. */
    static final Set<String> STANDING_APPROVALS = Set.of("APPROVED", "CLAIMED");
    /** Phone-edited versions this PC checks under the basis they were written in, before or after approval. */
    static final Set<String> PHONE_CHECKED = Set.of("AWAITING_APPROVAL", "APPROVED", "CLAIMED");
    private static final com.fasterxml.jackson.databind.ObjectMapper NOTE_JSON =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Where {@link #draftSaved} runs: off the save's own thread, so a slow server never slows a save. */
    private java.util.concurrent.Executor after = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vendor-draft-mirror");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, Instant> askedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tests run the follow-up inline, or not at all. */
    void setAfter(java.util.concurrent.Executor after) {
        this.after = after;
    }

    /** What the seller's screen may know of a task. */
    public record TaskView(UUID reviewId, String state, int draftVersion, boolean editedOnMobile,
                           boolean outcomeRecorded) {

        static TaskView of(VendorReplyTask t) {
            return new TaskView(t.getReviewId(), t.getState(), t.getDraftVersion(), t.isEditedOnMobile(),
                    t.getOutcomeRecordedAt() != null);
        }
    }

    private final VendorLinkService link;
    private final VendorStoreBindingRepository bindings;
    private final VendorReviewRefRepository refs;
    private final VendorReplyTaskRepository tasks;
    private final ReviewRepository reviews;
    private final ReviewReplyDraftService drafts;
    private final ReviewReplyApprovalService approvals;
    private final ReviewReplyOutcomeService outcomes;
    private final ReviewTriageRepository triages;
    private final Clock clock;
    /** Answer Memory's review-lane hook, or null where a test runs this lane without it. */
    private com.sellerops.review.memory.ReviewAnswerMemoryHook answerMemory;

    @Autowired(required = false)
    public void setAnswerMemory(com.sellerops.review.memory.ReviewAnswerMemoryHook answerMemory) {
        this.answerMemory = answerMemory;
    }

    /** The company's forbidden phrases as review replies obey them; null where a test runs this lane alone. */
    private com.sellerops.review.draft.ReviewStyleGuard styleGuard;

    @Autowired(required = false)
    public void setStyleGuard(com.sellerops.review.draft.ReviewStyleGuard styleGuard) {
        this.styleGuard = styleGuard;
    }

    /** What drafts are written from, and the check of a sent-back text; null where a test runs this lane alone. */
    private ReviewDraftBasis basis;
    private ReviewDraftRecheck recheck;

    @Autowired(required = false)
    public void setDraftBasis(ReviewDraftBasis basis, ReviewDraftRecheck recheck) {
        this.basis = basis;
        this.recheck = recheck;
    }

    @Autowired
    public VendorReplyTaskService(VendorLinkService link, VendorStoreBindingRepository bindings,
                                  VendorReviewRefRepository refs, VendorReplyTaskRepository tasks,
                                  ReviewRepository reviews, ReviewReplyDraftService drafts,
                                  ReviewReplyApprovalService approvals, ReviewReplyOutcomeService outcomes,
                                  ReviewTriageRepository triages) {
        this(link, bindings, refs, tasks, reviews, drafts, approvals, outcomes, triages, Clock.systemUTC());
    }

    VendorReplyTaskService(VendorLinkService link, VendorStoreBindingRepository bindings,
                           VendorReviewRefRepository refs, VendorReplyTaskRepository tasks, ReviewRepository reviews,
                           ReviewReplyDraftService drafts, ReviewReplyApprovalService approvals,
                           ReviewReplyOutcomeService outcomes, ReviewTriageRepository triages, Clock clock) {
        this.link = link;
        this.bindings = bindings;
        this.refs = refs;
        this.tasks = tasks;
        this.reviews = reviews;
        this.drafts = drafts;
        this.approvals = approvals;
        this.outcomes = outcomes;
        this.triages = triages;
        this.clock = clock;
    }

    /**
     * Open, or reported posted. {@code POSTED} ends the task on the server, but until the channel itself says the
     * review is answered (the next upload's 답글여부) nothing else on this PC knows a reply is out — approving here
     * then would be the second reply.
     */
    @Override
    public boolean holds(UUID orgId, UUID reviewId) {
        return tasks.existsByOrgIdAndReviewIdAndStateNotIn(orgId, reviewId, NOT_HOLDING);
    }

    private static final Set<String> UNPOSTED_ENDS = Set.of("FAILED", "CANCELLED", "INVALIDATED");
    /** Ended unposted, or not yet approved: no reply is on its way. */
    private static final Set<String> NOT_HOLDING = Set.of("FAILED", "CANCELLED", "INVALIDATED", "AWAITING_APPROVAL",
            "RECHECK_NEEDED");

    /**
     * Approving here for the copy or guided road takes the review back from the phone: its unapproved task is
     * cancelled first, so the phone cannot approve a second reply. Read first and refused when the phone approved it
     * meanwhile. (Residual: an approval landing on the phone between that read and the cancel is cancelled with it;
     * the seller sees it cancelled, never posted.)
     */
    @Override
    public synchronized void yieldToApprovalHere(UUID orgId, UUID reviewId) {
        Optional<VendorReplyTask> open = tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId)
                .filter(t -> UNAPPROVED.contains(t.getState()));
        if (open.isEmpty()) {
            return;
        }
        VendorReplyTask task = open.get();
        String token = link.installToken()
                .orElseThrow(() -> ApiException.conflict("공급자 서버에 연결된 PC가 아니라 휴대폰의 초안을 거둘 수 없습니다."));
        try {
            TaskSnapshot now = link.client().task(token, task.getVendorTaskId());
            task = apply(orgId, reviewId, task.getVendorStoreId(), now);
            if (!UNAPPROVED.contains(now.state())) {
                throw ApiException.conflict("휴대폰에서 이미 승인한 답글입니다. 화면을 다시 불러와 확인하세요.");
            }
            TaskSnapshot cancelled = link.client().cancel(token, task.getVendorTaskId(),
                    "pc-approve-here:" + task.getVendorTaskId() + ":" + task.getStateVersion());
            apply(orgId, reviewId, task.getVendorStoreId(), cancelled);
        } catch (VendorServerClient.TaskRefused e) {
            throw ApiException.conflict("휴대폰의 초안을 거두지 못해 여기서 승인하지 않았습니다. 화면을 다시 불러오세요.");
        } catch (VendorServerClient.Refused e) {
            throw ApiException.conflict("공급자 서버가 이 PC의 요청을 받지 않았습니다. 연결 상태를 확인하세요.");
        } catch (VendorServerClient.Unreachable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "공급자 서버에 연결하지 못해 휴대폰의 초안을 거둘 수 없습니다. 잠시 후 다시 시도하세요.");
        }
    }

    /** A save here: the phone's copy follows soon, off this thread. */
    @Override
    public void draftSaved(UUID orgId, UUID reviewId) {
        if (link.installToken().isEmpty() || refs.findByReviewId(reviewId).isEmpty()) {
            return;
        }
        askedAt.put(reviewId, clock.instant());
        followUp(orgId, reviewId);
    }

    private void followUp(UUID orgId, UUID reviewId) {
        try {
            after.execute(() -> {
                try {
                    mirrorReview(orgId, reviewId);
                } catch (RuntimeException e) {
                    // The next pull brings the two together again; a save never hears of this.
                    log.warn("vendor reply task: draft not mirrored type={}", e.getClass().getSimpleName());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("vendor reply task: draft mirror not scheduled");
        }
    }

    @Override
    public ReviewReplyDelegationView describe(UUID orgId, UUID reviewId, boolean draftReady) {
        if (link.installToken().isEmpty() || refs.findByReviewId(reviewId).isEmpty()) {
            return null;
        }
        Optional<VendorReplyTask> task = tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId);
        String state = task.map(VendorReplyTask::getState).orElse(null);
        // The seller is looking at this review: ask the server about it soon, so an edit on the phone shows here.
        Instant now = clock.instant();
        Instant last = askedAt.get(reviewId);
        if (last == null || !now.isBefore(last.plus(ASK_AGAIN))) {
            askedAt.put(reviewId, now);
            followUp(orgId, reviewId);
        }
        Optional<ReviewReplyDraft> head = drafts.latest(reviewId);
        boolean fromPhone = head.map(d -> PHONE_ACTOR.equals(d.getCreatedBy())).orElse(false);
        ReviewReplyDelegationView.SetAside aside = task
                .filter(t -> t.getSetAsideLocalVersion() != null && head.isPresent()
                        && Integer.valueOf(head.get().getVersion()).equals(t.getMirroredLocalVersion()))
                .flatMap(t -> drafts.version(reviewId, t.getSetAsideLocalVersion()))
                .map(d -> new ReviewReplyDelegationView.SetAside(d.getVersion(), d.getBody(), d.getCreatedAt()))
                .orElse(null);
        return new ReviewReplyDelegationView(state, task.map(VendorReplyTask::isEditedOnMobile).orElse(false),
                draftReady && (state == null || SENDABLE.contains(state)),
                state != null && CANCELLABLE.contains(state),
                task.map(VendorReplyTask::getBasisNote).map(VendorReplyTaskService::note).orElse(null),
                task.map(VendorReplyTask::getUpdatedAt).orElse(null), fromPhone, aside);
    }

    /** 「휴대폰 문장 쓰기」: the seller keeps the phone's words; this PC's set-aside text is no longer offered. */
    public synchronized void dropSetAside(UUID orgId, UUID reviewId) {
        tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId)
                .filter(t -> t.getSetAsideLocalVersion() != null)
                .ifPresent(t -> {
                    t.setSetAsideLocalVersion(null);
                    tasks.save(t);
                });
    }

    @Override
    public Map<UUID, String> statesOf(UUID orgId, Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty() || link.installToken().isEmpty()) {
            return Map.of();
        }
        Map<UUID, VendorReplyTask> newest = new HashMap<>();
        for (VendorReplyTask task : tasks.findByOrgIdAndReviewIdIn(orgId, reviewIds)) {
            newest.merge(task.getReviewId(), task,
                    (a, b) -> Comparator.comparing(VendorReplyTask::getCreatedAt).compare(a, b) >= 0 ? a : b);
        }
        Map<UUID, String> states = new HashMap<>();
        newest.forEach((reviewId, task) -> states.put(reviewId, task.getState()));
        return states;
    }

    /** The stored note as the screen reads it; an unreadable one is shown as none rather than guessed at. */
    private static ReviewReplyDelegationView.Note note(String json) {
        try {
            return NOTE_JSON.readValue(json, ReviewReplyDelegationView.Note.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("vendor reply task: basis note unreadable type={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The latest task this PC knows for the review, if any. */
    public Optional<TaskView> taskFor(UUID orgId, UUID reviewId) {
        return tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId).map(TaskView::of);
    }

    /**
     * The review's current draft up to the server, as {@link #mirror} sends it, with every refusal said: a first
     * draft opens a task, a newer one becomes its next version while it is unapproved or held.
     */
    synchronized TaskView upload(UUID orgId, UUID reviewId) {
        Sent sent = send(orgId, reviewId, true);
        markMirrored(sent.task(), drafts.latest(reviewId).orElseThrow().getVersion(), true);
        return TaskView.of(sent.task());
    }

    /**
     * 「답글 등록 요청」 (D9): the seller, at this PC, approves exactly the draft version on their screen ({@code
     * baseVersion}) and it goes to the delegate in one press. A newer version here, or an edit on the phone this PC
     * had not taken in, refuses it and shows the newer words instead (2026-10-25) — the seller approves what they
     * read. If the approval is refused after the draft went, the task stays waiting and the seller is told so.
     */
    public synchronized TaskView handOver(UUID orgId, UUID reviewId, int baseVersion) {
        int head = drafts.latest(reviewId).map(ReviewReplyDraft::getVersion).orElse(0);
        if (head != baseVersion) {
            throw ApiException.conflict(CHANGED);
        }
        // An edit on the phone first: taken in as this PC's draft, and the seller is shown it instead of approving.
        if (mirrorReview(orgId, reviewId) == Mirrored.TAKEN_IN) {
            throw ApiException.conflict(CHANGED_ON_PHONE);
        }
        Sent sent;
        try {
            sent = send(orgId, reviewId, false);
        } catch (ApiException e) {
            if (CHANGED_ON_PHONE.equals(e.getMessage())) {
                mirrorReview(orgId, reviewId);
            }
            throw e;
        }
        markMirrored(sent.task(), head, true);
        TaskSnapshot submitted = sent.snapshot();
        if (!"AWAITING_APPROVAL".equals(submitted.state())) {
            throw ApiException.conflict("응대 기준이 바뀌어 이 초안은 다시 확인해야 합니다. 초안을 확인한 뒤 다시 누르세요.");
        }
        TaskSnapshot approved;
        try {
            approved = link.client().approve(sent.token(), submitted.id(), submitted.draftVersion(),
                    submitted.draftFingerprint(), "pc-approve:" + submitted.id() + ":" + submitted.draftVersion());
        } catch (VendorServerClient.TaskRefused e) {
            throw ApiException.conflict("답글은 올렸지만 승인하지 못했습니다. 다시 누르거나 휴대폰에서 승인하세요.");
        } catch (VendorServerClient.Refused e) {
            throw ApiException.conflict("공급자 서버가 이 PC의 요청을 받지 않았습니다. 연결 상태를 확인하세요.");
        } catch (VendorServerClient.Unreachable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "답글은 보냈지만 승인 결과를 받지 못했습니다. 잠시 후 다시 확인하세요.");
        }
        return TaskView.of(apply(orgId, reviewId, sent.binding().getVendorStoreId(), approved));
    }

    /** 「요청 취소」 (D10): ends the task at once until a delegate claims it; after that, asks the delegate to stop. */
    public synchronized TaskView cancel(UUID orgId, UUID reviewId) {
        VendorReplyTask task = tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId)
                .filter(t -> CANCELLABLE.contains(t.getState()))
                .orElseThrow(() -> ApiException.conflict("취소할 수 있는 요청이 없습니다."));
        String token = link.installToken()
                .orElseThrow(() -> ApiException.conflict("공급자 서버에 연결된 PC가 아닙니다."));
        TaskSnapshot snapshot;
        try {
            snapshot = link.client().cancel(token, task.getVendorTaskId(),
                    "pc-cancel:" + task.getVendorTaskId() + ":" + task.getStateVersion());
        } catch (VendorServerClient.TaskRefused e) {
            throw ApiException.conflict("요청 상태가 바뀌어 취소하지 못했습니다. 리뷰를 다시 받아 온 뒤 확인하세요.");
        } catch (VendorServerClient.Refused e) {
            throw ApiException.conflict("공급자 서버가 이 PC의 요청을 받지 않았습니다. 연결 상태를 확인하세요.");
        } catch (VendorServerClient.Unreachable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "공급자 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
        return TaskView.of(apply(orgId, reviewId, task.getVendorStoreId(), snapshot));
    }

    private record Sent(VendorStoreBinding binding, String token, TaskSnapshot snapshot, VendorReplyTask task) {}

    static final String CHANGED = "문장이 바뀌어 승인하지 않았습니다. 바뀐 문장을 보고 다시 누르세요.";
    static final String CHANGED_ON_PHONE = "휴대폰에서 문장이 바뀌어 승인하지 않았습니다. 바뀐 문장을 보고 다시 누르세요.";

    /**
     * The checks every send shares, then the draft submitted under the version this PC last saw; the server's answer
     * is copied here. {@code mirroring}: a draft with a company ban still goes up, marked by this PC's check so the
     * phone cannot approve it either — the phone must not keep an older text the seller has since rewritten.
     */
    private Sent send(UUID orgId, UUID reviewId, boolean mirroring) {
        Review review = reviews.findByIdAndOrgId(reviewId, orgId)
                .orElseThrow(() -> ApiException.notFound("해당 리뷰를 찾을 수 없습니다."));
        VendorReviewRef ref = refs.findByReviewId(reviewId)
                .orElseThrow(() -> ApiException.conflict("공급자 서버에서 받아 온 리뷰만 휴대폰 승인을 요청할 수 있습니다."));
        VendorStoreBinding binding = bindings.findByVendorStoreId(ref.getVendorStoreId())
                .filter(b -> orgId.equals(b.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("해당 리뷰를 찾을 수 없습니다."));
        if (review.getReplyState() == ReviewReplyState.ANSWERED) {
            throw ApiException.conflict("채널에 이미 답글이 등록된 리뷰입니다.");
        }
        if (triages.findByOrgIdAndReviewId(orgId, reviewId).map(ReviewTriage::getDisposition).orElse(null)
                != TriageDisposition.RESPONSE_NEEDED) {
            throw ApiException.conflict("'대응 필요'로 기록된 리뷰만 담당자에게 보낼 수 있습니다.");
        }
        Optional<VendorReplyTask> latest = tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId);
        if (latest.isPresent() && !SENDABLE.contains(latest.get().getState())) {
            throw ApiException.conflict(refusal("POSTED".equals(latest.get().getState()) ? "already_posted"
                    : "task_in_progress"));
        }
        if (approvals.current(orgId, reviewId).filter(a -> a.getState() == ReviewReplyApprovalState.APPROVED)
                .isPresent()) {
            // The seller already approved here, for the copy or guided road. Two roads, two replies.
            throw ApiException.conflict("여기서 이미 승인한 답글입니다. 담당자에게 보내려면 먼저 승인을 해제하세요.");
        }
        if (!ReviewBodyFingerprint.of(review.getBody()).equals(ref.getBodyFingerprint())) {
            throw ApiException.conflict("리뷰 내용이 서버와 다릅니다. 리뷰를 다시 받아 온 뒤 초안을 확인하세요.");
        }
        ReviewReplyDraft draft = drafts.latest(reviewId)
                .orElseThrow(() -> ApiException.conflict("보낼 답글 초안이 없습니다. 먼저 초안을 저장하세요."));
        // The same gate as approving here (2026-10-23): a banned phrase never leaves for the phone or the delegate.
        java.util.List<String> banned = styleGuard == null ? List.of() : styleGuard.forbiddenIn(draft.getBody(), orgId);
        if (!banned.isEmpty() && !mirroring) {
            throw ApiException.conflict("회사가 쓰지 않기로 한 표현이 있어 보낼 수 없습니다: "
                    + String.join(" · ", banned.stream().map(p -> "「" + p + "」").toList()) + ". 문장을 고친 뒤 보내세요.");
        }
        String token = link.installToken()
                .orElseThrow(() -> ApiException.conflict("공급자 서버에 연결된 PC가 아닙니다."));
        TaskSnapshot snapshot;
        try {
            // The seller is looking at this draft and pressing send: it goes under what drafts are written from now,
            // reported first if that changed, so it does not land sent back.
            Fingerprints now = reportBasis(binding, token);
            int seen = latest.filter(t -> REVISABLE.contains(t.getState())).map(VendorReplyTask::getDraftVersion)
                    .orElse(0);
            snapshot = link.client().submitDraft(token, binding.getVendorStoreId(), ref.getVendorReviewId(),
                    draft.getBody(), new DraftMeta(ref.getBodyFingerprint(), draft.getCreatedAt(), now.knowledge(),
                            now.policy(), now.voice()), seen);
            snapshot = checkSent(orgId, review, token, snapshot);
        } catch (VendorServerClient.TaskRefused e) {
            throw ApiException.conflict(refusal(e.code()));
        } catch (VendorServerClient.Refused e) {
            throw ApiException.conflict("공급자 서버가 이 PC의 요청을 받지 않았습니다. 연결 상태를 확인하세요.");
        } catch (VendorServerClient.Unreachable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "공급자 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
        return new Sent(binding, token, snapshot, apply(orgId, reviewId, binding.getVendorStoreId(), snapshot));
    }

    /**
     * This PC's check of a version it just sent: a finding is recorded beside it on the server — the phone shows what
     * to fix, and a company ban keeps the phone from approving it (the server honours the finding). A version written
     * here with nothing found is recorded as nothing; the phone reads an unmarked PC version as passed. Without a
     * check here, or when the server refuses the record, the version goes up unmarked as before.
     */
    private TaskSnapshot checkSent(UUID orgId, Review review, String token, TaskSnapshot sent) {
        if (!REVISABLE.contains(sent.state()) || Integer.valueOf(sent.draftVersion()).equals(sent.pcRecheckVersion())) {
            return sent;
        }
        VendorServerClient.BasisNote finding;
        if (recheck != null) {
            ReviewDraftRecheck.Result result = recheck.check(orgId, review,
                    VocPreviewSanitizer.redactFullBody(review.getBody()).text(), sent.draftBody());
            finding = result.passed() ? null : note(result);
        } else {
            // No knowledge check wired here: the company's bans are still this PC's to enforce.
            List<String> banned = styleGuard == null ? List.of() : styleGuard.forbiddenIn(sent.draftBody(), orgId);
            finding = banned.isEmpty() ? null : new VendorServerClient.BasisNote(
                    List.of(ReviewDraftRecheck.FORBIDDEN_PHRASE), List.of(), List.of(),
                    ReviewDraftRecheck.sentencesWith(ReviewReplyValidation.normalize(sent.draftBody()), banned), banned);
        }
        if (finding == null) {
            return sent;
        }
        try {
            return link.client().pcRecheck(token, sent.id(), sent.draftVersion(), false, finding.codes(), finding);
        } catch (VendorServerClient.TaskRefused e) {
            log.warn("vendor reply task: sent draft check not recorded code={}", e.code());
            return sent;
        }
    }

    /** How one review's two copies were brought together. */
    enum Mirrored { NOTHING, SENT, TAKEN_IN }

    /**
     * <b>One draft, either device</b> (owner decision 2026-10-25). Each of the store's reviews with a draft for a
     * reply: a draft written here goes up; an edit on the phone becomes this PC's draft; when both changed since they
     * were last together, the phone's words lead and this PC's version is set aside for the seller. Approved, posted
     * or ended tasks are never touched — and a review the seller withdrew from the phone is not sent again on its
     * own. Runs after {@link #sync}. Returns how many reviews moved.
     */
    synchronized int mirror(VendorStoreBinding binding, String token) {
        int moved = 0;
        for (VendorReviewRef ref : refs.draftedForReply(binding.getVendorStoreId())) {
            try {
                moved += mirrorOne(binding, token, ref) == Mirrored.NOTHING ? 0 : 1;
            } catch (ApiException | VendorServerClient.TaskRefused e) {
                // This review only (changed under the server, purged, banned on approval …); the next pull asks again.
                log.warn("vendor reply task: draft not mirrored store={} type={}", binding.getVendorStoreId(),
                        e.getClass().getSimpleName());
            }
        }
        return moved;
    }

    /** One review brought together now — after a save here, a hand-over, or a seller opening it. */
    synchronized Mirrored mirrorReview(UUID orgId, UUID reviewId) {
        Optional<String> token = link.installToken();
        VendorReviewRef ref = refs.findByReviewId(reviewId).orElse(null);
        VendorStoreBinding binding = ref == null ? null : bindings.findByVendorStoreId(ref.getVendorStoreId())
                .filter(b -> orgId.equals(b.getOrgId())).orElse(null);
        if (token.isEmpty() || binding == null) {
            return Mirrored.NOTHING;
        }
        sync(binding, token.get());
        return mirrorOne(binding, token.get(), ref);
    }

    private Mirrored mirrorOne(VendorStoreBinding binding, String token, VendorReviewRef ref) {
        UUID orgId = binding.getOrgId();
        UUID reviewId = ref.getReviewId();
        Review review = reviews.findByIdAndOrgId(reviewId, orgId).orElse(null);
        ReviewReplyDraft head = drafts.latest(reviewId).orElse(null);
        if (review == null || head == null || review.getReplyState() == ReviewReplyState.ANSWERED
                || triages.findByOrgIdAndReviewId(orgId, reviewId).map(ReviewTriage::getDisposition).orElse(null)
                        != TriageDisposition.RESPONSE_NEEDED
                || approvals.current(orgId, reviewId).filter(a -> a.getState() == ReviewReplyApprovalState.APPROVED)
                        .isPresent()
                || !ReviewBodyFingerprint.of(review.getBody()).equals(ref.getBodyFingerprint())) {
            return Mirrored.NOTHING;
        }
        VendorReplyTask task = tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId).orElse(null);
        if (task == null) {
            markMirrored(send(orgId, reviewId, true).task(), head.getVersion(), true);
            return Mirrored.SENT;
        }
        if (!REVISABLE.contains(task.getState())) {
            return Mirrored.NOTHING;
        }
        if (task.getDraftFingerprint().equals(head.getContentFingerprint())) {
            if (!Integer.valueOf(task.getDraftVersion()).equals(task.getMirroredServerVersion())
                    || !Integer.valueOf(head.getVersion()).equals(task.getMirroredLocalVersion())) {
                markMirrored(task, head.getVersion(), false);
            }
            return Mirrored.NOTHING;
        }
        // Before the first time (a task sent before 2026-10-25) the phone's mark says which side wrote last.
        boolean phone = task.getMirroredServerVersion() == null ? task.isEditedOnMobile()
                : task.getDraftVersion() != task.getMirroredServerVersion();
        boolean here = task.getMirroredLocalVersion() == null ? !task.isEditedOnMobile()
                : head.getVersion() != task.getMirroredLocalVersion();
        if (!phone) {
            markMirrored(send(orgId, reviewId, true).task(), head.getVersion(), true);
            return Mirrored.SENT;
        }
        TaskSnapshot current = link.client().task(token, task.getVendorTaskId());
        task = apply(orgId, reviewId, binding.getVendorStoreId(), current);
        if (!REVISABLE.contains(current.state())) {
            return Mirrored.NOTHING;
        }
        // The phone's words become this PC's draft — a person's sentence; a version written here meanwhile is kept
        // aside for the seller to choose, not overwritten in silence.
        drafts.saveAs(orgId, reviewId, PHONE_ACTOR, current.draftBody(), head.getVersion(),
                ReviewReplyDraftService.Provenance.seller());
        int taken = drafts.latest(reviewId).orElseThrow().getVersion();
        task.setSetAsideLocalVersion(here ? Integer.valueOf(head.getVersion()) : null);
        markMirrored(task, taken, false);
        return Mirrored.TAKEN_IN;
    }

    /** The two copies are together at the task's current server version and this PC's draft {@code localVersion}. */
    private void markMirrored(VendorReplyTask task, int localVersion, boolean clearSetAside) {
        task.setMirroredServerVersion(task.getDraftVersion());
        task.setMirroredLocalVersion(localVersion);
        if (clearSetAside) {
            task.setSetAsideLocalVersion(null);
        }
        tasks.save(task);
    }

    /** Copies the store's task changes since the last sync. Returns how many were applied. */
    int sync(VendorStoreBinding binding, String token) {
        long cursor = binding.getTaskCursor();
        int applied = 0;
        boolean more = true;
        for (int page = 0; page < MAX_PAGES && more; page++) {
            TaskChanges changes = link.client().taskChanges(token, binding.getVendorStoreId(), cursor, PAGE_SIZE);
            long stored = cursor;
            boolean stop = false;
            for (TaskChange change : changes.changes()) {
                Optional<VendorReviewRef> ref = refs.findByVendorReviewId(change.task().reviewId());
                if (ref.isEmpty()) {
                    // A review this PC has not pulled yet. Stop here; the next sync, after the next review pull, resumes.
                    stop = true;
                    break;
                }
                try {
                    apply(binding.getOrgId(), ref.get().getReviewId(), binding.getVendorStoreId(), change.task());
                } catch (RuntimeException e) {
                    log.warn("vendor reply task: change not applied store={} seq={} type={}",
                            binding.getVendorStoreId(), change.seq(), e.getClass().getSimpleName());
                    stop = true;
                    break;
                }
                stored = change.seq();
                applied++;
            }
            if (stored != cursor) {
                cursor = stored;
                binding.setTaskCursor(cursor);
                bindings.save(binding);
            }
            more = !stop && changes.more();
        }
        return applied;
    }

    /** What review drafts are written from on this PC now. */
    private Fingerprints fingerprints(UUID orgId) {
        return basis == null ? new Fingerprints(UNVERSIONED, UNVERSIONED, UNVERSIONED) : basis.of(orgId);
    }

    /**
     * Reports the store's draft fingerprints when they differ from what this PC last reported; the server sends
     * waiting drafts made under others back. A refusal is logged and retried on the next pull — it never stops one.
     */
    Fingerprints reportBasis(VendorStoreBinding binding, String token) {
        Fingerprints now = fingerprints(binding.getOrgId());
        if (basis == null || now.combined().equals(binding.getReportedDraftBasis())) {
            return now;
        }
        try {
            VendorServerClient.PolicyCheck answer = link.client().policyCheck(token, binding.getVendorStoreId(),
                    now.knowledge(), now.policy(), now.voice());
            binding.setReportedDraftBasis(now.combined());
            bindings.save(binding);
            log.info("vendor reply task: draft basis reported store={} sentBack={} withdrawalSuggested={}",
                    binding.getVendorStoreId(), answer.sentBackForRecheck().size(),
                    answer.withdrawalSuggested().size());
        } catch (VendorServerClient.TaskRefused e) {
            log.warn("vendor reply task: draft basis refused store={} code={}", binding.getVendorStoreId(), e.code());
        }
        return now;
    }

    /**
     * Checks again each of the store's sent-back tasks this PC has not checked at its current version and
     * fingerprints. Passed: the same text is drafted again under the current fingerprints, back to waiting for
     * approval (a phone edit stays marked as one and gets this PC's pass). Held: the codes are recorded on the
     * server and the task stays sent back for the seller. Returns how many went back to waiting.
     */
    synchronized int recheckSentBack(VendorStoreBinding binding, String token) {
        if (basis == null || recheck == null) {
            return 0;
        }
        UUID orgId = binding.getOrgId();
        UUID storeId = binding.getVendorStoreId();
        Fingerprints now = fingerprints(orgId);
        int passed = 0;
        for (VendorReplyTask copy : tasks.findByVendorStoreIdAndState(storeId, "RECHECK_NEEDED")) {
            if (Integer.valueOf(copy.getDraftVersion()).equals(copy.getRecheckedVersion())
                    && now.combined().equals(copy.getRecheckedBasis())) {
                continue;
            }
            TaskSnapshot current = link.client().task(token, copy.getVendorTaskId());
            VendorReplyTask task = apply(orgId, copy.getReviewId(), storeId, current);
            if (!"RECHECK_NEEDED".equals(task.getState())) {
                continue;
            }
            Review review = reviews.findByIdAndOrgId(task.getReviewId(), orgId).orElse(null);
            VendorReviewRef ref = refs.findByReviewId(task.getReviewId()).orElse(null);
            TaskSnapshot after = null;
            if (review != null && ref != null
                    && ReviewBodyFingerprint.of(review.getBody()).equals(ref.getBodyFingerprint())) {
                ReviewDraftRecheck.Result result = recheck.check(orgId, review,
                        VocPreviewSanitizer.redactFullBody(review.getBody()).text(), current.draftBody());
                try {
                    if (result.passed()) {
                        after = link.client().submitDraft(token, storeId, ref.getVendorReviewId(),
                                current.draftBody(), new DraftMeta(ref.getBodyFingerprint(), clock.instant(),
                                        now.knowledge(), now.policy(), now.voice()), current.draftVersion());
                        if (after.editedOnMobile() && "AWAITING_APPROVAL".equals(after.state())) {
                            after = link.client().pcRecheck(token, after.id(), after.draftVersion(), true, List.of(),
                                    null);
                        }
                        passed += "AWAITING_APPROVAL".equals(after.state()) ? 1 : 0;
                    } else {
                        after = link.client().pcRecheck(token, current.id(), current.draftVersion(), false,
                                result.codes(), note(result));
                    }
                } catch (VendorServerClient.TaskRefused e) {
                    // Not recorded on the server, so not checked: the next pull asks again (2026-10-23 review).
                    log.warn("vendor reply task: recheck not recorded store={} code={}", storeId, e.code());
                    continue;
                }
            }
            // Otherwise the review changed since it was pulled: the next upload invalidates the task, or the seller
            // drafts it again. Either way this version is not checked here twice.
            VendorReplyTask checked = after == null ? task : apply(orgId, task.getReviewId(), storeId, after);
            checked.setRecheckedVersion(after == null ? current.draftVersion() : after.draftVersion());
            checked.setRecheckedBasis(now.combined());
            tasks.save(checked);
        }
        return passed;
    }

    /**
     * Checks each of the store's approved or claimed texts that was drafted under other fingerprints than this PC's
     * now, once per version and fingerprints (owner decision 2026-10-23). A promise the current evidence no longer
     * supports holds it — or, claimed, asks its delegate to stop; a cited document that was only edited is noted and
     * the approval stands. Returns how many were held.
     */
    synchronized int recheckApproved(VendorStoreBinding binding, String token) {
        if (basis == null || recheck == null) {
            return 0;
        }
        UUID orgId = binding.getOrgId();
        UUID storeId = binding.getVendorStoreId();
        Fingerprints now = fingerprints(orgId);
        int held = 0;
        for (VendorReplyTask copy : tasks.findByVendorStoreIdAndStateIn(storeId, STANDING_APPROVALS)) {
            if (Integer.valueOf(copy.getDraftVersion()).equals(copy.getRecheckedVersion())
                    && now.combined().equals(copy.getRecheckedBasis())) {
                continue;
            }
            TaskSnapshot current = link.client().task(token, copy.getVendorTaskId());
            VendorReplyTask task = apply(orgId, copy.getReviewId(), storeId, current);
            TaskSnapshot after = null;
            if (STANDING_APPROVALS.contains(current.state()) && current.approvedVersion() != null
                    && current.approvedVersion() == current.draftVersion()
                    && !now.combined().equals(current.draftBasis())) {
                try {
                    after = checkOne(orgId, storeId, token, task, current);
                } catch (VendorServerClient.TaskRefused e) {
                    // The finding did not reach the server: this version is not checked, and the next pull tries again.
                    log.warn("vendor reply task: approved check not recorded store={} code={}", storeId, e.code());
                    continue;
                }
                held += after != null && !STANDING_APPROVALS.contains(after.state()) ? 1 : 0;
            }
            VendorReplyTask checked = after == null ? task : apply(orgId, task.getReviewId(), storeId, after);
            checked.setRecheckedVersion(current.draftVersion());
            checked.setRecheckedBasis(now.combined());
            tasks.save(checked);
        }
        return held;
    }

    /**
     * Checks each phone-edited version this PC has not yet checked, while what replies are written from is still
     * what it was written in (2026-10-23). The finding — passed, or a promise the evidence does not support, with its
     * sentence — goes to the server beside the version, so the phone shows it before the seller approves, and a
     * later basis change does not hold the same promise again. Returns how many were checked.
     */
    synchronized int checkPhoneEdits(VendorStoreBinding binding, String token) {
        if (basis == null || recheck == null) {
            return 0;
        }
        UUID orgId = binding.getOrgId();
        UUID storeId = binding.getVendorStoreId();
        Fingerprints now = fingerprints(orgId);
        int checked = 0;
        for (VendorReplyTask copy : tasks.findByVendorStoreIdAndStateIn(storeId, PHONE_CHECKED)) {
            if (!copy.isEditedOnMobile() || Integer.valueOf(copy.getDraftVersion()).equals(copy.getPcCheckedVersion())) {
                continue;
            }
            checked += checkPhoneEdit(orgId, storeId, token, copy.getVendorTaskId(), now) ? 1 : 0;
        }
        return checked;
    }

    /**
     * One phone-edited task checked, if its current version is due. The server keeps an approved one from every
     * delegate until this check is recorded, and holds it in the same step when the check finds something — the
     * seller approved before the finding existed (2026-10-23 external review). Marked checked here only once the
     * server has the finding, or when there is nothing this PC can check; a refused finding is asked again next pull.
     */
    private boolean checkPhoneEdit(UUID orgId, UUID storeId, String token, UUID vendorTaskId, Fingerprints now) {
        VendorReplyTask copy = tasks.findByVendorTaskId(vendorTaskId).orElseThrow();
        TaskSnapshot current = link.client().task(token, vendorTaskId);
        VendorReplyTask task = apply(orgId, copy.getReviewId(), storeId, current);
        boolean due = PHONE_CHECKED.contains(current.state()) && current.editedOnMobile()
                && !Integer.valueOf(current.draftVersion()).equals(current.pcRecheckVersion())
                && now.combined().equals(current.draftBasis())
                && (!STANDING_APPROVALS.contains(current.state())
                        || Integer.valueOf(current.draftVersion()).equals(current.approvedVersion()));
        Review review = reviews.findByIdAndOrgId(task.getReviewId(), orgId).orElse(null);
        VendorReviewRef ref = refs.findByReviewId(task.getReviewId()).orElse(null);
        boolean checked = false;
        if (due && review != null && ref != null
                && ReviewBodyFingerprint.of(review.getBody()).equals(ref.getBodyFingerprint())) {
            ReviewDraftRecheck.Result result = recheck.check(orgId, review,
                    VocPreviewSanitizer.redactFullBody(review.getBody()).text(), current.draftBody());
            try {
                TaskSnapshot after = link.client().pcRecheck(token, current.id(), current.draftVersion(),
                        result.passed(), result.codes(), result.passed() ? null : note(result));
                task = apply(orgId, task.getReviewId(), storeId, after);
                checked = true;
            } catch (VendorServerClient.TaskRefused e) {
                log.warn("vendor reply task: phone edit check not recorded store={} code={}", storeId, e.code());
                return false;
            }
        }
        // Checked, or not checkable here (the basis moved on, or the review did): either way not asked again for
        // this version — a moved basis is the approved check's to judge ({@link #checkOne}).
        task.setPcCheckedVersion(current.draftVersion());
        tasks.save(task);
        return checked;
    }

    /**
     * The promise words a PC flagged on {@code current}'s approved version before it was approved — what the seller
     * was shown and approved anyway. Forbidden phrases are never in it: they are absolute.
     */
    private static Set<String> knownFindings(TaskSnapshot current) {
        if (current.pcRecheck() == null || !current.pcRecheck().startsWith("FLAGGED")
                || current.basisNoteJson() == null
                || !java.util.Objects.equals(current.pcRecheckVersion(), current.approvedVersion())) {
            return Set.of();
        }
        ReviewReplyDelegationView.Note seen = note(current.basisNoteJson());
        if (seen == null) {
            return Set.of();
        }
        // Promises only. A forbidden phrase is an absolute ban: approved past a flag or not, it is held (2026-10-23).
        return seen.promises() == null ? Set.of() : Set.copyOf(seen.promises());
    }

    /**
     * One approved text checked; the server's answer, or null when nothing was sent. A refusal is the caller's: it is
     * not a check.
     */
    private TaskSnapshot checkOne(UUID orgId, UUID storeId, String token, VendorReplyTask task, TaskSnapshot current) {
        Review review = reviews.findByIdAndOrgId(task.getReviewId(), orgId).orElse(null);
        VendorReviewRef ref = refs.findByReviewId(task.getReviewId()).orElse(null);
        if (review == null || ref == null
                || !ReviewBodyFingerprint.of(review.getBody()).equals(ref.getBodyFingerprint())) {
            // The review moved since it was pulled; the next upload invalidates the task or the delegate's check stops it.
            return null;
        }
        ReviewDraftRecheck.Result result = recheck.check(orgId, review,
                VocPreviewSanitizer.redactFullBody(review.getBody()).text(), current.draftBody());
        if (current.editedOnMobile() && !java.util.Objects.equals(current.pcRecheckVersion(), current.draftVersion())) {
            // A phone version approved before any check, under a basis that has since moved: this is its first check,
            // the one the delegate waits for. Passed frees it; a finding holds it on the server — nothing was seen.
            return link.client().pcRecheck(token, current.id(), current.draftVersion(), result.passed(),
                    result.codes(), result.passed() ? null : note(result));
        }
        if (result.passed()) {
            return null;
        }
        // A promise or phrase the seller was shown and approved anyway is their word, not a new conflict. A promise
        // that lost its ground, or a phrase banned since, is.
        Set<String> known = knownFindings(current);
        List<String> fresh = result.promises().stream().filter(p -> !known.contains(p)).toList();
        List<String> banned = result.phrases().stream().filter(p -> !known.contains(p)).toList();
        boolean hold = !fresh.isEmpty() || !banned.isEmpty();
        boolean sourceChanged = result.codes().contains(ReviewDraftRecheck.SOURCE_CHANGED);
        if (!hold && !sourceChanged) {
            return null;
        }
        VendorServerClient.BasisNote note;
        if (hold) {
            List<String> codes = new java.util.ArrayList<>();
            if (sourceChanged) {
                codes.add(ReviewDraftRecheck.SOURCE_CHANGED);
            }
            if (!fresh.isEmpty()) {
                codes.add(ReviewDraftRecheck.UNSUPPORTED_PROMISE);
            }
            if (!banned.isEmpty()) {
                codes.add(ReviewDraftRecheck.FORBIDDEN_PHRASE);
            }
            List<String> marked = new java.util.ArrayList<>(fresh);
            marked.addAll(banned);
            note = new VendorServerClient.BasisNote(codes, sources(result), fresh,
                    ReviewDraftRecheck.sentencesWith(ReviewReplyValidation.normalize(current.draftBody()), marked),
                    banned);
        } else {
            note = new VendorServerClient.BasisNote(List.of(ReviewDraftRecheck.SOURCE_CHANGED), sources(result),
                    List.of(), List.of());
        }
        return link.client().checkApproved(token, current.id(), current.approvedVersion(), hold, note,
                "pc-check:" + current.id() + ":" + current.approvedVersion() + ":" + current.stateVersion());
    }

    /** What the seller is shown about a finding: the server's note. */
    private static VendorServerClient.BasisNote note(ReviewDraftRecheck.Result result) {
        return new VendorServerClient.BasisNote(result.codes(), sources(result), result.promises(), result.sentences(),
                result.phrases());
    }

    private static List<VendorServerClient.ChangedSource> sources(ReviewDraftRecheck.Result result) {
        return result.changed().stream()
                .map(c -> new VendorServerClient.ChangedSource(c.kind(), c.title(), c.changedAt(), c.removed()))
                .toList();
    }

    /** Tells the server about the seller's own 'no reply' decisions in this store. Returns how many were sent. */
    int reportNoReply(VendorStoreBinding binding, String token) {
        int sent = 0;
        for (VendorReviewRef ref : refs.sellerNoReplyUnreported(binding.getVendorStoreId())) {
            link.client().markNoReplyNeeded(token, binding.getVendorStoreId(), ref.getVendorReviewId(),
                    "no-reply:" + ref.getVendorReviewId());
            ref.setNoReplyReportedAt(clock.instant());
            refs.save(ref);
            sent++;
        }
        return sent;
    }

    /** The task as the server now reports it; a first POSTED is recorded in the reply lane. */
    private VendorReplyTask apply(UUID orgId, UUID reviewId, UUID storeId, TaskSnapshot snapshot) {
        VendorReplyTask task = tasks.findByVendorTaskId(snapshot.id()).orElseGet(() -> {
            VendorReplyTask fresh = new VendorReplyTask();
            fresh.setVendorTaskId(snapshot.id());
            fresh.setOrgId(orgId);
            fresh.setReviewId(reviewId);
            fresh.setVendorStoreId(storeId);
            return fresh;
        });
        if (!task.getReviewId().equals(reviewId) || !task.getOrgId().equals(orgId)) {
            throw new IllegalStateException("task bound to another review");
        }
        // Changes can be re-read; never step a task back to an older state version.
        if (task.getId() != null && snapshot.stateVersion() < task.getStateVersion()) {
            return task;
        }
        boolean newlyApproved = APPROVED_ONCE.contains(snapshot.state()) && snapshot.approvedFingerprint() != null
                && (task.getApprovedDraftVersion() == null
                        || !snapshot.approvedFingerprint().equals(task.getApprovedFingerprint()));
        task.setState(snapshot.state());
        task.setStateVersion(snapshot.stateVersion());
        task.setDraftVersion(snapshot.draftVersion());
        task.setDraftFingerprint(snapshot.draftFingerprint());
        task.setApprovedFingerprint(snapshot.approvedFingerprint());
        task.setEditedOnMobile(snapshot.editedOnMobile());
        task.setBasisNote(snapshot.basisNoteJson());
        if (newlyApproved) {
            rememberApproved(orgId, reviewId, task, snapshot);
        }
        if ("POSTED".equals(snapshot.state()) && task.getOutcomeRecordedAt() == null) {
            recordPosted(orgId, reviewId, task, snapshot);
            task.setOutcomeRecordedAt(clock.instant());
        }
        return tasks.save(task);
    }

    /**
     * The seller approved this text: it becomes the review's draft head in the seller's name (the phone's words, if
     * they edited there) and is remembered as the seller's answer. Remembering is best effort and never fails the
     * sync — the approval is the server's fact either way.
     */
    private void rememberApproved(UUID orgId, UUID reviewId, VendorReplyTask task, TaskSnapshot snapshot) {
        String body = ReviewReplyValidation.normalize(snapshot.draftBody());
        if (!ReviewReplyFingerprint.of(body).equals(snapshot.approvedFingerprint())) {
            // The snapshot's text is not the approved one (a later version in flight): nothing to stand behind yet.
            return;
        }
        ReviewReplyDraft approved = approvedHead(orgId, reviewId, body);
        task.setApprovedDraftVersion(approved.getVersion());
        if (answerMemory == null) {
            return;
        }
        reviews.findByIdAndOrgId(reviewId, orgId).ifPresent(review -> {
            try {
                answerMemory.rememberApproved(review, approved, null);
            } catch (RuntimeException e) {
                log.warn("vendor reply task: approval not remembered type={}", e.getClass().getSimpleName());
            }
        });
    }

    /** The draft holding exactly {@code body}: the head if it already does, else a new seller version on top. */
    private ReviewReplyDraft approvedHead(UUID orgId, UUID reviewId, String body) {
        String fingerprint = ReviewReplyFingerprint.of(body);
        ReviewReplyDraft head = drafts.latest(reviewId).orElse(null);
        if (head != null && fingerprint.equals(head.getContentFingerprint())) {
            return head;
        }
        // The seller's approved words — a person's sentence, whoever drafted the version before it.
        drafts.saveAs(orgId, reviewId, ACTOR, body, head == null ? 0 : head.getVersion(),
                ReviewReplyDraftService.Provenance.seller());
        return drafts.latest(reviewId).orElseThrow();
    }

    /**
     * The delegate posted the approved text. The reply lane records an outcome against a draft version, so the
     * approved text becomes the review's draft head if it is not already (a phone edit), then a binding is minted for
     * that version and the outcome appended — idempotent on a command id derived from the task.
     */
    private void recordPosted(UUID orgId, UUID reviewId, VendorReplyTask task, TaskSnapshot snapshot) {
        String body = ReviewReplyValidation.normalize(snapshot.draftBody());
        String fingerprint = ReviewReplyFingerprint.of(body);
        if (!fingerprint.equals(snapshot.approvedFingerprint())) {
            throw new IllegalStateException("posted text is not the approved text");
        }
        ReviewReplyDraft head = approvedHead(orgId, reviewId, body);
        int version = head.getVersion();
        String algorithm = head.getFingerprintAlgorithm() == null ? ReviewReplyValidation.FINGERPRINT_ALGORITHM
                : head.getFingerprintAlgorithm();
        String submissionRef = outcomes.mint(orgId, reviewId, version, fingerprint, ACTOR);
        outcomes.record(orgId, reviewId, VocItemRef.forReview(reviewId), submissionRef, version, fingerprint,
                algorithm, OperatorOutcome.OPERATOR_REPORTED_SUBMITTED, null, "vendor-posted:" + task.getVendorTaskId(),
                ACTOR);
    }

    private static String refusal(String code) {
        return switch (code) {
            case "task_in_progress" -> "이미 승인됐거나 담당자가 등록 중인 답글이 있습니다. 바꾸려면 먼저 요청을 취소하세요.";
            case "review_changed" -> "리뷰 내용이 바뀌었습니다. 리뷰를 다시 받아 온 뒤 초안을 확인하세요.";
            case "already_replied" -> "채널에 이미 답글이 등록된 리뷰입니다.";
            case "already_posted" -> "이 리뷰에는 이미 담당자가 답글을 등록했습니다.";
            case "invalid_body" -> "답글 내용을 보낼 수 없습니다. 길이와 내용을 확인하세요.";
            case "stale" -> CHANGED_ON_PHONE;
            // The server deleted it when its retention ended (contract §6): nothing there to hand over any more.
            case "review_purged" -> "서버 보관 기간이 끝나 정리된 리뷰라 담당자에게 맡길 수 없습니다. 판매자센터에서 직접 답글을 다세요.";
            default -> "공급자 서버가 이 요청을 받지 않았습니다.";
        };
    }
}
