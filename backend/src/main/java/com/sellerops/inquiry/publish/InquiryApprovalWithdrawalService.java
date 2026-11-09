package com.sellerops.inquiry.publish;

import com.sellerops.common.ApiException;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import com.sellerops.inquiry.reply.InquiryReplyDraft;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheck;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemAudit;
import com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemEvent;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.memory.AnswerMemoryReuseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>What the customer was told, and the way back from an approved answer the record has outrun</b> (2026-11-05
 * seventh review).
 *
 * <ol>
 *   <li><b>The record now and the answer sent are two facts.</b> A seller who answered 「진행 중」 and later recorded
 *       「처리 완료」 has finished the action and told the customer it was in progress. {@link #answered} says which record
 *       the approved version was written from, so the screen never reads the current record as what was said.</li>
 *   <li><b>An approved answer the record no longer backs is held at registration</b>
 *       ({@link InquiryDraftRecheck#SELLER_CHECK_CHANGED}, fix-first): nothing goes on until the text changes, and an
 *       approved text cannot change. {@link #withdraw} is that way on: once the service has no sign the answer is on the
 *       channel and the seller says they did not post it by hand, the approval is moved, field for field, to
 *       {@code inquiry_approval_withdrawn}, the standing approval, intent and execution are cleared, and the reply goes
 *       back to {@code PROPOSED} — to be drafted from the record that stands and approved again.</li>
 * </ol>
 *
 * Narrow on purpose: only an answer the current record does not back is taken back here, only while nothing was
 * dispatched — an execution that was sent, may have been sent, or was read back from the channel is never cleared — and
 * only once the channel was read after the approval and showed no answer. This is not a general 「승인 취소」.
 */
@Service
public class InquiryApprovalWithdrawalService {

    /** The approved answer is written from the record that stands: nothing to take back. */
    public static final String MATCHES = "MATCHES";
    /** Not on the channel as far as the service knows, and the record moved on: may be taken back. */
    public static final String OFFERED = "OFFERED";
    /** On the channel: what was sent stays sent. */
    public static final String REGISTERED = "REGISTERED";
    /** A send that went out or may have: its result is read first. */
    public static final String UNSETTLED = "UNSETTLED";
    /**
     * Nothing was sent, but the channel has not been read since the approval — or never — so whether an answer is on it
     * (pasted by hand) is not known (2026-11-05 eighth review: 「등록된 답변이 없음을 확인함」과 「아직 모름」은 다르다).
     */
    public static final String UNCONFIRMED = "UNCONFIRMED";

    static final Set<InquiryWorkItemPhase> APPROVED_NOT_SENT =
            EnumSet.of(InquiryWorkItemPhase.APPROVED, InquiryWorkItemPhase.ACTION_PENDING, InquiryWorkItemPhase.FAILED);

    public static final String NOT_CONFIRMED = "판매자센터에 이 답변을 직접 등록하지 않았는지 먼저 확인해 주세요.";
    public static final String CHANGED = "다른 곳에서 먼저 바뀌었어요. 새로 불러온 내용을 확인한 뒤 다시 눌러 주세요.";
    public static final String STANDS = "승인한 답변이 지금 기록으로 쓴 답변이에요. 거둘 필요가 없어요.";
    public static final String ON_CHANNEL = "채널에 답변이 등록돼 있어요. 이미 보낸 답변은 거둘 수 없어요.";
    public static final String NOT_SETTLED = "등록됐는지 아직 확인하지 못했어요. 등록 상태를 먼저 확인해 주세요.";
    public static final String NOT_HELD = "고칠 보류가 없는 답변이에요. 거둘 필요가 없어요.";
    public static final String NOT_TOLD_BEHIND = "고객에게 보낸 답변과 지금 처리 결과가 같아요. 마무리할 일이 없어요.";
    public static final String NOT_READ = "승인한 뒤로 채널에서 이 문의를 아직 읽지 못해 등록 여부를 몰라요. 다음 수집에서 등록된 답변이 없는 것이 "
            + "확인되면 되돌릴 수 있어요.";

    /** Why an approved answer may be taken back: the seller's record moved on from it. */
    public static final String BECAUSE_RECORD = "RECORD";
    /** Why an approved answer may be taken back: it is held at registration for something a person must fix. */
    public static final String BECAUSE_HOLD = "HOLD";

    /**
     * The approved answer against the record. {@code outcome}/{@code recordedAt}: the record the approved version was
     * written from (null when it was written from none); {@code current}: that record still stands; {@code reopen}: one
     * of {@link #MATCHES} · {@link #OFFERED} · {@link #REGISTERED} · {@link #UNSETTLED} · {@link #UNCONFIRMED};
     * {@code channelReadAt}: when the service last read this inquiry from the channel; {@code because}:
     * {@link #BECAUSE_RECORD} · {@link #BECAUSE_HOLD} · null (nothing to take back); {@code holdCheckId}/{@code holdCodes}:
     * the fix-first hold on the approved version, sent back with a take-back; {@code toldBehind}: registered, and the
     * record now says 「처리 완료」 while the answer was written from another; {@code closure}/{@code closedAt}: how the
     * seller closed that for the record that stands, or null.
     */
    public record AnsweredView(int version, String fingerprint, String outcome, String outcomeKo, Instant recordedAt,
                               boolean current, String reopen, Instant channelReadAt, String because,
                               String holdCheckId, java.util.List<String> holdCodes, boolean toldBehind, String closure,
                               Instant closedAt) {
        public AnsweredView(int version, String fingerprint, String outcome, String outcomeKo, Instant recordedAt,
                            boolean current, String reopen, Instant channelReadAt) {
            this(version, fingerprint, outcome, outcomeKo, recordedAt, current, reopen, channelReadAt, null, null,
                    java.util.List.of(), false, null, null);
        }
    }

    /** A registered answer told the customer less than the record now says; the Home shows it until closed. */
    public record ToldBehind(UUID workItemId, UUID inquiryId, UUID checkId, String toldOutcome, String toldOutcomeKo,
                             String note, Instant recordedAt) {
    }

    private final EntityManager em;
    private final InquiryApprovalRepository approvals;
    private final InquiryActionIntentRepository intents;
    private final InquiryExecutionRepository executions;
    private final InquiryVerificationRepository verifications;
    private final InquiryApprovalWithdrawalRepository withdrawn;
    private final InquiryReplyDraftRepository drafts;
    private final InquirySellerCheckRepository checks;
    private final InquiryRepository inquiries;
    private final InquiryWorkItemAuditRepository audits;
    private final InquiryDraftRecheck recheck;
    private final AnswerMemoryRepository memories;
    private org.springframework.beans.factory.ObjectProvider<com.sellerops.replyhold.ReplyHoldService> holds;
    private InquiryToldBehindClosureRepository closures;

    public InquiryApprovalWithdrawalService(EntityManager em, InquiryApprovalRepository approvals,
                                            InquiryActionIntentRepository intents,
                                            InquiryExecutionRepository executions,
                                            InquiryVerificationRepository verifications,
                                            InquiryApprovalWithdrawalRepository withdrawn,
                                            InquiryReplyDraftRepository drafts, InquirySellerCheckRepository checks,
                                            InquiryRepository inquiries, InquiryWorkItemAuditRepository audits,
                                            InquiryDraftRecheck recheck, AnswerMemoryRepository memories) {
        this.em = em;
        this.approvals = approvals;
        this.intents = intents;
        this.executions = executions;
        this.verifications = verifications;
        this.withdrawn = withdrawn;
        this.drafts = drafts;
        this.checks = checks;
        this.inquiries = inquiries;
        this.audits = audits;
        this.recheck = recheck;
        this.memories = memories;
    }

    /** The hold reader, for a take-back of an answer held for something a person must fix (2026-11-05 tenth review). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHolds(org.springframework.beans.factory.ObjectProvider<com.sellerops.replyhold.ReplyHoldService> holds) {
        this.holds = holds;
    }

    /** Where the seller's closing of 「완료됐지만 진행 중으로 안내」 is kept (V180). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClosures(InquiryToldBehindClosureRepository closures) {
        this.closures = closures;
    }

    /** The fix-first hold standing on the approved version, or null. */
    private com.sellerops.replyhold.ReplyHoldService.HoldView fixHold(UUID orgId, UUID workItemId, int approvedVersion) {
        com.sellerops.replyhold.ReplyHoldService service = holds == null ? null : holds.getIfAvailable();
        if (service == null) {
            return null;
        }
        com.sellerops.replyhold.ReplyHoldService.HoldView hold = service.holdFor(orgId,
                com.sellerops.replyhold.ReplyDraftHold.Lane.INQUIRY, workItemId);
        return hold != null && hold.approved() && hold.draftVersion() == approvedVersion && "FIX".equals(hold.needs())
                ? hold : null;
    }

    /** Registered, the record that stands is 「처리 완료」, and the answer was written from none or another outcome. */
    static boolean toldBehind(String reopen, InquirySellerCheck standing, InquirySellerCheck was) {
        return REGISTERED.equals(reopen) && standing != null
                && com.sellerops.inquiry.sellercheck.SellerCheckOutcome.DONE == standing.outcomeValue()
                && (was == null || !standing.getId().equals(was.getId()))
                && (was == null || com.sellerops.inquiry.sellercheck.SellerCheckOutcome.DONE != was.outcomeValue());
    }

    /** The approved answer against the record that stands; empty when nothing is approved. */
    @Transactional(readOnly = true)
    public Optional<AnsweredView> answered(UUID orgId, InquiryWorkItem item, InquirySellerCheck standing) {
        if (item == null || !orgId.equals(item.getOrgId())) {
            return Optional.empty();
        }
        Optional<InquiryApproval> approval = approvals.findByWorkItemId(item.getId())
                .filter(a -> orgId.equals(a.getOrgId()));
        if (approval.isEmpty()) {
            return Optional.empty();
        }
        InquiryReplyDraft approved = drafts.findByWorkItemIdAndVersion(item.getId(),
                approval.get().getApprovedDraftVersion()).orElse(null);
        UUID from = approved == null ? null : recheck.writtenFrom(approved);
        InquirySellerCheck was = from == null ? null
                : checks.findById(from).filter(c -> orgId.equals(c.getOrgId())).orElse(null);
        boolean current = was != null && standing != null && standing.getId().equals(was.getId());
        Inquiry inquiry = inquiries.findById(item.getInquiryId()).filter(i -> orgId.equals(i.getOrgId())).orElse(null);
        boolean moved = !current && standing != null;
        com.sellerops.replyhold.ReplyHoldService.HoldView hold = moved ? null
                : fixHold(orgId, item.getId(), approval.get().getApprovedDraftVersion());
        String because = moved ? BECAUSE_RECORD : hold != null ? BECAUSE_HOLD : null;
        String reopen = because == null ? MATCHES : sendState(item, inquiry, approval.get());
        boolean behind = toldBehind(reopen, standing, was);
        InquiryToldBehindClosure closed = behind && closures != null
                ? closures.findByWorkItemIdAndCheckId(item.getId(), standing.getId()).orElse(null) : null;
        return Optional.of(new AnsweredView(approval.get().getApprovedDraftVersion(),
                approval.get().getApprovedFingerprint(), was == null ? null : was.getOutcome(),
                was == null ? null : was.outcomeValue().labelKo(), was == null ? null : was.getRecordedAt(), current,
                reopen, inquiry == null ? null : inquiry.getLastSeenAt(), because,
                hold == null ? null : hold.checkId(), hold == null ? java.util.List.of() : hold.codes(), behind,
                closed == null ? null : closed.getClosure(), closed == null ? null : closed.getClosedAt()));
    }

    /**
     * Where the send stands, as far as the service can know: {@link #OFFERED} only while nothing was dispatched — an
     * execution still pending, or refused before anything was created and never read back — AND the channel was read
     * after the approval and showed no answer ({@code inquiries.last_seen_at}, stamped by collection each time it sees
     * this inquiry with its answer state). Nothing sent but no such read is {@link #UNCONFIRMED}: not knowing is not
     * having checked.
     */
    String sendState(InquiryWorkItem item, Inquiry inquiry, InquiryApproval approval) {
        String unsent = unsentState(item, inquiry);
        if (!OFFERED.equals(unsent)) {
            return unsent;
        }
        java.time.Instant read = inquiry.getLastSeenAt();
        java.time.Instant approved = approval == null ? null : approval.getCreatedAt();
        return read != null && approved != null && read.isAfter(approved) ? OFFERED : UNCONFIRMED;
    }

    /** Whether anything was or may have been sent, before asking whether the channel was read since. */
    private String unsentState(InquiryWorkItem item, Inquiry inquiry) {
        if (item.getPhase() == InquiryWorkItemPhase.EXECUTED || item.getPhase() == InquiryWorkItemPhase.COMPLETED) {
            return REGISTERED;
        }
        if (inquiry == null || "ANSWERED".equals(inquiry.getStatus()) || inquiry.getAnsweredAt() != null) {
            return inquiry == null ? UNSETTLED : REGISTERED;
        }
        if (!APPROVED_NOT_SENT.contains(item.getPhase())) {
            return UNSETTLED;
        }
        InquiryExecution execution = executions.findByWorkItemId(item.getId()).orElse(null);
        if (execution == null) {
            return OFFERED;
        }
        boolean nothingCreated = execution.getProviderMessageNo() == null || execution.getProviderMessageNo().isBlank();
        return switch (execution.getStatus()) {
            case ACTION_PENDING -> nothingCreated ? OFFERED : UNSETTLED;
            case FAILED -> nothingCreated && verifications.countByExecutionId(execution.getId()) == 0
                    ? OFFERED : UNSETTLED;
            case EXECUTED, COMPLETED -> REGISTERED;
            default -> UNSETTLED;
        };
    }

    /**
     * Takes the approved answer back so a new one can be written from the record that stands. Refused unless: the screen's
     * record and approved fingerprint are still the standing ones; the approved version was written from another record;
     * nothing was dispatched ({@link #sendState} is {@link #OFFERED}); and the seller confirmed they did not post it by
     * hand. Everything is under a lock on the work item, so two devices cannot both take it back, and a send cannot
     * begin from a row this clears: the send is refused at registration by the same record change this requires.
     *
     * @return the record that stands, which the caller drafts from
     */
    @Transactional
    public InquirySellerCheck withdraw(UUID orgId, UUID workItemId, UUID checkId, String approvedFingerprint,
                                       boolean confirmedNotRegistered, String actor) {
        if (checkId == null) {
            throw ApiException.conflict(CHANGED);
        }
        return withdraw(orgId, workItemId, checkId, approvedFingerprint, null, confirmedNotRegistered, actor);
    }

    /**
     * As above, for either reason: {@code checkId} names the record on screen when the record moved on;
     * {@code holdCheckId} names the fix-first hold on screen when the approved answer is held for something a person must
     * fix (2026-11-05 tenth review — a policy conflict, a forbidden phrase, a changed figure). The same send conditions
     * hold for both. Taking it back resolves nothing: the reply goes back to the seller as it was, its hold is checked
     * again as a draft, and approving it again runs every check — a conflict that still stands still holds it.
     *
     * @return the record that stands, or null when there is none
     */
    @Transactional
    public InquirySellerCheck withdraw(UUID orgId, UUID workItemId, UUID checkId, String approvedFingerprint,
                                       String holdCheckId, boolean confirmedNotRegistered, String actor) {
        if (!confirmedNotRegistered) {
            throw ApiException.badRequest(NOT_CONFIRMED);
        }
        InquiryWorkItem item = em.find(InquiryWorkItem.class, workItemId, LockModeType.PESSIMISTIC_WRITE);
        if (item == null || !orgId.equals(item.getOrgId())) {
            throw ApiException.notFound("문의를 찾을 수 없습니다.");
        }
        InquiryApproval approval = approvals.findByWorkItemId(workItemId).filter(a -> orgId.equals(a.getOrgId()))
                .orElseThrow(() -> ApiException.conflict(CHANGED));
        if (approvedFingerprint == null || !approvedFingerprint.equals(approval.getApprovedFingerprint())) {
            throw ApiException.conflict(CHANGED);
        }
        InquirySellerCheck standing = checks
                .findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByRecordedAtDesc(orgId, workItemId)
                .orElse(null);
        InquiryReplyDraft approved = drafts.findByWorkItemIdAndVersion(workItemId, approval.getApprovedDraftVersion())
                .orElseThrow(() -> ApiException.conflict(CHANGED));
        UUID from = recheck.writtenFrom(approved);
        com.sellerops.replyhold.ReplyHoldService.HoldView hold = null;
        if (checkId != null) {
            if (standing == null || !standing.getId().equals(checkId)) {
                throw ApiException.conflict(CHANGED);
            }
            if (standing.getId().equals(from)) {
                throw ApiException.conflict(STANDS);
            }
        } else {
            if (standing != null && !standing.getId().equals(from)) {
                // The record moved on: that take-back names the record, so the screen showing a hold is behind.
                throw ApiException.conflict(CHANGED);
            }
            hold = fixHold(orgId, workItemId, approval.getApprovedDraftVersion());
            if (hold == null) {
                throw ApiException.conflict(holdCheckId == null ? NOT_HELD : CHANGED);
            }
            if (!hold.checkId().equals(holdCheckId)) {
                throw ApiException.conflict(CHANGED);
            }
        }
        Inquiry inquiry = inquiries.findById(item.getInquiryId()).filter(i -> orgId.equals(i.getOrgId())).orElse(null);
        String state = sendState(item, inquiry, approval);
        if (REGISTERED.equals(state)) {
            throw ApiException.conflict(ON_CHANNEL);
        }
        if (UNCONFIRMED.equals(state)) {
            throw ApiException.conflict(NOT_READ);
        }
        if (!OFFERED.equals(state)) {
            throw ApiException.conflict(NOT_SETTLED);
        }
        InquiryExecution execution = executions.findByWorkItemId(workItemId).orElse(null);
        if (execution != null) {
            em.lock(execution, LockModeType.PESSIMISTIC_WRITE);
            em.refresh(execution);
            // Read again under the lock: a send that began meanwhile is never cleared.
            boolean nothingCreated = execution.getProviderMessageNo() == null
                    || execution.getProviderMessageNo().isBlank();
            boolean stillUnsent = nothingCreated && (execution.getStatus() == InquiryExecutionStatus.ACTION_PENDING
                    || (execution.getStatus() == InquiryExecutionStatus.FAILED
                    && verifications.countByExecutionId(execution.getId()) == 0));
            if (!stillUnsent) {
                throw ApiException.conflict(NOT_SETTLED);
            }
        }

        InquiryApprovalWithdrawal row = new InquiryApprovalWithdrawal();
        row.setOrgId(orgId);
        row.setWorkItemId(workItemId);
        row.setApprovalId(approval.getId());
        row.setApprovedDraftVersion(approval.getApprovedDraftVersion());
        row.setApprovedFingerprint(approval.getApprovedFingerprint());
        row.setApprovalCommandId(approval.getCommandId());
        row.setApprover(approval.getApprover());
        row.setApprovedAt(approval.getCreatedAt());
        row.setSellerAccountId(approval.getSellerAccountId());
        row.setChannelId(approval.getChannelId());
        row.setTargetExternalId(approval.getTargetExternalId());
        row.setSourceSubtype(approval.getSourceSubtype());
        row.setActionKind(approval.getActionKind());
        row.setExecutionStatus(execution == null ? null : execution.getStatus().name());
        row.setExecutionFailureReason(execution == null ? null : execution.getFailureReason());
        row.setWrittenFromCheckId(from);
        row.setStandingCheckId(standing == null ? null : standing.getId());
        row.setReason(hold == null ? InquiryApprovalWithdrawal.SELLER_CHECK_CHANGED
                : InquiryApprovalWithdrawal.HELD_FOR_FIX);
        row.setHoldCodes(hold == null ? null : String.join(",", hold.codes()));
        row.setSellerConfirmedNotRegistered(true);
        row.setChannelReadAt(inquiry == null ? null : inquiry.getLastSeenAt());
        row.setWithdrawnBy(actor);
        withdrawn.saveAndFlush(row);

        InquiryWorkItemPhase from0 = item.getPhase();
        long before = audits.countByWorkItemIdAndEventType(workItemId, InquiryWorkItemEvent.APPROVAL_WITHDRAWN);
        InquiryWorkItemAudit audit = new InquiryWorkItemAudit();
        audit.setOrgId(orgId);
        audit.setWorkItemId(workItemId);
        audit.setCommandId("withdraw:" + approval.getId() + (before == 0 ? "" : "#" + (before + 1)));
        audit.setEventType(InquiryWorkItemEvent.APPROVAL_WITHDRAWN);
        audit.setPhaseFrom(from0);
        audit.setPhaseTo(InquiryWorkItemPhase.PROPOSED);
        audit.setActor(actor);
        audits.save(audit);

        if (execution != null) {
            executions.delete(execution);
        }
        intents.findByWorkItemId(workItemId).ifPresent(intents::delete);
        approvals.delete(approval);
        em.flush();
        item.setPhase(InquiryWorkItemPhase.PROPOSED);
        em.flush();

        // The approval remembered this answer as what the company says (USER_APPROVED). Taken back, it says nothing —
        // unless a person declared how far it may travel, which is their own statement and stays.
        memories.findByOrgIdAndOriginRef(orgId, "approved:" + workItemId + ":" + approval.getApprovedDraftVersion())
                .filter(m -> m.getReuseScope() == AnswerMemoryReuseScope.UNKNOWN)
                .ifPresent(memories::delete);
        if (hold != null) {
            // Back with the seller as it was: its hold is now a draft's, read again, so the reason stays on screen until
            // the text changes — and is gone only if what it conflicted with changed.
            holds.getIfAvailable().recheckInquiry(orgId, workItemId);
        }
        return standing;
    }

    /**
     * Every registered answer in the org that told the customer less than the record now says, and that the seller has
     * not closed for that record. Bounded: only a 「처리 완료」 record written after the approval can be one — an answer
     * approved after the record was written from it, or was held for not being.
     */
    @Transactional(readOnly = true)
    public java.util.List<ToldBehind> toldBehind(UUID orgId) {
        java.util.List<Object[]> rows = em.createQuery("select c, w, a from InquirySellerCheck c, InquiryWorkItem w, "
                        + "InquiryApproval a where c.orgId = :org and c.supersededAt is null and c.outcome = :done "
                        + "and w.id = c.workItemId and w.orgId = :org and a.workItemId = w.id and w.phase in :sent "
                        + "and c.recordedAt > a.createdAt", Object[].class)
                .setParameter("org", orgId)
                .setParameter("done", com.sellerops.inquiry.sellercheck.SellerCheckOutcome.DONE.name())
                .setParameter("sent", EnumSet.of(InquiryWorkItemPhase.EXECUTED, InquiryWorkItemPhase.COMPLETED))
                .getResultList();
        java.util.Set<UUID> closed = new java.util.HashSet<>();
        if (closures != null && !rows.isEmpty()) {
            closures.findByOrgIdAndCheckIdIn(orgId, rows.stream().map(r -> ((InquirySellerCheck) r[0]).getId()).toList())
                    .forEach(c -> closed.add(c.getCheckId()));
        }
        java.util.List<ToldBehind> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            InquirySellerCheck standing = (InquirySellerCheck) r[0];
            InquiryWorkItem item = (InquiryWorkItem) r[1];
            InquiryApproval approval = (InquiryApproval) r[2];
            if (closed.contains(standing.getId())) {
                continue;
            }
            InquirySellerCheck was = drafts.findByWorkItemIdAndVersion(item.getId(), approval.getApprovedDraftVersion())
                    .map(recheck::writtenFrom).flatMap(checks::findById).filter(c -> orgId.equals(c.getOrgId()))
                    .orElse(null);
            if (toldBehind(REGISTERED, standing, was)) {
                out.add(new ToldBehind(item.getId(), item.getInquiryId(), standing.getId(),
                        was == null ? null : was.getOutcome(), was == null ? null : was.outcomeValue().labelKo(),
                        standing.getNote(), standing.getRecordedAt()));
            }
        }
        return out;
    }

    /**
     * The seller closes 「처리는 완료됐지만 고객에게는 … 안내했어요」 for the record on screen: {@code closure} is
     * {@link InquiryToldBehindClosure#TOLD_SEPARATELY} or {@link InquiryToldBehindClosure#NOT_NEEDED}. Nothing is sent. A
     * second press for the same record is the first one's answer; a record that moved on since is refused.
     */
    @Transactional
    public InquiryToldBehindClosure closeToldBehind(UUID orgId, UUID workItemId, UUID checkId, String closure,
                                                    String actor) {
        if (!InquiryToldBehindClosure.TOLD_SEPARATELY.equals(closure)
                && !InquiryToldBehindClosure.NOT_NEEDED.equals(closure)) {
            throw ApiException.badRequest("「따로 안내함」 또는 「안내 필요 없음」을 골라 주세요.");
        }
        InquiryWorkItem item = em.find(InquiryWorkItem.class, workItemId, LockModeType.PESSIMISTIC_WRITE);
        if (item == null || !orgId.equals(item.getOrgId())) {
            throw ApiException.notFound("문의를 찾을 수 없습니다.");
        }
        Optional<InquiryToldBehindClosure> already = checkId == null ? Optional.empty()
                : closures.findByWorkItemIdAndCheckId(workItemId, checkId);
        if (already.isPresent()) {
            return already.get();
        }
        InquirySellerCheck standing = checks
                .findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByRecordedAtDesc(orgId, workItemId)
                .orElseThrow(() -> ApiException.conflict(CHANGED));
        if (!standing.getId().equals(checkId)) {
            throw ApiException.conflict(CHANGED);
        }
        AnsweredView view = answered(orgId, item, standing).orElseThrow(() -> ApiException.conflict(NOT_TOLD_BEHIND));
        if (!view.toldBehind()) {
            throw ApiException.conflict(NOT_TOLD_BEHIND);
        }
        InquiryToldBehindClosure row = new InquiryToldBehindClosure();
        row.setOrgId(orgId);
        row.setWorkItemId(workItemId);
        row.setCheckId(standing.getId());
        row.setClosure(closure);
        row.setClosedBy(actor);
        return closures.saveAndFlush(row);
    }
}
