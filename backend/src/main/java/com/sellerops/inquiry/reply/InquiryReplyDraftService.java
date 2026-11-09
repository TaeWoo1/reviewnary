package com.sellerops.inquiry.reply;

import com.sellerops.common.ApiException;
import com.sellerops.inquiry.draft.DraftAuthorKind;
import com.sellerops.inquiry.draft.DraftKnowledgeState;
import com.sellerops.inquiry.reply.dto.ReplyDraftView;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Seller reply-draft persistence: append-only, versioned ESM answer drafts on a
 * PROPOSED work item.
 *
 * <p>Ordering is deliberate — the <b>org guard runs before any content is read or
 * validated</b>. Only a PROPOSED item accepts a draft. The seller edits only
 * title/comments; {@code answerStatus} is backend-fixed. Both fields are normalized
 * (CRLF/CR &rarr; LF, trimmed) before validation, persistence, and fingerprinting.
 *
 * <p><b>Versioning &amp; concurrency:</b> the caller passes {@code baseVersion} (the
 * version it edited from; {@code 0} for the first save). A save from the head
 * inserts {@code version+1}; a base that is not the head is a stale write (409). An
 * exact retry (same content already at the head) is an idempotent no-op that returns
 * the head without inserting a duplicate version — for a seller's save only; a generated
 * version always appends (see {@code saveAs}). Concurrent saves are serialized by
 * the {@code (work_item_id, version)} UNIQUE constraint — the loser re-resolves to
 * the head (idempotent if identical, else 409). No content ever reaches an audit
 * row or a log/exception message.
 */
@Service
public class InquiryReplyDraftService {

    private final InquiryWorkItemRepository workItems;
    private final InquiryReplyDraftRepository drafts;

    public InquiryReplyDraftService(InquiryWorkItemRepository workItems,
                                    InquiryReplyDraftRepository drafts) {
        this.workItems = workItems;
        this.drafts = drafts;
    }

    /**
     * The head version number, or {@code 0} when nothing is saved yet — exactly the {@code
     * baseVersion} a first save expects. Exposed so a generator can append without racing itself into
     * a 409 by guessing.
     */
    public int currentVersion(UUID workItemId) {
        return drafts.findTopByWorkItemIdOrderByVersionDesc(workItemId)
                .map(InquiryReplyDraft::getVersion).orElse(0);
    }

    /** The current draft for a work item (assumes the caller already org-verified it). */
    public ReplyDraftView latestView(UUID workItemId) {
        return drafts.findTopByWorkItemIdOrderByVersionDesc(workItemId)
                .map(ReplyDraftView::of).orElse(null);
    }

    /**
     * The provenance stamped onto a saved version.
     *
     * <p>A seller edit is always {@link DraftAuthorKind#SELLER} with no model and no knowledge state,
     * whatever the version before it was: the moment a human changes a word, the draft is theirs and
     * claiming a model wrote it would be false. Only {@link InquiryDraftComposer} passes anything
     * else.
     */
    public record Provenance(DraftAuthorKind authorKind, String modelVersion,
                             DraftKnowledgeState knowledgeState, UUID productId,
                             com.sellerops.inquiry.draft.AnswerBasisState answerBasis, UUID sellerCheckId,
                             UUID kindCorrectionId) {

        public Provenance(DraftAuthorKind authorKind, String modelVersion, DraftKnowledgeState knowledgeState,
                          UUID productId, com.sellerops.inquiry.draft.AnswerBasisState answerBasis) {
            this(authorKind, modelVersion, knowledgeState, productId, answerBasis, null, null);
        }

        public Provenance(DraftAuthorKind authorKind, String modelVersion, DraftKnowledgeState knowledgeState,
                          UUID productId, com.sellerops.inquiry.draft.AnswerBasisState answerBasis, UUID sellerCheckId) {
            this(authorKind, modelVersion, knowledgeState, productId, answerBasis, sellerCheckId, null);
        }

        /** What a hand-typed save records. */
        public static Provenance seller() {
            return seller(null, null);
        }

        /** A hand-typed save, naming the seller record the screen was showing (null when none). */
        public static Provenance seller(UUID sellerCheckId) {
            return seller(sellerCheckId, null);
        }

        /** A hand-typed save, naming the record and the kind correction the screen was showing (null when none). */
        public static Provenance seller(UUID sellerCheckId, UUID kindCorrectionId) {
            return new Provenance(DraftAuthorKind.SELLER, null, null, null, null, sellerCheckId, kindCorrectionId);
        }

        /** The same provenance, naming the seller record its writer had in front of them. */
        public Provenance withSellerCheck(UUID id) {
            return new Provenance(authorKind, modelVersion, knowledgeState, productId, answerBasis, id,
                    kindCorrectionId);
        }

        /** The same provenance, naming the kind correction its writer had in front of them. */
        public Provenance withKindCorrection(UUID id) {
            return new Provenance(authorKind, modelVersion, knowledgeState, productId, answerBasis, sellerCheckId, id);
        }
    }

