package com.sellerops.inquiry.sellercheck;

import com.sellerops.common.ApiException;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seller's record of what they found on the seller centre, for one inquiry (2026-11-02 seller-check review).
 *
 * <p><b>Nothing is assumed.</b> An outcome must be chosen and a sentence written: a blank is refused, never filled from
 * an example. <b>Nothing spreads.</b> The record is read only by this inquiry's own evidence collection
 * ({@code InquiryEvidenceCollector}); no knowledge writer, precedent store or order binding reads this table.
 * <b>Nothing is overwritten.</b> A correction closes the current row and adds one, so a draft written from the old record
 * can still be traced to it.
 */
@Service
public class InquirySellerCheckService {

    public static final int NOTE_MAX = 300;
    /** The refusal when the record the screen showed is no longer the one that stands. */
    public static final String STALE = "다른 곳에서 확인한 결과가 먼저 저장됐어요. 새로 불러온 기록을 확인한 뒤 다시 적어 주세요.";

    /** What the screen shows of a record — the closed outcome, the seller's sentence, who confirmed it and when. */
    public record SellerCheckView(UUID id, String outcome, String outcomeKo, String note, String confirmedBy,
                                  String recordedBy, Instant recordedAt, java.time.LocalDate nextCheckOn) {
        static SellerCheckView of(InquirySellerCheck c) {
            return new SellerCheckView(c.getId(), c.getOutcome(), c.outcomeValue().labelKo(), c.getNote(),
                    c.getConfirmedBy(), c.getRecordedBy(), c.getRecordedAt(), c.getNextCheckOn());
        }
    }

    /**
     * The next-check day an action in progress starts with, until the seller picks another — two days after the record,
     * as the approved after-approval mockup preselects (2026-11-02 round 4). Never empty, so an inquiry whose action is
     * still open cannot leave the seller's list with nothing to bring it back.
     */
    public static final int DEFAULT_NEXT_CHECK_DAYS = 2;

    /** The seller's day, which is the day a next-check date is measured against. */
    public static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Seoul");
    private java.time.Clock clock = java.time.Clock.system(ZONE);

    /** Tests fix the day; nothing else does. */
    public void useClock(java.time.Clock clock) {
        this.clock = clock;
    }

    public java.time.LocalDate today() {
        return java.time.LocalDate.now(clock);
    }

    /**
     * Whether the reply is still the seller's to write — a new record drafts again only then. Once a reply is approved the
     * approval and what was sent stay as they are (2026-11-03 sixth review): a record corrected afterwards changes what
     * the seller is waiting on, not the answer.
     */
    @Transactional(readOnly = true)
    public boolean answerOpen(UUID orgId, UUID workItemId) {
        owned(orgId, workItemId);
        return workItems.findById(workItemId)
                .map(w -> com.sellerops.inquiry.workitem.InquiryWorkItemPhase.AWAITING_SELLER.contains(w.getPhase()))
                .orElse(false);
    }

    private final InquirySellerCheckRepository checks;
    private final InquiryWorkItemRepository workItems;

    public InquirySellerCheckService(InquirySellerCheckRepository checks, InquiryWorkItemRepository workItems) {
        this.checks = checks;
        this.workItems = workItems;
    }

    @Transactional(readOnly = true)
    public Optional<InquirySellerCheck> current(UUID orgId, UUID workItemId) {
        if (orgId == null || workItemId == null) {
            return Optional.empty();
        }
        return checks.findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByRecordedAtDesc(orgId, workItemId);
    }

    @Transactional(readOnly = true)
    public Optional<SellerCheckView> currentView(UUID orgId, UUID workItemId) {
        owned(orgId, workItemId);
        return current(orgId, workItemId).map(SellerCheckView::of);
    }

