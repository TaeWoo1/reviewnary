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
import com.sellerops.common.ApiException;
import com.sellerops.common.ReviewBodyFingerprint;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.vendorlink.VendorServerClient.DraftMeta;
import com.sellerops.vendorlink.VendorServerClient.TaskChange;
import com.sellerops.vendorlink.VendorServerClient.TaskChanges;
import com.sellerops.vendorlink.VendorServerClient.TaskSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
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
 *   <li><b>Only a draft the seller sends goes to the phone.</b> {@link #requestApproval} is the seller pressing
 *       「휴대폰으로 승인 요청」 on this PC for a review's current draft; nothing is sent on its own.</li>
 *   <li><b>Only the seller's own 'no reply' is told to the server.</b> {@link #reportNoReply} sends a review whose
 *       triage a person set to {@code NO_ACTION}; an automated judgement is never sent.</li>
 * </ol>
 *
 * <p>The server is the state of record. {@link #sync} copies its task changes here ({@link VendorReplyTask}); while a
 * task is open this PC's own approve and guided-run road stays shut ({@link DelegatedReplyLane}), so one review never
 * gets two replies. A delegate's {@code POSTED} is recorded once in the ordinary reply lane as the existing pair
 * {@code OPERATOR_REPORTED_SUBMITTED} + {@code UNVERIFIED} (v1.14 ⑨) — never as a completion.
 *
 * <p><b>Draft fingerprints.</b> This PC has no versioned knowledge, policy or voice yet, so every draft says
 * {@value #UNVERSIONED} for all three and the PC sends no policy check: the server's recheck rule has nothing to
 * compare until such versions exist.
 */
@Service
public class VendorReplyTaskService implements DelegatedReplyLane {

    private static final Logger log = LoggerFactory.getLogger(VendorReplyTaskService.class);

    static final String UNVERSIONED = "unversioned";
    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES = 25;
    /** Who wrote what this lane records: the vendor's delegate, reported through the server. */
    static final String ACTOR = "VENDOR_DELEGATE";

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
    private final Clock clock;

    @Autowired
    public VendorReplyTaskService(VendorLinkService link, VendorStoreBindingRepository bindings,
                                  VendorReviewRefRepository refs, VendorReplyTaskRepository tasks,
                                  ReviewRepository reviews, ReviewReplyDraftService drafts,
                                  ReviewReplyApprovalService approvals, ReviewReplyOutcomeService outcomes) {
        this(link, bindings, refs, tasks, reviews, drafts, approvals, outcomes, Clock.systemUTC());
    }

    VendorReplyTaskService(VendorLinkService link, VendorStoreBindingRepository bindings,
                           VendorReviewRefRepository refs, VendorReplyTaskRepository tasks, ReviewRepository reviews,
                           ReviewReplyDraftService drafts, ReviewReplyApprovalService approvals,
                           ReviewReplyOutcomeService outcomes, Clock clock) {
        this.link = link;
        this.bindings = bindings;
        this.refs = refs;
        this.tasks = tasks;
        this.reviews = reviews;
        this.drafts = drafts;
        this.approvals = approvals;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    @Override
    public boolean openOn(UUID orgId, UUID reviewId) {
        return tasks.existsByOrgIdAndReviewIdAndStateNotIn(orgId, reviewId, VendorReplyTask.ENDED);
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
        if (approvals.current(orgId, reviewId).filter(a -> a.getState() == ReviewReplyApprovalState.APPROVED)
                .isPresent()) {
            // The seller already approved here, for the copy or guided road. Two roads, two replies.
            throw ApiException.conflict("이 PC에서 이미 승인한 답글입니다. 승인을 철회한 뒤 휴대폰 승인을 요청하세요.");
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
            snapshot = link.client().submitDraft(token, binding.getVendorStoreId(), ref.getVendorReviewId(),
                    draft.getBody(), new DraftMeta(ref.getBodyFingerprint(), draft.getCreatedAt(), UNVERSIONED,
                            UNVERSIONED, UNVERSIONED));
        } catch (VendorServerClient.TaskRefused e) {
            throw ApiException.conflict(refusal(e.code()));
        } catch (VendorServerClient.Refused e) {
            throw ApiException.conflict("공급자 서버가 이 PC의 요청을 받지 않았습니다. 연결 상태를 확인하세요.");
        } catch (VendorServerClient.Unreachable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "공급자 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
        return TaskView.of(apply(orgId, reviewId, binding.getVendorStoreId(), snapshot));
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
        task.setState(snapshot.state());
        task.setStateVersion(snapshot.stateVersion());
        task.setDraftVersion(snapshot.draftVersion());
        task.setDraftFingerprint(snapshot.draftFingerprint());
        task.setApprovedFingerprint(snapshot.approvedFingerprint());
        task.setEditedOnMobile(snapshot.editedOnMobile());
        if ("POSTED".equals(snapshot.state()) && task.getOutcomeRecordedAt() == null) {
            recordPosted(orgId, reviewId, task, snapshot);
            task.setOutcomeRecordedAt(clock.instant());
        }
        return tasks.save(task);
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
        ReviewReplyDraft head = drafts.latest(reviewId).orElse(null);
        int version;
        String algorithm;
        if (head != null && fingerprint.equals(head.getContentFingerprint())) {
            version = head.getVersion();
            algorithm = head.getFingerprintAlgorithm();
        } else {
            // The seller's approved words — a person's sentence, whoever drafted the version before it.
            version = drafts.saveAs(orgId, reviewId, ACTOR, body, head == null ? 0 : head.getVersion(),
                    ReviewReplyDraftService.Provenance.seller()).version();
            algorithm = ReviewReplyValidation.FINGERPRINT_ALGORITHM;
        }
        String submissionRef = outcomes.mint(orgId, reviewId, version, fingerprint, ACTOR);
        outcomes.record(orgId, reviewId, VocItemRef.forReview(reviewId), submissionRef, version, fingerprint,
                algorithm, OperatorOutcome.OPERATOR_REPORTED_SUBMITTED, null, "vendor-posted:" + task.getVendorTaskId(),
                ACTOR);
    }

    private static String refusal(String code) {
        return switch (code) {
            case "task_in_progress" -> "이미 승인됐거나 담당자가 등록 중인 답글이 있습니다. 바꾸려면 휴대폰에서 먼저 취소하세요.";
            case "review_changed" -> "리뷰 내용이 바뀌었습니다. 리뷰를 다시 받아 온 뒤 초안을 확인하세요.";
            case "already_replied" -> "채널에 이미 답글이 등록된 리뷰입니다.";
            case "already_posted" -> "이 리뷰에는 이미 담당자가 답글을 등록했습니다.";
            case "invalid_body" -> "답글 내용을 보낼 수 없습니다. 길이와 내용을 확인하세요.";
            default -> "공급자 서버가 이 요청을 받지 않았습니다.";
        };
    }
}