    public ReplyDraftView save(UUID orgId, UUID workItemId, UUID sellerUserId,
                               String title, String comments, Integer baseVersion) {
        return save(orgId, workItemId, sellerUserId, title, comments, baseVersion, Provenance.seller());
    }

    /**
     * As {@link #save(UUID, UUID, UUID, String, String, Integer, Provenance)}, but the author is named
     * rather than derived from a user id.
     *
     * <p>Exists because a draft can now be prepared before any human opens the inquiry — the Proactive
     * Operations Agent writes one with {@code SYSTEM:PROACTIVE_AGENT} as the author. Everything else
     * about the version is identical: same validation, same normalization, same fingerprint, same
     * append-only versioning, same PROPOSED-only gate. The author string is the ONLY difference, and
     * it is the difference that matters — a system-prepared draft that recorded a seller as its author
     * would put a person's name on a sentence they have never read.
     *
     * <p><b>A generated version is never folded into an earlier one</b> (2026-11-02 checkpoint): it is
     * the record of one run — its provenance here, and the evidence and figure grounds the caller binds
     * to its version next. A second run that wrote the same words from different evidence and reused
     * the head would have recorded its evidence on a version that already had some (PostgreSQL refused
     * the second ordinal 0: HTTP 500), or, without the constraint, two runs' evidence on one version. So
     * it always appends; the earlier version, and any approval bound to it, stay as they were. Only a
     * seller's own retry is idempotent.
     */
    public ReplyDraftView saveAs(UUID orgId, UUID workItemId, String actor,
                                 String title, String comments, Integer baseVersion,
                                 Provenance provenance) {
        return persist(orgId, workItemId, actor, title, comments, baseVersion, provenance, true);
    }

    public ReplyDraftView save(UUID orgId, UUID workItemId, UUID sellerUserId,
                               String title, String comments, Integer baseVersion,
                               Provenance provenance) {
        return persist(orgId, workItemId, "SELLER:" + sellerUserId, title, comments, baseVersion, provenance, false);
    }