    /** What the seller is asked to check on this inquiry, as the last draft attempt read it — or null. */
    @Transactional(readOnly = true)
    public String ask(UUID orgId, UUID workItemId) {
        owned(orgId, workItemId);
        return workItems.findById(workItemId).map(w -> w.getSellerCheckAsk()).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SellerCheckView> history(UUID orgId, UUID workItemId) {
        owned(orgId, workItemId);
        return checks.findByOrgIdAndWorkItemIdOrderByRecordedAtAsc(orgId, workItemId).stream()
                .map(SellerCheckView::of).toList();
    }

    /**
     * Records what the seller found, closing the record it replaces. Refuses a missing outcome and a blank sentence —
     * the reply would otherwise be written from nothing the seller said.
     */
    @Transactional
    public SellerCheckView record(UUID orgId, UUID workItemId, String outcome, String note, String actor) {
        return record(orgId, workItemId, outcome, note, actor, null, false, null);
    }

    /** As the unchecked {@link #record(UUID, UUID, String, String, String)}, with the next-check date. */
    @Transactional
    public SellerCheckView record(UUID orgId, UUID workItemId, String outcome, String note, String actor,
                                  java.time.LocalDate nextCheckOn) {
        return record(orgId, workItemId, outcome, note, actor, null, false, nextCheckOn);
    }

    /**
     * As {@link #record(UUID, UUID, String, String, String)}, from a screen that names the record it was showing
     * ({@code replaces}, null when it showed none). When that is no longer the current record — the same press sent twice,
     * or the record corrected on another device — nothing is written and the seller is asked to look at the record that
     * stands now (2026-11-02 fourth review: 중복 클릭 · PC·휴대폰 동시 수정). Two presses that pass this check at the same
     * moment are still one record: the second is refused by the one-current-record index.
     */
    @Transactional
    public SellerCheckView record(UUID orgId, UUID workItemId, String outcome, String note, String actor,
                                  UUID replaces) {
        return record(orgId, workItemId, outcome, note, actor, replaces, true, null);
    }

    /**
     * The screen's press: the record it was showing ({@code replaces}) and, for 「접수·진행 중」, the day the seller will
     * look again ({@code nextCheckOn}, 2026-11-03 sixth review; {@link #DEFAULT_NEXT_CHECK_DAYS} later when not given, never
     * before today). No other outcome keeps a day — there is nothing left to check.
     */
    @Transactional
    public SellerCheckView record(UUID orgId, UUID workItemId, String outcome, String note, String actor,
                                  UUID replaces, java.time.LocalDate nextCheckOn) {
        return record(orgId, workItemId, outcome, note, actor, replaces, true, nextCheckOn);
    }

    private SellerCheckView record(UUID orgId, UUID workItemId, String outcome, String note, String actor,
                                   UUID replaces, boolean checkReplaces, java.time.LocalDate nextCheckOn) {
        owned(orgId, workItemId);
        SellerCheckOutcome o = SellerCheckOutcome.parse(outcome);
        if (o == null) {
            throw ApiException.badRequest("확인한 결과를 하나 골라 주세요.");
        }
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("확인한 내용을 한 줄 적어 주세요. 비워 두면 답변을 만들지 않아요.");
        }
        if (text.length() > NOTE_MAX) {
            throw ApiException.badRequest("확인한 내용은 " + NOTE_MAX + "자 안으로 적어 주세요.");
        }
        java.time.LocalDate nextCheck = null;
        if (o == SellerCheckOutcome.IN_PROGRESS) {
            nextCheck = nextCheckOn == null ? today().plusDays(DEFAULT_NEXT_CHECK_DAYS) : notBeforeToday(nextCheckOn);
        }
        Instant now = Instant.now();
        Optional<InquirySellerCheck> standing = current(orgId, workItemId);
        if (checkReplaces && !java.util.Objects.equals(standing.map(InquirySellerCheck::getId).orElse(null), replaces)) {
            throw ApiException.conflict(STALE);
        }
        standing.ifPresent(previous -> {
            previous.setSupersededAt(now);
            checks.saveAndFlush(previous);
        });
        InquirySellerCheck c = new InquirySellerCheck();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setWorkItemId(workItemId);
        c.setOutcome(o.name());
        c.setNote(text);
        c.setConfirmedBy(InquirySellerCheck.ConfirmedBy.SELLER.name());
        c.setRecordedBy(actor == null || actor.isBlank() ? "SELLER" : actor.length() > 120 ? actor.substring(0, 120)
                : actor);
        c.setRecordedAt(now);
        c.setNextCheckOn(nextCheck);
        try {
            checks.saveAndFlush(c);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict(STALE);
        }
        return SellerCheckView.of(c);
    }

    /**
     * 「이 날로 정하기」 — moves the next-check day of the record that stands. The one thing on a record that changes in place:
     * it is the seller's own schedule, not what they found, so the record stays the record the reply was written from and
     * nothing is held or drafted again (2026-11-03 sixth review). Only on the record the screen showed, and only while it
     * says 「접수·진행 중」.
     */
    @Transactional
    public SellerCheckView setNextCheck(UUID orgId, UUID workItemId, UUID checkId, java.time.LocalDate nextCheckOn) {
        owned(orgId, workItemId);
        InquirySellerCheck standing = current(orgId, workItemId).orElseThrow(() -> ApiException.conflict(STALE));
        if (!standing.getId().equals(checkId)) {
            throw ApiException.conflict(STALE);
        }
        if (standing.outcomeValue() != SellerCheckOutcome.IN_PROGRESS) {
            throw ApiException.badRequest("진행 중인 일에만 다시 확인할 날을 정해요.");
        }
        if (nextCheckOn == null) {
            throw ApiException.badRequest("다시 확인할 날짜를 골라 주세요.");
        }
        standing.setNextCheckOn(notBeforeToday(nextCheckOn));
        checks.saveAndFlush(standing);
        return SellerCheckView.of(standing);
    }

    private java.time.LocalDate notBeforeToday(java.time.LocalDate day) {
        if (day.isBefore(today())) {
            throw ApiException.badRequest("다시 확인할 날짜는 오늘이나 그 뒤로 골라 주세요.");
        }
        return day;
    }

    /**
     * The sentence the evidence pool carries — what the judge reads and the drafter is shown. It says whose word it is
     * and that it holds for this inquiry only, so neither can mistake it for the store's rule or the channel's own state.
     */
    public static String evidenceText(InquirySellerCheck c) {
        return "판매자가 판매자센터에서 직접 확인한 이 문의의 결과(이 문의에만 해당, 채널이 확인한 사실 아님): "
                + c.outcomeValue().labelKo() + " — " + c.getNote();
    }

    public static String evidenceLabel(InquirySellerCheck c) {
        return "판매자 확인 · " + c.outcomeValue().labelKo();
    }

    private void owned(UUID orgId, UUID workItemId) {
        if (orgId == null || workItemId == null || workItems.findById(workItemId)
                .filter(w -> orgId.equals(w.getOrgId())).isEmpty()) {
            throw ApiException.notFound("문의를 찾을 수 없어요.");
        }
    }
}
