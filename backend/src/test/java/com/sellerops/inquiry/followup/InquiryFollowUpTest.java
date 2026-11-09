package com.sellerops.inquiry.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheck;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckService;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Answering the customer and finishing the action are two facts (2026-11-03 sixth review). Real rows for every foreign
 * key, so the class runs on PostgreSQL as it does on H2.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class InquiryFollowUpTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    @Autowired OrganizationRepository organizations;
    @Autowired ChannelRepository channels;
    @Autowired SellerAccountRepository sellerAccounts;
    @Autowired InquiryRepository inquiries;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquirySellerCheckRepository checkRows;
    @Autowired InquiryReplyDraftRepository drafts;
    @Autowired InquiryWorkItemAuditRepository audits;
    @Autowired com.sellerops.product.ProductRepository products;

    private InquirySellerCheckService checks;
    private InquiryFollowUpService followUps;
    private UUID org;
    private UUID otherOrg;
    private UUID channelId;
    private final java.util.Map<UUID, UUID> accountOf = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        checks = new InquirySellerCheckService(checkRows, workItems);
        checks.useClock(Clock.fixed(TODAY.atStartOfDay(InquirySellerCheckService.ZONE).plusHours(9).toInstant(),
                InquirySellerCheckService.ZONE));
        followUps = new InquiryFollowUpService(workItems, checkRows, checks, inquiries, channels);
        org = newOrg("후속 확인 QA");
        otherOrg = newOrg("다른 가게");
        channelId = channels.findByCode("FOLLOW-UP-QA").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("FOLLOW-UP-QA");
            c.setNameKo("후속 확인 QA");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSortOrder(0);
            return channels.save(c);
        }).getId();
    }

    private UUID newOrg(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    private UUID account(UUID orgId) {
        return accountOf.computeIfAbsent(orgId, id -> {
            SellerAccount a = new SellerAccount();
            a.setOrgId(id);
            a.setChannelId(channelId);
            a.setConnectionStatus(ChannelStatus.PENDING);
            a.setFileUpload(false);
            return sellerAccounts.save(a).getId();
        });
    }

    private InquiryWorkItem item(UUID orgId, InquiryWorkItemPhase phase, String title) {
        Inquiry q = new Inquiry();
        q.setOrgId(orgId);
        q.setChannelId(channelId);
        q.setTitle(title);
        q.setBody("합성 문의");
        q.setStatus("UNANSWERED");
        q.setReceivedAt(Instant.parse("2026-11-01T00:00:00Z"));
        UUID inquiryId = inquiries.save(q).getId();
        InquiryWorkItem w = new InquiryWorkItem();
        w.setOrgId(orgId);
        w.setInquiryId(inquiryId);
        w.setSellerAccountId(account(orgId));
        w.setChannelId(channelId);
        w.setPhase(phase);
        return workItems.saveAndFlush(w);
    }

    private InquiryWorkItem item(InquiryWorkItemPhase phase, String title) {
        return item(org, phase, title);
    }

    /** Records while the reply is still open, then moves the phase on — the order it happens in the product. */
    private InquiryWorkItem recorded(InquiryWorkItemPhase phase, String title, String outcome, LocalDate next) {
        InquiryWorkItem w = item(InquiryWorkItemPhase.PROPOSED, title);
        checks.record(org, w.getId(), outcome, "합성 기록", "SELLER:qa", next);
        w.setPhase(phase);
        return workItems.saveAndFlush(w);
    }

    private static List<String> titles(List<InquiryFollowUpService.FollowUpRow> rows) {
        return rows.stream().map(InquiryFollowUpService.FollowUpRow::subject).toList();
    }

    @Test
    @DisplayName("each thing still open after a reply waits in its own place; finished, unanswered and other stores' items "
            + "are not follow-ups")
    void eachOpenThingWaitsInItsOwnPlace() {
        item(InquiryWorkItemPhase.ACTION_PENDING, "등록 확인 전");
        recorded(InquiryWorkItemPhase.EXECUTED, "등록 확인 전 · 진행 중", "IN_PROGRESS", TODAY.plusDays(4));
        recorded(InquiryWorkItemPhase.COMPLETED, "진행 중", "IN_PROGRESS", TODAY.plusDays(2));
        recorded(InquiryWorkItemPhase.COMPLETED, "고객 회신 대기", "NEEDS_CUSTOMER_INFO", null);
        recorded(InquiryWorkItemPhase.COMPLETED, "처리 완료", "DONE", null);
        recorded(InquiryWorkItemPhase.COMPLETED, "처리 불가", "CANNOT", null);
        recorded(InquiryWorkItemPhase.PROPOSED, "아직 답변 전", "IN_PROGRESS", TODAY.plusDays(1));
        item(InquiryWorkItemPhase.COMPLETED, "기록 없이 등록 확인");
        item(InquiryWorkItemPhase.REJECTED, "거절");
        item(otherOrg, InquiryWorkItemPhase.ACTION_PENDING, "다른 가게");
        InquiryWorkItem seeded = item(InquiryWorkItemPhase.ACTION_PENDING, "견본 데이터");
        Inquiry sample = inquiries.findById(seeded.getInquiryId()).orElseThrow();
        sample.setDataOrigin(com.sellerops.common.DataOrigin.DEMO_SEED);
        inquiries.saveAndFlush(sample);

        InquiryFollowUpService.FollowUps read = followUps.followUps(org, TODAY);

        assertThat(read.due()).isEmpty();
        assertThat(titles(read.waiting())).containsExactly("등록 확인 전 · 진행 중", "등록 확인 전", "진행 중", "고객 회신 대기");
        assertThat(read.waiting()).extracting(InquiryFollowUpService.FollowUpRow::waitingOn)
                .containsExactly("REGISTRATION", "REGISTRATION", "ACTION", "CUSTOMER");
        InquiryFollowUpService.FollowUpRow registering = read.waiting().get(0);
        assertThat(registering.outcome()).as("the record is carried while registration is checked").isEqualTo("IN_PROGRESS");
        assertThat(registering.nextCheckOn()).isEqualTo(TODAY.plusDays(4));
        assertThat(registering.to()).as("the same inquiry opens again").isEqualTo("/inquiries/" + registering.inquiryId());
        assertThat(read.waitingTotal()).isEqualTo(4);
    }

    @Test
    @DisplayName("the rule on its own: a registered reply with a finished record, or none, is finished — whichever read "
            + "brings it")
    void theRuleOnItsOwn() {
        InquirySellerCheck done = new InquirySellerCheck();
        done.setOutcome("DONE");
        InquirySellerCheck cannot = new InquirySellerCheck();
        cannot.setOutcome("CANNOT");
        InquirySellerCheck later = new InquirySellerCheck();
        later.setOutcome("IN_PROGRESS");
        later.setNextCheckOn(TODAY.plusDays(1));
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.COMPLETED, done, TODAY)).isEmpty();
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.COMPLETED, cannot, TODAY)).isEmpty();
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.COMPLETED, null, TODAY)).isEmpty();
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.PROPOSED, later, TODAY)).isEmpty();
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.FAILED, later, TODAY)).isEmpty();
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.COMPLETED, later, TODAY).orElseThrow())
                .isEqualTo(new InquiryFollowUpService.Open("ACTION", false));
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.COMPLETED, later, TODAY.plusDays(1)).orElseThrow())
                .isEqualTo(new InquiryFollowUpService.Open("ACTION", true));
        InquirySellerCheck undated = new InquirySellerCheck();
        undated.setOutcome("IN_PROGRESS");
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.COMPLETED, undated, TODAY).orElseThrow())
                .as("no day is a check now, not a wait nothing brings back")
                .isEqualTo(new InquiryFollowUpService.Open("ACTION", true));
        assertThat(InquiryFollowUpService.classify(InquiryWorkItemPhase.ACTION_PENDING, done, TODAY).orElseThrow())
                .isEqualTo(new InquiryFollowUpService.Open("REGISTRATION", false));
    }

    @Test
    @DisplayName("the queue says a seller check is still asked only while nothing is recorded — the Home's 「이 내용만 "
            + "알려주세요」")
    void theQueueSaysWhatIsStillAsked() {
        InquiryWorkItem asked = item(InquiryWorkItemPhase.PROPOSED, "확인 요청");
        asked.setSellerCheckAsk("ORDER_ACTION");
        workItems.saveAndFlush(asked);
        InquiryWorkItem answered = item(InquiryWorkItemPhase.PROPOSED, "기록함");
        answered.setSellerCheckAsk("ORDER_ACTION");
        workItems.saveAndFlush(answered);
        checks.record(org, answered.getId(), "DONE", "환불 완료", "SELLER:qa");
        item(InquiryWorkItemPhase.PROPOSED, "묻지 않음");

        com.sellerops.inquiry.queue.InquiryQueueService queue =
                new com.sellerops.inquiry.queue.InquiryQueueService(workItems, inquiries, channels, products);
        queue.setSellerChecks(checkRows);
        java.util.Map<String, String> askOf = new java.util.HashMap<>();
        queue.queue(org, InquiryWorkItemPhase.AWAITING_SELLER, 0, 20).content()
                .forEach(r -> askOf.put(r.title(), r.sellerCheckAsk()));
        assertThat(askOf).containsEntry("확인 요청", "ORDER_ACTION").containsEntry("기록함", null)
                .containsEntry("묻지 않음", null).hasSize(3);
    }

    @Test
    @DisplayName("a decided inquiry still opens on its work item — carried apart from the draftable one")
    void aDecidedInquiryOpensAgain() {
        InquiryWorkItem open = item(InquiryWorkItemPhase.PROPOSED, "답변 전");
        InquiryWorkItem registered = recorded(InquiryWorkItemPhase.COMPLETED, "등록 확인됨", "IN_PROGRESS", null);
        InquiryWorkItem registering = item(InquiryWorkItemPhase.ACTION_PENDING, "등록 중");
        InquiryWorkItem dismissed = item(InquiryWorkItemPhase.DISMISSED, "정리함");

        com.sellerops.inquiry.queue.InquiryRowsService rows = new com.sellerops.inquiry.queue.InquiryRowsService(inquiries,
                workItems, channels, products, com.sellerops.identity.ExecutableIdentityResolver.unresolved());
        java.util.Map<String, com.sellerops.inquiry.queue.dto.InquiryRowItem> byTitle = new java.util.HashMap<>();
        rows.rows(org, null, null, null, null, null, 50).items().forEach(r -> byTitle.put(r.title(), r));

        assertThat(byTitle.get("답변 전").workItemId()).isEqualTo(open.getId());
        assertThat(byTitle.get("답변 전").decidedWorkItemId()).isNull();
        assertThat(byTitle.get("등록 확인됨").workItemId()).as("not draftable").isNull();
        assertThat(byTitle.get("등록 확인됨").decidedWorkItemId()).isEqualTo(registered.getId());
        assertThat(byTitle.get("등록 중").decidedWorkItemId()).isEqualTo(registering.getId());
        assertThat(byTitle.get("정리함").workItemId()).isNull();
        assertThat(byTitle.get("정리함").decidedWorkItemId()).as("set aside, never decided").isNull();
        assertThat(dismissed.getId()).isNotNull();
    }

    @Test
    @DisplayName("the next-check day hands a check back to the seller and writes nothing — no draft, no record, no phase")
    void theDayComesBackAsACheckNotASend() {
        InquiryWorkItem w = recorded(InquiryWorkItemPhase.COMPLETED, "환불 진행 중", "IN_PROGRESS", TODAY.plusDays(2));
        long draftsBefore = drafts.count();
        long checksBefore = checkRows.count();
        long auditsBefore = audits.count();

        assertThat(followUps.followUps(org, TODAY.plusDays(1)).due()).isEmpty();
        InquiryFollowUpService.FollowUps onTheDay = followUps.followUps(org, TODAY.plusDays(2));
        assertThat(titles(onTheDay.due())).containsExactly("환불 진행 중");
        assertThat(onTheDay.waiting()).as("due is not also waiting").isEmpty();
        assertThat(titles(followUps.followUps(org, TODAY.plusDays(9)).due())).as("still due after the day")
                .containsExactly("환불 진행 중");

        assertThat(drafts.count()).isEqualTo(draftsBefore);
        assertThat(checkRows.count()).isEqualTo(checksBefore);
        assertThat(audits.count()).isEqualTo(auditsBefore);
        assertThat(workItems.findById(w.getId()).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.COMPLETED);
    }

    @Test
    @DisplayName("an action in progress always has a day to look again — two days later until the seller picks one, never "
            + "before today — and that day is never part of what a reply is written from")
    void anActionInProgressSaysWhenToLookAgain() {
        InquiryWorkItem w = item(InquiryWorkItemPhase.PROPOSED, "날짜");
        assertThatThrownBy(() -> checks.record(org, w.getId(), "IN_PROGRESS", "환불 대기", "SELLER:qa", null,
                TODAY.minusDays(1))).isInstanceOf(ApiException.class).hasMessageContaining("오늘이나 그 뒤로");
        assertThat(checkRows.count()).as("a refused press writes nothing").isZero();

        InquirySellerCheckService.SellerCheckView first = checks.record(org, w.getId(), "IN_PROGRESS", "환불 대기",
                "SELLER:qa", null, null);
        assertThat(first.nextCheckOn()).as("not left empty").isEqualTo(TODAY.plusDays(2));
        InquirySellerCheck stored = checkRows.findById(first.id()).orElseThrow();
        assertThat(InquirySellerCheckService.evidenceText(stored)).doesNotContain(TODAY.plusDays(2).toString())
                .doesNotContain("11월").doesNotContain("다시 확인");

        InquirySellerCheckService.SellerCheckView done = checks.record(org, w.getId(), "DONE", "환불 완료", "SELLER:qa",
                first.id(), TODAY.plusDays(3));
        assertThat(done.nextCheckOn()).as("nothing is left to check on a finished action").isNull();
    }

    @Test
    @DisplayName("「이 날로 정하기」 moves the day on the same record — no new record, so the reply written from it still "
            + "stands — and only on the record shown, only while in progress, never before today")
    void movingTheDayIsNotANewRecord() {
        InquiryWorkItem w = recorded(InquiryWorkItemPhase.COMPLETED, "환불 진행 중", "IN_PROGRESS", null);
        UUID standing = checks.current(org, w.getId()).orElseThrow().getId();

        InquirySellerCheckService.SellerCheckView moved = checks.setNextCheck(org, w.getId(), standing, TODAY.plusDays(6));
        assertThat(moved.id()).isEqualTo(standing);
        assertThat(moved.nextCheckOn()).isEqualTo(TODAY.plusDays(6));
        assertThat(checks.history(org, w.getId())).hasSize(1);
        assertThat(followUps.followUps(org, TODAY.plusDays(5)).due()).isEmpty();
        assertThat(titles(followUps.followUps(org, TODAY.plusDays(6)).due())).containsExactly("환불 진행 중");

        assertThatThrownBy(() -> checks.setNextCheck(org, w.getId(), UUID.randomUUID(), TODAY.plusDays(1)))
                .as("another record than the one shown").isInstanceOf(ApiException.class).hasMessageContaining("먼저 저장");
        assertThatThrownBy(() -> checks.setNextCheck(org, w.getId(), standing, TODAY.minusDays(1)))
                .isInstanceOf(ApiException.class).hasMessageContaining("오늘이나 그 뒤로");
        assertThatThrownBy(() -> checks.setNextCheck(otherOrg, w.getId(), standing, TODAY.plusDays(1)))
                .as("another store").isInstanceOf(ApiException.class);
        UUID done = checks.record(org, w.getId(), "DONE", "환불 완료", "SELLER:qa", standing, null).id();
        assertThatThrownBy(() -> checks.setNextCheck(org, w.getId(), done, TODAY.plusDays(1)))
                .as("a finished action has nothing to check").isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("correcting the record after the reply closes the wait, and the earlier record is kept")
    void aCorrectionClosesTheWait() {
        InquiryWorkItem w = recorded(InquiryWorkItemPhase.COMPLETED, "환불 진행 중", "IN_PROGRESS", TODAY);
        assertThat(titles(followUps.followUps(org, TODAY).due())).containsExactly("환불 진행 중");
        UUID standing = checks.current(org, w.getId()).orElseThrow().getId();

        checks.record(org, w.getId(), "IN_PROGRESS", "환불 승인 대기", "SELLER:qa", standing, TODAY.plusDays(3));
        InquiryFollowUpService.FollowUps pushed = followUps.followUps(org, TODAY);
        assertThat(pushed.due()).as("a new day moves it back to waiting").isEmpty();
        assertThat(pushed.waiting()).extracting(InquiryFollowUpService.FollowUpRow::nextCheckOn)
                .containsExactly(TODAY.plusDays(3));

        checks.record(org, w.getId(), "DONE", "환불 완료", "SELLER:qa", checks.current(org, w.getId()).orElseThrow().getId(),
                null);
        InquiryFollowUpService.FollowUps closed = followUps.followUps(org, TODAY);
        assertThat(closed.due()).isEmpty();
        assertThat(closed.waiting()).isEmpty();
        assertThat(checks.history(org, w.getId())).hasSize(3);
    }

    /**
     * Tenth review (2026-11-05): a registered answer that told the customer less than the record now says is a check
     * today — 「처리는 완료됐지만 고객에게는 진행 중으로 안내했어요」 — in place of whatever else it waited on, with the record
     * it was found on so the screen closes it for that one. Which answers are behind is the answer service's rule
     * ({@code InquiryApprovalWithdrawalTest}); this is where the Home reads it.
     */
    @Test
    @DisplayName("an answer behind the record is a check today, once, with the record it was found on")
    void anAnswerBehindTheRecordIsACheckToday() {
        InquiryWorkItem behind = recorded(InquiryWorkItemPhase.EXECUTED, "완료 · 진행 중으로 안내", "DONE", null);
        recorded(InquiryWorkItemPhase.COMPLETED, "진행 중", "IN_PROGRESS", TODAY.plusDays(2));
        UUID checkId = checkRows.findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByRecordedAtDesc(org,
                behind.getId()).orElseThrow().getId();
        com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService answers =
                mock(com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService.class);
        when(answers.toldBehind(org)).thenReturn(List.of(
                new com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService.ToldBehind(behind.getId(),
                        behind.getInquiryId(), checkId, "IN_PROGRESS", "접수·진행 중", "환불 완료", Instant.now())));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.sellerops.inquiry.publish.InquiryApprovalWithdrawalService>
                provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answers);
        followUps.setAnswers(provider);

        InquiryFollowUpService.FollowUps now = followUps.followUps(org, TODAY);

        assertThat(now.due()).filteredOn(r -> r.workItemId().equals(behind.getId())).singleElement().satisfies(r -> {
            assertThat(r.waitingOn()).isEqualTo(InquiryFollowUpService.TOLD_BEHIND);
            assertThat(r.due()).isTrue();
            assertThat(r.outcome()).isEqualTo("DONE");
            assertThat(r.toldOutcome()).isEqualTo("IN_PROGRESS");
            assertThat(r.checkId()).isEqualTo(checkId);
        });
        assertThat(now.waiting()).as("not also waiting on its registration")
                .noneMatch(r -> r.workItemId().equals(behind.getId()));
        assertThat(titles(now.waiting())).contains("진행 중");
    }
}
