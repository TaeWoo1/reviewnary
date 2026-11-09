package com.sellerops.inquiry.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import com.sellerops.inquiry.reply.InquiryReplyDraft;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckService;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemEvent;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.knowledge.memory.AnswerMemory;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.memory.AnswerMemoryReuseScope;
import com.sellerops.knowledge.memory.AnswerMemoryStrength;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the customer was told against the record now, and the way back from an approved answer the record has outrun
 * (2026-11-05 seventh review). Real rows for every foreign key, so the class runs on PostgreSQL as it does on H2; the
 * approval is bound by the production binding writer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class InquiryApprovalWithdrawalTest {

    @Autowired OrganizationRepository organizations;
    @Autowired ChannelRepository channels;
    @Autowired SellerAccountRepository sellerAccounts;
    @Autowired InquiryRepository inquiries;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquirySellerCheckRepository checkRows;
    @Autowired InquiryReplyDraftRepository drafts;
    @Autowired InquiryWorkItemAuditRepository audits;
    @Autowired InquiryApprovalRepository approvals;
    @Autowired InquiryActionIntentRepository intents;
    @Autowired InquiryExecutionRepository executions;
    @Autowired InquiryVerificationRepository verifications;
    @Autowired InquiryApprovalWithdrawalRepository withdrawn;
    @Autowired AnswerMemoryRepository memories;
    @Autowired PlatformTransactionManager tx;
    @Autowired EntityManager em;
    @Autowired InquiryToldBehindClosureRepository closures;

    private InquirySellerCheckService checks;
    private InquiryPublishBindingWriter binding;
    private InquiryApprovalWithdrawalService service;
    private UUID org;
    private UUID channelId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        checks = new InquirySellerCheckService(checkRows, workItems);
        binding = new InquiryPublishBindingWriter(workItems, approvals, intents, executions, audits, tx);
        // What a version was written from is the record it names — the production rule for every version written since
        // versions named one; the cited-evidence fallback for older model versions is InquiryDraftRecheck's own test.
        InquiryDraftRecheck recheck = mock(InquiryDraftRecheck.class);
        when(recheck.writtenFrom(any())).thenAnswer(i -> ((InquiryReplyDraft) i.getArgument(0)).getSellerCheckId());
        service = new InquiryApprovalWithdrawalService(em, approvals, intents, executions, verifications, withdrawn,
                drafts, checkRows, inquiries, audits, recheck, memories);
        service.setClosures(closures);
        Organization o = new Organization();
        o.setName("승인 거두기 QA");
        org = organizations.save(o).getId();
        channelId = channels.findByCode("WITHDRAW-QA").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("WITHDRAW-QA");
            c.setNameKo("승인 거두기 QA");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSortOrder(0);
            return channels.save(c);
        }).getId();
        SellerAccount a = new SellerAccount();
        a.setOrgId(org);
        a.setChannelId(channelId);
        a.setConnectionStatus(ChannelStatus.PENDING);
        a.setFileUpload(false);
        accountId = sellerAccounts.save(a).getId();
    }

    private InquiryWorkItem item() {
        Inquiry q = new Inquiry();
        q.setOrgId(org);
        q.setChannelId(channelId);
        q.setTitle("환불 문의");
        q.setBody("합성 문의");
        q.setStatus("UNANSWERED");
        q.setExternalId("ext-" + UUID.randomUUID());
        q.setReceivedAt(Instant.parse("2026-11-01T00:00:00Z"));
        q.setLastSeenAt(Instant.parse("2026-11-04T01:00:00Z"));
        UUID inquiryId = inquiries.save(q).getId();
        InquiryWorkItem w = new InquiryWorkItem();
        w.setOrgId(org);
        w.setInquiryId(inquiryId);
        w.setSellerAccountId(accountId);
        w.setChannelId(channelId);
        w.setPhase(InquiryWorkItemPhase.PROPOSED);
        return workItems.saveAndFlush(w);
    }

    private InquiryReplyDraft draft(InquiryWorkItem w, int version, String text, UUID writtenFrom) {
        InquiryReplyDraft d = new InquiryReplyDraft();
        d.setOrgId(org);
        d.setWorkItemId(w.getId());
        d.setVersion(version);
        d.setAnswerStatus(1);
        d.setTitle("안내");
        d.setComments(text);
        d.setContentFingerprint(String.format("%064d", version) .replace('0', (char) ('a' + version)));
        d.setFingerprintAlgorithm("TEST");
        d.setCreatedBy("SELLER:qa");
        d.setSellerCheckId(writtenFrom);
        return drafts.saveAndFlush(d);
    }

    private InquiryWorkItem approve(InquiryWorkItem w, InquiryReplyDraft d) {
        binding.bind(w, d, new InquiryPublishBindingWriter.ApprovalTarget(accountId, channelId, "ext", null),
                "cmd-" + UUID.randomUUID(), "SELLER:qa");
        em.flush();
        return workItems.findById(w.getId()).orElseThrow();
    }

    /** Answered 「진행 중」, approved, and the record then moved to 「처리 완료」. */
    private record Outrun(InquiryWorkItem item, UUID was, UUID now, InquiryReplyDraft approved) {
    }

    private Outrun outrun() {
        InquiryWorkItem w = item();
        UUID was = checks.record(org, w.getId(), "IN_PROGRESS", "환불 대기", "SELLER:qa").id();
        InquiryReplyDraft v1 = draft(w, 1, "요청하신 환불은 현재 대기 중입니다.", was);
        w = approve(w, v1);
        UUID now = checks.record(org, w.getId(), "DONE", "카드 결제 취소로 환불 처리", "SELLER:qa", was, null).id();
        em.flush();
        readAfterApproval(w);
        return new Outrun(workItems.findById(w.getId()).orElseThrow(), was, now, v1);
    }

    /** Collection saw the inquiry, unanswered, after the approval — what OFFERED stands on. */
    private void readAfterApproval(InquiryWorkItem w) {
        Inquiry q = inquiries.findById(w.getInquiryId()).orElseThrow();
        q.setLastSeenAt(approvals.findByWorkItemId(w.getId()).orElseThrow().getCreatedAt().plusSeconds(60));
        inquiries.saveAndFlush(q);
    }

    private InquiryApprovalWithdrawalService.AnsweredView answered(InquiryWorkItem w) {
        return service.answered(org, workItems.findById(w.getId()).orElseThrow(),
                checks.current(org, w.getId()).orElse(null)).orElseThrow();
    }

    @Test
    @DisplayName("what the customer was told is the record the approved version was written from, not the record now")
    void theAnswerSaysWhatItWasWrittenFrom() {
        Outrun o = outrun();
        InquiryApprovalWithdrawalService.AnsweredView view = answered(o.item());
        assertThat(view.outcome()).isEqualTo("IN_PROGRESS");
        assertThat(view.current()).isFalse();
        assertThat(view.reopen()).isEqualTo(InquiryApprovalWithdrawalService.OFFERED);
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.channelReadAt()).isAfter(approvals.findByWorkItemId(o.item().getId()).orElseThrow().getCreatedAt());

        // Registered afterwards: the same two facts, and nothing to take back.
        InquiryWorkItem w = o.item();
        w.setPhase(InquiryWorkItemPhase.COMPLETED);
        workItems.saveAndFlush(w);
        InquiryApprovalWithdrawalService.AnsweredView sent = answered(w);
        assertThat(sent.outcome()).isEqualTo("IN_PROGRESS");
        assertThat(sent.current()).isFalse();
        assertThat(sent.reopen()).isEqualTo(InquiryApprovalWithdrawalService.REGISTERED);
    }

    @Test
    @DisplayName("an answer written from the record that stands says so, and there is nothing to take back")
    void anAnswerOnTheStandingRecordMatches() {
        InquiryWorkItem w = item();
        UUID was = checks.record(org, w.getId(), "IN_PROGRESS", "환불 대기", "SELLER:qa").id();
        w = approve(w, draft(w, 1, "요청하신 환불은 현재 대기 중입니다.", was));
        InquiryApprovalWithdrawalService.AnsweredView view = answered(w);
        assertThat(view.current()).isTrue();
        assertThat(view.reopen()).isEqualTo(InquiryApprovalWithdrawalService.MATCHES);
        UUID id = w.getId();
        String fp = view.fingerprint();
        assertThatThrownBy(() -> service.withdraw(org, id, was, fp, true, "SELLER:qa"))
                .isInstanceOf(ApiException.class).hasMessage(InquiryApprovalWithdrawalService.STANDS);
        assertThat(approvals.findByWorkItemId(id)).isPresent();
    }

    @Test
    @DisplayName("taking an outrun approval back keeps it in full, clears the standing rows, returns the reply to the "
            + "seller, and a second approval goes through")
    void theWayBackKeepsTheApprovalAndAllowsASecond() {
        Outrun o = outrun();
        InquiryApproval before = approvals.findByWorkItemId(o.item().getId()).orElseThrow();
        AnswerMemory remembered = memory(o.item(), 1, AnswerMemoryReuseScope.UNKNOWN);

        service.withdraw(org, o.item().getId(), o.now(), before.getApprovedFingerprint(), true, "SELLER:qa");
        em.flush();
        em.clear();

        assertThat(approvals.findByWorkItemId(o.item().getId())).isEmpty();
        assertThat(intents.findByWorkItemId(o.item().getId())).isEmpty();
        assertThat(executions.findByWorkItemId(o.item().getId())).isEmpty();
        assertThat(workItems.findById(o.item().getId()).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.PROPOSED);
        assertThat(drafts.findByWorkItemIdAndVersion(o.item().getId(), 1)).as("the approved text is kept").isPresent();
        var kept = withdrawn.findAllByOrgIdAndWorkItemIdOrderByWithdrawnAtAsc(org, o.item().getId());
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getApprovalId()).isEqualTo(before.getId());
        assertThat(kept.get(0).getApprovedFingerprint()).isEqualTo(before.getApprovedFingerprint());
        assertThat(kept.get(0).getApprovalCommandId()).isEqualTo(before.getCommandId());
        assertThat(kept.get(0).getApprover()).isEqualTo("SELLER:qa");
        assertThat(kept.get(0).getExecutionStatus()).isEqualTo("ACTION_PENDING");
        assertThat(kept.get(0).getWrittenFromCheckId()).isEqualTo(o.was());
        assertThat(kept.get(0).getStandingCheckId()).isEqualTo(o.now());
        assertThat(kept.get(0).isSellerConfirmedNotRegistered()).isTrue();
        assertThat(kept.get(0).getChannelReadAt()).as("the read it stood on, after the approval")
                .isAfter(kept.get(0).getApprovedAt());
        assertThat(audits.countByWorkItemIdAndEventType(o.item().getId(), InquiryWorkItemEvent.APPROVAL_WITHDRAWN))
                .isEqualTo(1);
        assertThat(memories.findById(remembered.getId())).as("an approval taken back says nothing").isEmpty();

        InquiryWorkItem again = workItems.findById(o.item().getId()).orElseThrow();
        InquiryReplyDraft v2 = draft(again, 2, "요청하신 환불은 카드 결제 취소로 처리되었습니다.", o.now());
        again = approve(again, v2);
        assertThat(again.getPhase()).isEqualTo(InquiryWorkItemPhase.ACTION_PENDING);
        assertThat(approvals.findByWorkItemId(again.getId()).orElseThrow().getApprovedDraftVersion()).isEqualTo(2);
        assertThat(audits.countByWorkItemIdAndEventType(again.getId(), InquiryWorkItemEvent.ACTION_INTENT_CREATED))
                .as("the second intent is a second row, not a refused insert").isEqualTo(2);
        assertThat(answered(again).current()).isTrue();
    }

    @Test
    @DisplayName("a remembered answer a person declared the reach of stays when its approval is taken back")
    void aDeclaredMemoryStays() {
        Outrun o = outrun();
        AnswerMemory declared = memory(o.item(), 1, AnswerMemoryReuseScope.CASE_ONLY);
        service.withdraw(org, o.item().getId(), o.now(), o.approved().getContentFingerprint(), true, "SELLER:qa");
        em.flush();
        assertThat(memories.findById(declared.getId())).isPresent();
    }

    @Test
    @DisplayName("nothing is taken back without the seller's word, on a stale screen, or once a send went out")
    void refusals() {
        Outrun o = outrun();
        UUID id = o.item().getId();
        String fp = o.approved().getContentFingerprint();
        assertThatThrownBy(() -> service.withdraw(org, id, o.now(), fp, false, "SELLER:qa"))
                .hasMessage(InquiryApprovalWithdrawalService.NOT_CONFIRMED);
        assertThatThrownBy(() -> service.withdraw(org, id, o.was(), fp, true, "SELLER:qa"))
                .as("the screen showed the older record").hasMessage(InquiryApprovalWithdrawalService.CHANGED);
        assertThatThrownBy(() -> service.withdraw(org, id, o.now(), "f".repeat(64), true, "SELLER:qa"))
                .as("the screen showed another approval").hasMessage(InquiryApprovalWithdrawalService.CHANGED);
        UUID otherOrg = organizations.save(newOrg()).getId();
        assertThatThrownBy(() -> service.withdraw(otherOrg, id, o.now(), fp, true, "SELLER:qa"))
                .isInstanceOf(ApiException.class);

        InquiryExecution e = executions.findByWorkItemId(id).orElseThrow();
        e.setStatus(InquiryExecutionStatus.DELIVERY_UNKNOWN);
        executions.saveAndFlush(e);
        assertThat(answered(o.item()).reopen()).isEqualTo(InquiryApprovalWithdrawalService.UNSETTLED);
        assertThatThrownBy(() -> service.withdraw(org, id, o.now(), fp, true, "SELLER:qa"))
                .hasMessage(InquiryApprovalWithdrawalService.NOT_SETTLED);

        e.setStatus(InquiryExecutionStatus.FAILED);
        e.setProviderMessageNo("created-over-there");
        executions.saveAndFlush(e);
        assertThat(answered(o.item()).reopen()).as("not offered on screen either").isEqualTo(
                InquiryApprovalWithdrawalService.UNSETTLED);
        assertThatThrownBy(() -> service.withdraw(org, id, o.now(), fp, true, "SELLER:qa"))
                .as("something may exist on the channel").hasMessage(InquiryApprovalWithdrawalService.NOT_SETTLED);

        e.setProviderMessageNo(null);
        executions.saveAndFlush(e);
        Inquiry q = inquiries.findById(o.item().getInquiryId()).orElseThrow();
        q.setStatus("ANSWERED");
        inquiries.saveAndFlush(q);
        assertThat(answered(o.item()).reopen()).isEqualTo(InquiryApprovalWithdrawalService.REGISTERED);
        assertThatThrownBy(() -> service.withdraw(org, id, o.now(), fp, true, "SELLER:qa"))
                .as("the channel read showed an answer").hasMessage(InquiryApprovalWithdrawalService.ON_CHANNEL);

        assertThat(approvals.findByWorkItemId(id)).isPresent();
        assertThat(withdrawn.countByWorkItemId(id)).isZero();
    }

    @Test
    @DisplayName("nothing sent but the channel not read since the approval — or never — is 「not known」, not 「checked」: "
            + "nothing is taken back")
    void notReadSinceTheApprovalIsNotKnown() {
        Outrun o = outrun();
        UUID id = o.item().getId();
        String fp = o.approved().getContentFingerprint();
        Inquiry q = inquiries.findById(o.item().getInquiryId()).orElseThrow();
        q.setLastSeenAt(approvals.findByWorkItemId(id).orElseThrow().getCreatedAt().minusSeconds(60));
        inquiries.saveAndFlush(q);
        assertThat(answered(o.item()).reopen()).as("read only before the approval")
                .isEqualTo(InquiryApprovalWithdrawalService.UNCONFIRMED);
        assertThatThrownBy(() -> service.withdraw(org, id, o.now(), fp, true, "SELLER:qa"))
                .hasMessage(InquiryApprovalWithdrawalService.NOT_READ);
        q.setLastSeenAt(null);
        inquiries.saveAndFlush(q);
        assertThat(answered(o.item()).reopen()).as("never read").isEqualTo(InquiryApprovalWithdrawalService.UNCONFIRMED);
        assertThatThrownBy(() -> service.withdraw(org, id, o.now(), fp, true, "SELLER:qa"))
                .hasMessage(InquiryApprovalWithdrawalService.NOT_READ);
        assertThat(approvals.findByWorkItemId(id)).isPresent();
        assertThat(withdrawn.countByWorkItemId(id)).isZero();
    }

    @Test
    @DisplayName("a send refused before anything was created may be taken back")
    void aRefusedSendWithNothingCreatedMayBeTakenBack() {
        Outrun o = outrun();
        InquiryExecution e = executions.findByWorkItemId(o.item().getId()).orElseThrow();
        e.setStatus(InquiryExecutionStatus.FAILED);
        e.setFailureReason("TARGET_MOVED");
        executions.saveAndFlush(e);
        InquiryWorkItem w = o.item();
        w.setPhase(InquiryWorkItemPhase.FAILED);
        workItems.saveAndFlush(w);
        assertThat(answered(w).reopen()).isEqualTo(InquiryApprovalWithdrawalService.OFFERED);
        service.withdraw(org, w.getId(), o.now(), o.approved().getContentFingerprint(), true, "SELLER:qa");
        em.flush();
        var kept = withdrawn.findAllByOrgIdAndWorkItemIdOrderByWithdrawnAtAsc(org, w.getId());
        assertThat(kept.get(0).getExecutionStatus()).isEqualTo("FAILED");
        assertThat(kept.get(0).getExecutionFailureReason()).isEqualTo("TARGET_MOVED");
    }

    private Organization newOrg() {
        Organization o = new Organization();
        o.setName("다른 가게");
        return o;
    }

    private AnswerMemory memory(InquiryWorkItem w, int version, AnswerMemoryReuseScope scope) {
        AnswerMemory m = new AnswerMemory();
        m.setOrgId(org);
        m.setTopicSignature("환불");
        m.setAnswerBody("요청하신 환불은 현재 대기 중입니다.");
        m.setNormalized("요청하신 환불은 현재 대기 중입니다");
        m.setStrength(AnswerMemoryStrength.USER_APPROVED);
        m.setOriginRef("approved:" + w.getId() + ":" + version);
        m.setReuseScope(scope);
        return memories.saveAndFlush(m);
    }

    /** A hold on {@code version} as the hold service reads it: {@code needs} FIX · EXCEPTION · CONFIRM. */
    private static com.sellerops.replyhold.ReplyHoldService.HoldView hold(InquiryWorkItem w, int version, boolean approved,
                                                                          String needs, String checkId) {
        return new com.sellerops.replyhold.ReplyHoldService.HoldView("INQUIRY", w.getId(), version, approved,
                java.util.List.of("POLICY_CONFLICT"), needs, false, "정책과 맞지 않아요", java.util.List.of("반품 기간"),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), Instant.now(), checkId, java.util.List.of(),
                java.util.List.of("반품 기간"));
    }

    private com.sellerops.replyhold.ReplyHoldService holdsReturning(
            java.util.function.Function<UUID, com.sellerops.replyhold.ReplyHoldService.HoldView> standing) {
        com.sellerops.replyhold.ReplyHoldService holds = mock(com.sellerops.replyhold.ReplyHoldService.class);
        when(holds.holdFor(any(), any(), any())).thenAnswer(i -> standing.apply(i.getArgument(2)));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.sellerops.replyhold.ReplyHoldService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(holds);
        service.setHolds(provider);
        return holds;
    }

    /**
     * Tenth review (2026-11-05): an approved answer held at registration for something a person must fix — a policy
     * conflict, not a changed record — is taken back under the same send conditions, kept in full with the hold's codes,
     * and checked again as the seller's draft. Taking it back resolves nothing; a hold that can be confirmed is not one.
     */
    @Test
    @DisplayName("an approved answer held for a fix is taken back on the same conditions and checked again as a draft")
    void anAnswerHeldForAFixIsTakenBack() {
        InquiryWorkItem w = item();
        InquiryReplyDraft v1 = draft(w, 1, "반품은 수령 후 3일 안에만 가능합니다.", null);
        InquiryWorkItem approved = approve(w, v1);
        UUID id = approved.getId();
        String fp = approvals.findByWorkItemId(id).orElseThrow().getApprovedFingerprint();
        String[] needs = {"FIX"};
        com.sellerops.replyhold.ReplyHoldService holds = holdsReturning(subject -> subject.equals(id)
                ? hold(approved, 1, true, needs[0], "hold-1") : null);

        InquiryApprovalWithdrawalService.AnsweredView before = answered(approved);
        assertThat(before.because()).isEqualTo(InquiryApprovalWithdrawalService.BECAUSE_HOLD);
        assertThat(before.holdCheckId()).isEqualTo("hold-1");
        assertThat(before.holdCodes()).containsExactly("POLICY_CONFLICT");
        assertThat(before.reopen()).as("nothing read since the approval").isEqualTo(
                InquiryApprovalWithdrawalService.UNCONFIRMED);
        assertThatThrownBy(() -> service.withdraw(org, id, null, fp, "hold-1", true, "SELLER:qa"))
                .isInstanceOf(ApiException.class).hasMessage(InquiryApprovalWithdrawalService.NOT_READ);

        readAfterApproval(approved);
        assertThat(answered(approved).reopen()).isEqualTo(InquiryApprovalWithdrawalService.OFFERED);
        assertThatThrownBy(() -> service.withdraw(org, id, null, fp, "hold-1", false, "SELLER:qa"))
                .isInstanceOf(ApiException.class).hasMessage(InquiryApprovalWithdrawalService.NOT_CONFIRMED);
        assertThatThrownBy(() -> service.withdraw(org, id, null, fp, "hold-0", true, "SELLER:qa"))
                .as("the screen's hold is not the one standing").isInstanceOf(ApiException.class)
                .hasMessage(InquiryApprovalWithdrawalService.CHANGED);
        needs[0] = "CONFIRM";
        assertThat(answered(approved).because()).as("a hold a confirmation clears is not a fix").isNull();
        assertThat(answered(approved).reopen()).isEqualTo(InquiryApprovalWithdrawalService.MATCHES);
        assertThatThrownBy(() -> service.withdraw(org, id, null, fp, "hold-1", true, "SELLER:qa"))
                .isInstanceOf(ApiException.class).hasMessage(InquiryApprovalWithdrawalService.CHANGED);
        needs[0] = "FIX";
        assertThat(approvals.findByWorkItemId(id)).as("every refusal left the approval").isPresent();

        service.withdraw(org, id, null, fp, "hold-1", true, "SELLER:qa");
        em.flush();
        em.clear();

        assertThat(approvals.findByWorkItemId(id)).isEmpty();
        assertThat(workItems.findById(id).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.PROPOSED);
        assertThat(drafts.findTopByWorkItemIdOrderByVersionDesc(id).orElseThrow().getVersion())
                .as("back as it was: nothing drafted").isEqualTo(1);
        var kept = withdrawn.findAllByOrgIdAndWorkItemIdOrderByWithdrawnAtAsc(org, id);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getReason()).isEqualTo(InquiryApprovalWithdrawal.HELD_FOR_FIX);
        assertThat(kept.get(0).getHoldCodes()).isEqualTo("POLICY_CONFLICT");
        assertThat(kept.get(0).getStandingCheckId()).as("no record stood").isNull();
        verify(holds).recheckInquiry(org, id);
    }

    @Test
    @DisplayName("a hold on an answer whose record moved on is taken back by the record, and a hold on another version "
            + "is not the approved answer's")
    void aHoldIsTheApprovedAnswersOwn() {
        Outrun o = outrun();
        UUID id = o.item().getId();
        String fp = approvals.findByWorkItemId(id).orElseThrow().getApprovedFingerprint();
        holdsReturning(subject -> hold(o.item(), 1, true, "FIX", "hold-1"));
        assertThat(answered(o.item()).because()).isEqualTo(InquiryApprovalWithdrawalService.BECAUSE_RECORD);
        assertThatThrownBy(() -> service.withdraw(org, id, null, fp, "hold-1", true, "SELLER:qa"))
                .as("the record moved: the screen showing a hold is behind").isInstanceOf(ApiException.class)
                .hasMessage(InquiryApprovalWithdrawalService.CHANGED);

        InquiryWorkItem w = item();
        InquiryWorkItem approved = approve(w, draft(w, 1, "반품은 수령 후 3일 안에만 가능합니다.", null));
        holdsReturning(subject -> hold(approved, 2, false, "FIX", "hold-2"));
        assertThat(answered(approved).because()).isNull();
        UUID other = approved.getId();
        String fp2 = approvals.findByWorkItemId(other).orElseThrow().getApprovedFingerprint();
        assertThatThrownBy(() -> service.withdraw(org, other, null, fp2, null, true, "SELLER:qa"))
                .isInstanceOf(ApiException.class).hasMessage(InquiryApprovalWithdrawalService.NOT_HELD);
    }

    /**
     * Tenth review (2026-11-05): the answer told the customer 「진행 중」, it is registered, and the seller then recorded
     * 「처리 완료」. The Home shows it until the seller closes it for that record; the same record never brings it back,
     * a later one does, and closing sends nothing.
     */
    @Test
    @DisplayName("a registered answer behind the record is a check until closed for that record")
    void aRegisteredAnswerBehindTheRecordIsACheck() {
        Outrun o = outrun();
        UUID id = o.item().getId();
        assertThat(answered(o.item()).toldBehind()).as("not registered yet: it can still be taken back").isFalse();
        assertThat(service.toldBehind(org)).isEmpty();

        InquiryWorkItem w = workItems.findById(id).orElseThrow();
        w.setPhase(InquiryWorkItemPhase.COMPLETED);
        workItems.saveAndFlush(w);
        InquiryApprovalWithdrawalService.AnsweredView view = answered(w);
        assertThat(view.toldBehind()).isTrue();
        assertThat(view.closure()).isNull();
        assertThat(service.toldBehind(org)).singleElement().satisfies(t -> {
            assertThat(t.workItemId()).isEqualTo(id);
            assertThat(t.checkId()).isEqualTo(o.now());
            assertThat(t.toldOutcome()).isEqualTo("IN_PROGRESS");
        });

        assertThatThrownBy(() -> service.closeToldBehind(org, id, o.now(), "SENT", "SELLER:qa"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.closeToldBehind(org, id, o.was(), "NOT_NEEDED", "SELLER:qa"))
                .as("a record that no longer stands").isInstanceOf(ApiException.class)
                .hasMessage(InquiryApprovalWithdrawalService.CHANGED);
        InquiryToldBehindClosure closed = service.closeToldBehind(org, id, o.now(), "TOLD_SEPARATELY", "SELLER:qa");
        InquiryToldBehindClosure again = service.closeToldBehind(org, id, o.now(), "NOT_NEEDED", "SELLER:qa");
        assertThat(again.getId()).as("a second press is the first one's answer").isEqualTo(closed.getId());
        assertThat(again.getClosure()).isEqualTo("TOLD_SEPARATELY");
        assertThat(service.toldBehind(org)).isEmpty();
        assertThat(answered(w).closure()).isEqualTo("TOLD_SEPARATELY");
        assertThat(approvals.findByWorkItemId(id)).as("closing sends and changes nothing").isPresent();
        assertThat(workItems.findById(id).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.COMPLETED);

        UUID later = checks.record(org, id, "DONE", "카드 결제 취소 확인, 환불 완료", "SELLER:qa", o.now(), null).id();
        em.flush();
        assertThat(service.toldBehind(org)).as("a later record brings it back").singleElement()
                .satisfies(t -> assertThat(t.checkId()).isEqualTo(later));
    }

    @Test
    @DisplayName("an answer written from the finished record, or a record that is not 「처리 완료」, is not behind")
    void notBehind() {
        InquiryWorkItem w = item();
        UUID done = checks.record(org, w.getId(), "DONE", "환불 처리", "SELLER:qa").id();
        w = approve(w, draft(w, 1, "환불 처리되었습니다.", done));
        w.setPhase(InquiryWorkItemPhase.COMPLETED);
        workItems.saveAndFlush(w);
        assertThat(answered(w).toldBehind()).isFalse();

        Outrun o = outrun();
        UUID id = o.item().getId();
        checks.record(org, id, "CANNOT", "카드사 사정으로 취소 불가", "SELLER:qa", o.now(), null);
        InquiryWorkItem sent = workItems.findById(id).orElseThrow();
        sent.setPhase(InquiryWorkItemPhase.COMPLETED);
        workItems.saveAndFlush(sent);
        assertThat(answered(sent).toldBehind()).isFalse();
        assertThat(service.toldBehind(org)).isEmpty();
    }
}