    private ReplyDraftView persist(UUID orgId, UUID workItemId, String actor,
                                   String title, String comments, Integer baseVersion,
                                   Provenance provenance, boolean generated) {
        // Org guard BEFORE reading/validating any content.
        InquiryWorkItem workItem = workItems.findById(workItemId)
                .filter(w -> w.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("문의 작업을 찾을 수 없습니다."));
        if (workItem.getPhase() != InquiryWorkItemPhase.PROPOSED) {
            throw ApiException.conflict("PROPOSED 상태의 문의만 답변 초안을 저장할 수 있습니다.");
        }
        if (baseVersion == null || baseVersion < 0) {
            throw ApiException.badRequest("baseVersion이 필요합니다 (첫 저장은 0).");
        }

        String normalizedTitle = EsmAnswerValidation.normalize(title);
        String normalizedComments = EsmAnswerValidation.normalize(comments);
        if (EsmAnswerValidation.isBlank(normalizedTitle)) {
            throw ApiException.badRequest("제목을 입력하세요.");
        }
        if (EsmAnswerValidation.isBlank(normalizedComments)) {
            throw ApiException.badRequest("답변 내용을 입력하세요.");
        }
        if (EsmAnswerValidation.utf8Bytes(normalizedTitle) > EsmAnswerValidation.TITLE_MAX_BYTES) {
            throw ApiException.badRequest(
                    "제목이 너무 깁니다 (최대 " + EsmAnswerValidation.TITLE_MAX_BYTES + " 바이트).");
        }
        // Kept up to the internal cap only: an answer too long for its channel is still the seller's to shorten, and the
        // confirm refuses to send it (InquiryReplyLength, 2026-11-02 — two checked drafts were lost here at 1000 bytes).
        if (EsmAnswerValidation.utf8Bytes(normalizedComments) > InquiryReplyLength.STORAGE_MAX_BYTES) {
            throw ApiException.badRequest(
                    "답변 내용이 너무 깁니다 (최대 " + InquiryReplyLength.STORAGE_MAX_BYTES + " 바이트).");
        }

        String fingerprint = ReplyDraftFingerprint.of(normalizedTitle, normalizedComments);

        Optional<InquiryReplyDraft> latest = drafts.findTopByWorkItemIdOrderByVersionDesc(workItemId);
        int currentVersion = latest.map(InquiryReplyDraft::getVersion).orElse(0);

        // Idempotent replay/no-op: the content is already the head, and the base is
        // either the head (re-save of identical content) or the head's predecessor
        // (the save being retried already produced this head).
        // Not when the seller now saves it with another record in front of them: the same words, written from the record
        // that stands, are a new version that says so (2026-11-03).
        if (!generated && latest.isPresent() && latest.get().getContentFingerprint().equals(fingerprint)
                && java.util.Objects.equals(latest.get().getSellerCheckId(), provenance.sellerCheckId())
                && java.util.Objects.equals(latest.get().getKindCorrectionId(), provenance.kindCorrectionId())
                && (baseVersion == currentVersion || baseVersion == currentVersion - 1)) {
            return ReplyDraftView.of(latest.get());
        }
        // Stale write: the base is not the current head (and the content differs).
        if (baseVersion != currentVersion) {
            throw ApiException.conflict(
                    "이미 최신 초안이 있습니다. 새로고침 후 다시 시도하세요.");
        }

        InquiryReplyDraft draft = new InquiryReplyDraft();
        draft.setOrgId(orgId);
        draft.setWorkItemId(workItemId);
        draft.setVersion(currentVersion + 1);
        draft.setAnswerStatus(EsmAnswerValidation.ANSWER_STATUS);
        draft.setTitle(normalizedTitle);
        draft.setComments(normalizedComments);
        draft.setContentFingerprint(fingerprint);
        draft.setFingerprintAlgorithm(EsmAnswerValidation.FINGERPRINT_ALGORITHM);
        draft.setCreatedBy(actor);
        draft.setAuthorKind(provenance.authorKind().name());
        draft.setModelVersion(provenance.modelVersion());
        draft.setKnowledgeState(provenance.knowledgeState() == null ? null : provenance.knowledgeState().name());
        draft.setAnswerBasis(provenance.answerBasis() == null ? null : provenance.answerBasis().name());
        draft.setProductId(provenance.productId());
        draft.setSellerCheckId(provenance.sellerCheckId());
        draft.setKindCorrectionId(provenance.kindCorrectionId());
        try {
            return ReplyDraftView.of(drafts.save(draft));
        } catch (DataIntegrityViolationException race) {
            // A concurrent writer took this version. Re-resolve to the head: an
            // identical concurrent save is an idempotent success, otherwise it is a
            // stale write the caller must re-base on.
            InquiryReplyDraft head = drafts.findTopByWorkItemIdOrderByVersionDesc(workItemId)
                    .orElseThrow(() -> race);
            // A generated version is never another run's (see saveAs): the race is a conflict, not a reuse.
            if (!generated && head.getContentFingerprint().equals(fingerprint)
                    && java.util.Objects.equals(head.getSellerCheckId(), provenance.sellerCheckId())
                    && java.util.Objects.equals(head.getKindCorrectionId(), provenance.kindCorrectionId())) {
                return ReplyDraftView.of(head);
            }
            throw ApiException.conflict("동시에 다른 초안이 저장되었습니다. 새로고침 후 다시 시도하세요.");
        }
    }
}
