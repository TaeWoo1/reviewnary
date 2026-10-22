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
import java.util.List;
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
 *   <li><b>Only a draft the seller sends leaves this PC.</b> {@link #requestApproval} is the seller pressing
 *       「휴대폰으로 승인 요청」 for a review's current draft, {@link #handOver} is 「승인하고 담당자에게 맡기기」 — the
 *       same draft approved here and now (D9). Nothing is sent on its own.</li>
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
 * Every hold carries what changed, the sentences it touches and the words that lost their ground. Without {@link
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
    /** What the server still lets the seller cancel (§4.2). */
    static final Set<String> CANCELLABLE = Set.of("AWAITING_APPROVAL", "RECHECK_NEEDED", "APPROVED", "CLAIMED", "HELD",
            "HOLD_REQUESTED");
    /** Approved texts this PC checks again when what replies are written from changed. */
    static final Set<String> STANDING_APPROVALS = Set.of("APPROVED", "CLAIMED");
    private static final com.fasterxml.jackson.databind.ObjectMapper NOTE_JSON =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

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
        return tasks.existsByOrgIdAndReviewIdAndStateNotIn(orgId, reviewId, UNPOSTED_ENDS);
    }

    private static final Set<String> UNPOSTED_ENDS = Set.of("FAILED", "CANCELLED", "INVALIDATED");

    @Override
    public ReviewReplyDelegationView describe(UUID orgId, UUID reviewId, boolean draftReady) {
        if (link.installToken().isEmpty() || refs.findByReviewId(reviewId).isEmpty()) {
            return null;
        }
        Optional<VendorReplyTask> task = tasks.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(orgId, reviewId);
        String state = task.map(VendorReplyTask::getState).orElse(null);
        return new ReviewReplyDelegationView(state, task.map(VendorReplyTask::isEditedOnMobile).orElse(false),
                draftReady && (state == null || SENDABLE.contains(state)),
                state != null && CANCELLABLE.contains(state),
                task.map(VendorReplyTask::getBasisNote).map(VendorReplyTaskService::note).orElse(null));
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
     * The seller sends the review's current draft for approval on the phone. Pressing it again after editing here
     * sends the new text as the task's next version while it still awaits approval.
     */
    public synchronized TaskView requestApproval(UUID orgId, UUID reviewId) {
        return TaskView.of(send(orgId, reviewId).task());
    }

    /**
     * 「승인하고 담당자에게 맡기기」 (D9): the seller, at this PC, sends the review's current draft and approves exactly
     * that version and text in one press — the delegate can claim it without a trip to the phone. If the approval is
     * refused after the draft went, the task stays awaiting approval on the phone and the seller is told so.
     */
    public synchronized TaskView handOver(UUID orgId, UUID reviewId) {
        Sent sent = send(orgId, reviewId);
        TaskSnapshot submitted = sent.snapshot();
        if (!"AWAITING_APPROVAL".equals(submitted.state())) {
            throw ApiException.conflict("휴대폰으로 보낸 답글이 다시 확인을 기다리고 있습니다. 초안을 확인한 뒤 다시 보내세요.");
        }
        TaskSnapshot approved;
        try {
            approved = link.client().approve(sent.token(), submitted.id(), submitted.draftVersion(),
                    submitted.draftFingerprint(), "pc-approve:" + submitted.id() + ":" + submitted.draftVersion());
        } catch (VendorServerClient.TaskRefused e) {
            throw ApiException.conflict("답글은 보냈지만 승인하지 못했습니다. 휴대폰에서 승인하거나 다시 시도하세요.");
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

    /** The checks both presses share, then the draft submitted; the server's answer is copied here. */
    private Sent send(UUID orgId, UUID reviewId) {
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
        String token = link.installToken()
                .orElseThrow(() -> ApiException.conflict("공급자 서버에 연결된 PC가 아닙니다."));
        TaskSnapshot snapshot;
        try {
            // The seller is looking at this draft and pressing send: it goes under what drafts are written from now,
            // reported first if that changed, so it does not land sent back.
            Fingerprints now = reportBasis(binding, token);
            snapshot = link.client().submitDraft(token, binding.getVendorStoreId(), ref.getVendorReviewId(),
                    draft.getBody(), new DraftMeta(ref.getBodyFingerprint(), draft.getCreatedAt(), now.knowledge(),
                            now.policy(), now.voice()));
        } catch (VendorServerClient.TaskRefused e) {
            throw ApiException.conflict(refusal(e.code()));
        } catch (VendorServerClient.Refused e) {
            throw ApiException.conflict("공급자 서버가 이 PC의 요청을 받지 않았습니다. 연결 상태를 확인하세요.");
        } catch (VendorServerClient.Unreachable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "공급자 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
        return new Sent(binding, token, snapshot, apply(orgId, reviewId, binding.getVendorStoreId(), snapshot));
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
                                        now.knowledge(), now.policy(), now.voice()));
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
                    log.warn("vendor reply task: recheck not recorded store={} code={}", storeId, e.code());
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
                after = checkOne(orgId, storeId, token, task, current);
                held += after != null && !STANDING_APPROVALS.contains(after.state()) ? 1 : 0;
            }
            VendorReplyTask checked = after == null ? task : apply(orgId, task.getReviewId(), storeId, after);
            checked.setRecheckedVersion(current.draftVersion());
            checked.setRecheckedBasis(now.combined());
            tasks.save(checked);
        }
        return held;
    }

    /** One approved text checked; the server's answer, or null when nothing was sent. */
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
        if (result.passed()) {
            return null;
        }
        boolean hold = result.codes().contains(ReviewDraftRecheck.UNSUPPORTED_PROMISE);
        try {
            return link.client().checkApproved(token, current.id(), current.approvedVersion(), hold, note(result),
                    "pc-check:" + current.id() + ":" + current.approvedVersion() + ":" + current.stateVersion());
        } catch (VendorServerClient.TaskRefused e) {
            log.warn("vendor reply task: approved check not recorded store={} code={}", storeId, e.code());
            return null;
        }
    }

    /** What the seller is shown about a finding: the server's note. */
    private static VendorServerClient.BasisNote note(ReviewDraftRecheck.Result result) {
        return new VendorServerClient.BasisNote(result.codes(), result.changed().stream()
                .map(c -> new VendorServerClient.ChangedSource(c.kind(), c.title(), c.changedAt(), c.removed()))
                .toList(), result.promises(), result.sentences());
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
            // The server deleted it when its retention ended (contract §6): nothing there to hand over any more.
            case "review_purged" -> "서버 보관 기간이 끝나 정리된 리뷰라 담당자에게 맡길 수 없습니다. 판매자센터에서 직접 답글을 다세요.";
            default -> "공급자 서버가 이 요청을 받지 않았습니다.";
        };
    }
}
