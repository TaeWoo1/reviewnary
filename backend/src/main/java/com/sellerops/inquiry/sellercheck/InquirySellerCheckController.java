package com.sellerops.inquiry.sellercheck;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.inquiry.draft.InquiryDraftComposer;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.draft.dto.GeneratedDraftView;
import com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService;
import com.sellerops.replyhold.ReplyHoldService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller's 「확인한 결과 적기」 on an inquiry (2026-11-02 seller-check review). Recording and drafting are one press:
 * the record is kept, then the reply is drafted again through the same production path, with the record among its
 * evidence and every check it always gets. A correction is the same press — a new record, a new draft, a new check.
 * No marketplace READ or WRITE happens here beyond what drafting already does.
 *
 * <p><b>Saved and drafted are two answers</b> (2026-11-02 fourth review: 「확인한 내용은 저장했어요. 답변은 아직 준비하지
 * 못했어요.」). The record is committed before drafting starts, so a model that fails cannot take the seller's fact with
 * it; the response says whether a new draft was written ({@link #READY}), not written for a reason the draft view names
 * ({@link #NOT_WRITTEN}), or not written because drafting failed ({@link #FAILED}). A retry drafts again from the record
 * that stands without writing it again ({@code POST …/seller-check/draft}), and only from the record the screen showed.
 * Whatever happened, the waiting reply is checked against the record now, so an older draft written from another record
 * is held, not shown as the new result.
 */
@RestController
@RequestMapping("/api/inquiries")
public class InquirySellerCheckController {

    private static final Logger log = LoggerFactory.getLogger(InquirySellerCheckController.class);

    public static final String READY = "READY";
    public static final String NOT_WRITTEN = "NOT_WRITTEN";
    public static final String FAILED = "FAILED";
    /**
     * The reply was already approved, so nothing was drafted: the approval and what was sent stay as they are (2026-11-03
     * sixth review). A record corrected before registration is confirmed still holds the approved reply at the send check.
     */
    public static final String KEPT = "KEPT";

    /*
     * Why a draft was not written, when {@link #FAILED} — a fixed code, never an exception's name (2026-11-06 seventeenth
     * review: 「기록 저장 성공과 초안 생성 실패를 구분하고, 실패 원인을 정해진 코드로 남깁니다」). The record is kept in every
     * one of them.
     */
    /** The server's own preparation of an OPEN inquiry (its proposal, rule-based, no model) failed. */
    public static final String PREPARE_FAILED = "PREPARE_FAILED";
    /** The inquiry is not in a state a draft can be saved in, found before any model call. */
    public static final String NOT_DRAFTABLE = "NOT_DRAFTABLE";
    /** The drafting path refused (a conflict or a bad request it named), after the record was kept. */
    public static final String DRAFT_REFUSED = "DRAFT_REFUSED";
    /** Drafting itself broke — the model, its transport, or anything else unexpected. */
    public static final String DRAFT_ERROR = "DRAFT_ERROR";

    /**
     * {@code replaces}: the record the screen was showing, null when it showed none. {@code nextCheckOn}: for 「접수·진행
     * 중」, the day the seller will look again — two days later when not given; ignored on any other outcome.
     */
    public record RecordRequest(String outcome, String note, UUID replaces, java.time.LocalDate nextCheckOn) {
        public RecordRequest(String outcome, String note, UUID replaces) {
            this(outcome, note, replaces, null);
        }
    }

    /** {@code checkId}: the record the screen is drafting from. */
    public record RedraftRequest(UUID checkId) {
    }

    /**
     * @param check      the record that stands now
     * @param draft      the drafting result, null when drafting failed
     * @param draftState {@link #READY} · {@link #NOT_WRITTEN} · {@link #FAILED}
     * @param hold       the hold on the waiting reply after this press, or null — an older draft that does not match the
     *                   record says so here
     * @param draftFailure with {@link #FAILED} only: {@link #PREPARE_FAILED} · {@link #NOT_DRAFTABLE} ·
     *                   {@link #DRAFT_REFUSED} · {@link #DRAFT_ERROR}; null otherwise
     */
    public record RecordResponse(InquirySellerCheckService.SellerCheckView check, GeneratedDraftView draft,
                                 String draftState, ReplyHoldService.HoldView hold, String draftFailure) {
        public RecordResponse(InquirySellerCheckService.SellerCheckView check, GeneratedDraftView draft,
                              String draftState, ReplyHoldService.HoldView hold) {
            this(check, draft, draftState, hold, null);
        }
    }

    private final InquirySellerCheckService checks;
    private final InquiryDraftComposer composer;
    private final ObjectProvider<ReplyHoldService> holds;
    private final InquiryReplyDraftRepository drafts;
    private final ObjectProvider<InquiryDraftRecheck> rechecks;
    private final ObjectProvider<InquiryApprovalWithdrawalService> withdrawals;
    private ObjectProvider<InquiryKindCorrectionService> kinds;
    private ObjectProvider<com.sellerops.inquiry.proposal.InquiryProposalService> proposals;

    /**
     * The preparation an OPEN inquiry goes through before a draft can be saved on it — the same OPEN → PROPOSED step the
     * screen's 「AI 답변 초안 만들기」 asks for first. Optional; without it an OPEN inquiry is refused before drafting.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setProposals(ObjectProvider<com.sellerops.inquiry.proposal.InquiryProposalService> proposals) {
        this.proposals = proposals;
    }

    /** 「판단 고치기」 — optional; without it the screen gets no kind and the press is refused. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setKinds(ObjectProvider<InquiryKindCorrectionService> kinds) {
        this.kinds = kinds;
    }
    private final com.sellerops.inquiry.workitem.InquiryWorkItemRepository workItems;
    /** Work items being drafted from a record right now, in this process — a second press waits for the first. */
    private final Set<UUID> drafting = ConcurrentHashMap.newKeySet();

    public InquirySellerCheckController(InquirySellerCheckService checks, InquiryDraftComposer composer,
                                        ObjectProvider<ReplyHoldService> holds, InquiryReplyDraftRepository drafts,
                                        ObjectProvider<InquiryDraftRecheck> rechecks,
                                        ObjectProvider<InquiryApprovalWithdrawalService> withdrawals,
                                        com.sellerops.inquiry.workitem.InquiryWorkItemRepository workItems) {
        this.withdrawals = withdrawals;
        this.workItems = workItems;
        this.checks = checks;
        this.composer = composer;
        this.holds = holds;
        this.drafts = drafts;
        this.rechecks = rechecks;
    }

    /**
     * What the screen draws above the answer: the record that stands (null when none), what the seller is asked to check
     * ({@code ORDER_ACTION} · {@code ORDER_STATE} · null), and whether the newest answer version was written from that
     * record ({@code draftMatches}; null when there is no record). A reload restores the way back from a failed draft from
     * this alone (2026-11-03 fifth review): a record with no answer written from it offers 「이 기록으로 답변 만들기」.
     */
    public record SellerCheckState(InquirySellerCheckService.SellerCheckView check, String ask, Boolean draftMatches,
                                   InquiryApprovalWithdrawalService.AnsweredView answered,
                                   InquiryKindCorrectionService.KindView kind) {
        public SellerCheckState(InquirySellerCheckService.SellerCheckView check, String ask, Boolean draftMatches) {
            this(check, ask, draftMatches, null, null);
        }
    }

    @GetMapping("/{workItemId}/seller-check")
    public SellerCheckState current(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID workItemId) {
        InquirySellerCheckService.SellerCheckView check = checks.currentView(principal.orgId(), workItemId).orElse(null);
        Boolean matches = null;
        if (check != null) {
            InquiryDraftRecheck recheck = rechecks.getIfAvailable();
            matches = drafts.findTopByWorkItemIdOrderByVersionDesc(workItemId)
                    .map(head -> recheck != null && check.id().equals(recheck.writtenFrom(head))).orElse(false);
        }
        // What the customer was told is the record the approved version was written from — not the record now
        // (2026-11-05 seventh review): null until an answer is approved.
        InquiryApprovalWithdrawalService.AnsweredView answered = null;
        InquiryApprovalWithdrawalService withdrawals = this.withdrawals.getIfAvailable();
        if (withdrawals != null) {
            InquirySellerCheck standing = checks.current(principal.orgId(), workItemId).orElse(null);
            answered = workItems.findById(workItemId)
                    .flatMap(w -> withdrawals.answered(principal.orgId(), w, standing)).orElse(null);
        }
        InquiryKindCorrectionService kinds = this.kinds == null ? null : this.kinds.getIfAvailable();
        InquiryKindCorrectionService.KindView kind = kinds == null ? null
                : kinds.view(principal.orgId(), workItemId).orElse(null);
        return new SellerCheckState(check, checks.ask(principal.orgId(), workItemId), matches, answered, kind);
    }

    /** {@code kind}: HOW_TO · ORDER_STATE · ORDER_ACTION; {@code replaces}: the standing correction on screen, or null. */
    public record KindRequest(String kind, UUID replaces) {
    }

    /** The kind kept, what the seller is asked to check after the redraft, and the redraft's result. */
    public record KindResponse(InquiryKindCorrectionService.KindView kind, String ask, GeneratedDraftView draft,
                               String draftState, ReplyHoldService.HoldView hold) {
    }

    /**
     * 「판단 고치기」 (2026-11-05 seventh review) — the seller says what kind of inquiry this is, and the reply is drafted
     * again under it, with every check a draft always gets. The correction is kept even when drafting fails; the retry is
     * the ordinary 「AI 답변 초안 만들기」, which plans under it too.
     */
    @PostMapping("/{workItemId}/kind")
    public KindResponse correctKind(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID workItemId,
                                    @RequestBody(required = false) KindRequest request) {
        InquiryKindCorrectionService kinds = this.kinds == null ? null : this.kinds.getIfAvailable();
        if (kinds == null) {
            throw ApiException.conflict("지금은 판단을 고칠 수 없어요.");
        }
        claim(workItemId);
        try {
            InquiryKindCorrectionService.KindView kept = kinds.correct(principal.orgId(), workItemId,
                    request == null ? null : request.kind(), request == null ? null : request.replaces(),
                    "SELLER:" + principal.userId());
            RecordResponse drafted = draftFrom(principal, workItemId,
                    checks.currentView(principal.orgId(), workItemId).orElse(null));
            // Read again: whether the answer now waiting was written under the correction is the redraft's to say.
            kept = kinds.view(principal.orgId(), workItemId).orElse(kept);
            return new KindResponse(kept, checks.ask(principal.orgId(), workItemId), drafted.draft(),
                    drafted.draftState(), drafted.hold());
        } finally {
            drafting.remove(workItemId);
        }
    }

    /**
     * {@code checkId}: the record on screen; {@code approvedFingerprint}: the approved answer on screen;
     * {@code notRegistered}: the seller confirmed they did not put that answer on the seller centre by hand.
     */
    public record ReopenRequest(UUID checkId, String approvedFingerprint, Boolean notRegistered) {
    }

    /**
     * 「승인 거두고 이 기록으로 다시 만들기」 (2026-11-05 seventh review) — an approved answer the record has outrun, held at
     * registration and not on the channel, is taken back ({@link InquiryApprovalWithdrawalService#withdraw}: the approval
     * kept in full, the reply back with the seller), then drafted from the record that stands through the same path as
     * any record. The new answer waits for the seller's own approval; nothing is sent here.
     */
    @PostMapping("/{workItemId}/seller-check/reopen")
    public RecordResponse reopen(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID workItemId,
                                 @RequestBody(required = false) ReopenRequest request) {
        InquiryApprovalWithdrawalService withdrawals = this.withdrawals.getIfAvailable();
        if (withdrawals == null) {
            throw ApiException.conflict("지금은 승인을 거둘 수 없어요.");
        }
        if (request == null) {
            throw ApiException.badRequest(InquiryApprovalWithdrawalService.NOT_CONFIRMED);
        }
        claim(workItemId);
        try {
            withdrawals.withdraw(principal.orgId(), workItemId, request.checkId(), request.approvedFingerprint(),
                    Boolean.TRUE.equals(request.notRegistered()), "SELLER:" + principal.userId());
            InquirySellerCheckService.SellerCheckView standing = checks.currentView(principal.orgId(), workItemId)
                    .orElseThrow(() -> ApiException.conflict(InquirySellerCheckService.STALE));
            return draftFrom(principal, workItemId, standing);
        } finally {
            drafting.remove(workItemId);
        }
    }

    /**
     * {@code approvedFingerprint}: the approved answer on screen; {@code holdCheckId}: the hold on screen;
     * {@code notRegistered}: the seller confirmed they did not put that answer on the seller centre by hand.
     */
    public record HeldWithdrawRequest(String approvedFingerprint, String holdCheckId, Boolean notRegistered) {
    }

    /** The reply is the seller's again ({@code phase}), with the hold it now carries as a draft, or null. */
    public record HeldWithdrawResponse(String phase, ReplyHoldService.HoldView hold) {
    }

    /**
     * 「승인 거두고 고치기」 (2026-11-05 tenth review) — an approved answer held at registration for something a person must
     * fix (a policy conflict, a forbidden phrase, a changed figure) is taken back under the same send conditions as a
     * record change. Nothing is drafted and nothing is resolved here: the answer comes back to the seller as it was, with
     * its reason, and approving it again runs every check.
     */
    @PostMapping("/{workItemId}/approval/withdraw")
    public HeldWithdrawResponse withdrawHeld(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID workItemId,
                                             @RequestBody(required = false) HeldWithdrawRequest request) {
        InquiryApprovalWithdrawalService withdrawals = this.withdrawals.getIfAvailable();
        if (withdrawals == null) {
            throw ApiException.conflict("지금은 승인을 거둘 수 없어요.");
        }
        if (request == null) {
            throw ApiException.badRequest(InquiryApprovalWithdrawalService.NOT_CONFIRMED);
        }
        if (request.holdCheckId() == null || request.holdCheckId().isBlank()) {
            throw ApiException.conflict(InquiryApprovalWithdrawalService.CHANGED);
        }
        claim(workItemId);
        try {
            withdrawals.withdraw(principal.orgId(), workItemId, null, request.approvedFingerprint(),
                    request.holdCheckId(), Boolean.TRUE.equals(request.notRegistered()), "SELLER:" + principal.userId());
            ReplyHoldService hold = holds.getIfAvailable();
            String phase = workItems.findById(workItemId).map(w -> w.getPhase().name()).orElse(null);
            return new HeldWithdrawResponse(phase, hold == null ? null
                    : hold.holdFor(principal.orgId(), com.sellerops.replyhold.ReplyDraftHold.Lane.INQUIRY, workItemId));
        } finally {
            drafting.remove(workItemId);
        }
    }

    /** {@code checkId}: the record on screen; {@code closure}: TOLD_SEPARATELY · NOT_NEEDED. */
    public record ToldBehindRequest(UUID checkId, String closure) {
    }

    /** How it was closed, for which record, and when. */
    public record ToldBehindResponse(UUID checkId, String closure, java.time.Instant closedAt) {
    }

    /**
     * 「따로 안내함」 · 「안내 필요 없음」 — closes 「처리는 완료됐지만 고객에게는 … 안내했어요」 for the record on screen
     * (2026-11-05 tenth review). Sends nothing; a later record brings it back.
     */
    @PostMapping("/{workItemId}/told-behind/close")
    public ToldBehindResponse closeToldBehind(@AuthenticationPrincipal AuthPrincipal principal,
                                              @PathVariable UUID workItemId,
                                              @RequestBody(required = false) ToldBehindRequest request) {
        InquiryApprovalWithdrawalService withdrawals = this.withdrawals.getIfAvailable();
        if (withdrawals == null) {
            throw ApiException.conflict("지금은 마무리할 수 없어요.");
        }
        if (request == null) {
            throw ApiException.badRequest("「따로 안내함」 또는 「안내 필요 없음」을 골라 주세요.");
        }
        com.sellerops.inquiry.publish.InquiryToldBehindClosure closed = withdrawals.closeToldBehind(principal.orgId(),
                workItemId, request.checkId(), request.closure(), "SELLER:" + principal.userId());
        return new ToldBehindResponse(closed.getCheckId(), closed.getClosure(), closed.getClosedAt());
    }

    @PostMapping("/{workItemId}/seller-check")
    public RecordResponse record(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID workItemId,
                                 @RequestBody(required = false) RecordRequest request) {
        claim(workItemId);
        try {
            InquirySellerCheckService.SellerCheckView recorded = checks.record(principal.orgId(), workItemId,
                    request == null ? null : request.outcome(), request == null ? null : request.note(),
                    "SELLER:" + principal.userId(), request == null ? null : request.replaces(),
                    request == null ? null : request.nextCheckOn());
            return draftFrom(principal, workItemId, recorded);
        } finally {
            drafting.remove(workItemId);
        }
    }

    /** {@code checkId}: the record the screen is showing; {@code nextCheckOn}: the day the seller picked. */
    public record NextCheckRequest(UUID checkId, java.time.LocalDate nextCheckOn) {
    }

    /**
     * 「이 날로 정하기」 — the seller's next look at an action in progress. Changes the day only: no new record, no draft, no
     * hold, nothing sent. The day is the seller's own task and never reaches what a reply is written from.
     */
    @org.springframework.web.bind.annotation.PutMapping("/{workItemId}/seller-check/next-check")
    public InquirySellerCheckService.SellerCheckView nextCheck(@AuthenticationPrincipal AuthPrincipal principal,
                                                               @PathVariable UUID workItemId,
                                                               @RequestBody(required = false) NextCheckRequest request) {
        if (request == null) {
            throw ApiException.badRequest("다시 확인할 날짜를 골라 주세요.");
        }
        return checks.setNextCheck(principal.orgId(), workItemId, request.checkId(), request.nextCheckOn());
    }

    /** 「이 기록으로 답변 다시 만들기」 — drafts again from the record that stands, writing no record. */
    @PostMapping("/{workItemId}/seller-check/draft")
    public RecordResponse redraft(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID workItemId,
                                  @RequestBody(required = false) RedraftRequest request) {
        InquirySellerCheckService.SellerCheckView current = checks.currentView(principal.orgId(), workItemId)
                .orElseThrow(() -> ApiException.badRequest("먼저 확인한 결과를 적어 주세요."));
        if (request == null || !current.id().equals(request.checkId())) {
            throw ApiException.conflict(InquirySellerCheckService.STALE);
        }
        claim(workItemId);
        try {
            return draftFrom(principal, workItemId, current);
        } finally {
            drafting.remove(workItemId);
        }
    }

    private void claim(UUID workItemId) {
        if (!drafting.add(workItemId)) {
            throw ApiException.conflict("이 문의의 답변을 만들고 있어요. 잠시 뒤 다시 확인해 주세요.");
        }
    }

    private RecordResponse draftFrom(AuthPrincipal principal, UUID workItemId,
                                     InquirySellerCheckService.SellerCheckView recorded) {
        GeneratedDraftView draft = null;
        String state;
        String failure = null;
        if (!checks.answerOpen(principal.orgId(), workItemId)) {
            // Approved already: a corrected record changes what the seller is waiting on, never the approved answer.
            state = KEPT;
        } else if ((failure = prepare(principal, workItemId)) != null) {
            // Not ready for a draft, and found out before the model was asked for one (2026-11-06 seventeenth review). The
            // record is kept; nothing else moved.
            log.warn("seller-check draft not started workItem={} code={}", workItemId, failure);
            state = FAILED;
        } else {
            try {
                draft = composer.generate(principal.orgId(), workItemId, principal.userId());
                state = draft != null && draft.draft() != null ? READY : NOT_WRITTEN;
            } catch (ApiException e) {
                failure = DRAFT_REFUSED;
                log.warn("seller-check draft failed workItem={} code={} status={}", workItemId, failure,
                        e.getStatus().value());
                state = FAILED;
            } catch (RuntimeException e) {
                // The record is already kept; what failed is the draft, and the screen says exactly that.
                failure = DRAFT_ERROR;
                log.warn("seller-check draft failed workItem={} code={} cause={}", workItemId, failure,
                        e.getClass().getSimpleName());
                state = FAILED;
            }
        }
        ReplyHoldService hold = holds.getIfAvailable();
        ReplyHoldService.HoldView held = null;
        if (hold != null) {
            try {
                held = hold.recheckInquiry(principal.orgId(), workItemId);
            } catch (RuntimeException e) {
                // The approval and registration gates check again; a failed check here holds nothing and loses nothing.
                log.warn("seller-check recheck failed workItem={} cause={}", workItemId, e.getClass().getSimpleName());
            }
        }
        return new RecordResponse(recorded, draft, state, held, failure);
    }

    /**
     * Bring an inquiry to where a draft can be saved on it, on the server, or say why not (null: ready). The screen used to
     * be the only one that knew an OPEN inquiry must first be proposed; a record on an inquiry older than the automatic
     * proposal's reach then drafted in full — the model included — and failed only at the save (FINDINGS §B106).
     *
     * <p>Only OPEN moves, and only to PROPOSED, through the one transition every proposal takes (rule-based, no model).
     * Nothing approved, executed or completed is ever brought back to a draft here — {@link #draftFrom} stops those first.
     */
    private String prepare(AuthPrincipal principal, UUID workItemId) {
        com.sellerops.inquiry.workitem.InquiryWorkItemPhase phase = phaseOf(principal, workItemId);
        if (phase == com.sellerops.inquiry.workitem.InquiryWorkItemPhase.OPEN) {
            com.sellerops.inquiry.proposal.InquiryProposalService proposals =
                    this.proposals == null ? null : this.proposals.getIfAvailable();
            if (proposals == null) {
                return NOT_DRAFTABLE;
            }
            try {
                proposals.propose(principal.orgId(), workItemId, principal.userId());
            } catch (RuntimeException e) {
                return PREPARE_FAILED;
            }
            phase = phaseOf(principal, workItemId);
        }
        return phase == com.sellerops.inquiry.workitem.InquiryWorkItemPhase.PROPOSED ? null : NOT_DRAFTABLE;
    }

    private com.sellerops.inquiry.workitem.InquiryWorkItemPhase phaseOf(AuthPrincipal principal, UUID workItemId) {
        return workItems.findById(workItemId).filter(w -> principal.orgId().equals(w.getOrgId()))
                .map(com.sellerops.inquiry.workitem.InquiryWorkItem::getPhase).orElse(null);
    }
}
