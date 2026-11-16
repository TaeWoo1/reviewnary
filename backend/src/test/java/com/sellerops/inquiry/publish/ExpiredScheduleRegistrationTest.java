package com.sellerops.inquiry.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.community.Cafe24CommunityArticleRepository;
import com.sellerops.identity.ExecutableIdentityResolver;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.inquiry.draft.InquiryDraftEvidenceRepository;
import com.sellerops.inquiry.draft.InquiryDraftRecheck;
import com.sellerops.inquiry.publish.dto.PublishStatusView;
import com.sellerops.inquiry.reply.EsmAnswerValidation;
import com.sellerops.inquiry.reply.InquiryReplyDraft;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.reply.ReplyDraftFingerprint;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeRequest;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.replyhold.ReplyDraftHold;
import com.sellerops.replyhold.ReplyHoldService;
import com.sellerops.reviewimport.ReviewImportPlanRepository;
import com.sellerops.reviewimport.ReviewImportSegmentAttemptRepository;
import com.sellerops.reviewimport.ReviewImportSegmentRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.sync.SyncJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * <b>An answer approved while its schedule was ahead is not sent after the date passed</b> (2026-11-16 B180 · §7.59).
 * Build j added the expiry as {@code SOURCE_CHANGED}, which the send of an approved answer drops — so on the real path
 * nothing stopped it. The whole path here is the production one in one Spring context: the seller's confirm through
 * {@link InquiryPublishService#confirmAndPublish} (its approval check), a dispatch the channel could not take yet, the
 * date passing, and {@link InquiryPublishService#resume} — the registration check, the hold row, the seller's
 * confirmation. Only the channel is a fake adapter; the knowledge is written through the seller's own write paths.
 *
 * <p>Each expiry case has a control that differs in the date alone and is sent, so what stops the expired one is the
 * expiry — and the hold says so with exactly one code. A wording change of the cited document alone still does not stop
 * an approved answer (the contract since 2026-11-01).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:expired_schedule_registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "sellerops.seed.enabled=false",
        "sellerops.seed.channel-catalogue=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpiredScheduleRegistrationTest {

    static final String CH_CODE = "COUPANG";
    static final java.util.concurrent.atomic.AtomicLong COUNTER = new java.util.concurrent.atomic.AtomicLong();
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String TITLE = "재입고 안내";
    /** No figure, no promise: the only thing the answer stands on is the cited schedule. */
    static final String REPLY = "문의하신 그레이 색상은 안내된 일정대로 재입고 예정이며, 일정은 바뀔 수 있습니다.";

    @Autowired OrganizationRepository organizations;
    @Autowired ChannelRepository channels;
    @Autowired SellerAccountRepository sellerAccounts;
    @Autowired InquiryRepository inquiries;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquiryReplyDraftRepository drafts;
    @Autowired InquiryDraftEvidenceRepository evidence;
    @Autowired InquiryApprovalRepository approvals;
    @Autowired InquiryActionIntentRepository intents;
    @Autowired InquiryExecutionRepository executions;
    @Autowired InquiryVerificationRepository verifications;
    @Autowired InquiryWorkItemAuditRepository audits;
    @Autowired PlatformTransactionManager txManager;
    @Autowired Cafe24CommunityArticleRepository articles;
    @Autowired SyncJobRepository syncJobs;
    @Autowired ReviewImportSegmentAttemptRepository attempts;
    @Autowired ReviewImportSegmentRepository segments;
    @Autowired ReviewImportPlanRepository plans;
    @Autowired SellerOperationsKnowledgeService orgKnowledge;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired KnowledgeScheduleService scheduleService;
    @Autowired InquiryDraftRecheck recheck;
    @Autowired ReplyHoldService holds;
    @Autowired ObjectProvider<ReplyHoldService> holdProvider;

    private final UUID user = UUID.randomUUID();
    private final LocalDate today = LocalDate.now(KST);
    /** The schedule's date: ahead when the seller approves. */
    private final LocalDate due = today.plusDays(9);
    private UUID org;
    private UUID channelId;
    private Adapter adapter;
    private InquiryPublishService publish;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("일정 만료 등록 QA");
        org = organizations.save(o).getId();
        channelId = channels.findByCode(CH_CODE).orElseGet(() -> {
            Channel c = new Channel();
            c.setCode(CH_CODE);
            c.setNameKo("테스트 채널");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSupportsInquiry(true);
            c.setSortOrder(0);
            return channels.save(c);
        }).getId();
        adapter = new Adapter();
        InquiryPublishBindingWriter writer =
                new InquiryPublishBindingWriter(workItems, approvals, intents, executions, audits, txManager);
        publish = new InquiryPublishService(workItems, drafts, inquiries, approvals, executions, verifications, audits,
                writer, new ChannelReplyAdapterRegistry(channels, List.of(adapter)), provenTarget(),
                new InquiryReplyCapabilityRegistry(), channels,
                new ExecutableIdentityResolver(sellerAccounts, channels, articles, syncJobs, attempts, segments, plans));
        publish.setReplyHolds(holdProvider);
        dayIs(today);
    }

    @AfterEach
    void systemClock() {
        recheck.setClock(null);
    }

    private void dayIs(LocalDate day) {
        recheck.setClock(Clock.fixed(day.atTime(12, 0).atZone(KST).toInstant(), KST));
    }

    // ── the cited schedules, written as the seller writes them ─────────────────────────────────────────────────────

    private static String table(LocalDate date) {
        return "[합성] 입고 일정\n\n| 상품 | 옵션 | 상태 | 날짜 | 구분 |\n|---|---|---|---|---|\n"
                + "| 면 침구 세트(퀸) | 그레이 | 재입고 대기 | " + date + " 입고 | 예정 |\n";
    }

    private InquiryDraftEvidence citedTable(LocalDate date) {
        UUID id = orgKnowledge.create(org, new OrgKnowledgeRequest(OrgKnowledgeType.OTHER, "입고 일정", table(date),
                null, List.of()), user, "QA").id();
        InquiryDraftEvidence row = new InquiryDraftEvidence();
        row.setKind(InquiryDraftEvidence.KIND_ORG_POLICY);
        row.setSourceId(id);
        row.setChunkId(orgChunks.findAllBySourceId(id).get(0).getId());
        row.setTitle("입고 일정");
        return row;
    }

    private InquiryDraftEvidence citedLine(LocalDate date) {
        KnowledgeSchedule line = scheduleService.save(org, null, null, "그레이", KnowledgeSchedule.Kind.INBOUND,
                KnowledgeSchedule.Status.PLANNED, date, null, today, true, "QA");
        InquiryDraftEvidence row = new InquiryDraftEvidence();
        row.setKind(InquiryDraftEvidence.KIND_SCHEDULE);
        row.setSourceId(line.getId());
        row.setLocator(KnowledgeScheduleService.fingerprint(line));
        row.setTitle("입고·출고 일정 · 그레이");
        return row;
    }

    /** A work item waiting on a model draft that cites {@code cited}, for a question about when stock comes back. */
    private InquiryWorkItem drafted(InquiryDraftEvidence cited) {
        SellerAccount acc = new SellerAccount();
        acc.setOrgId(org);
        acc.setChannelId(channelId);
        acc.setConnectionStatus(ChannelStatus.CONNECTED);
        acc.setFileUpload(false);
        UUID accountId = sellerAccounts.save(acc).getId();
        Inquiry q = new Inquiry();
        q.setOrgId(org);
        q.setChannelId(channelId);
        q.setSellerAccountId(accountId);
        q.setTitle("재입고 문의");
        q.setBody("그레이 재입고 언제 되나요?");
        q.setStatus("UNANSWERED");
        q.setInformStatus("미처리");
        q.setExternalId("onlineInquiry:" + (100000 + COUNTER.incrementAndGet()));
        q.setReceivedAt(Instant.now());
        UUID inquiryId = inquiries.save(q).getId();
        InquiryWorkItem wi = new InquiryWorkItem();
        wi.setOrgId(org);
        wi.setInquiryId(inquiryId);
        wi.setSellerAccountId(accountId);
        wi.setChannelId(channelId);
        wi.setPhase(InquiryWorkItemPhase.PROPOSED);
        wi = workItems.save(wi);
        InquiryReplyDraft d = new InquiryReplyDraft();
        d.setOrgId(org);
        d.setWorkItemId(wi.getId());
        d.setVersion(1);
        d.setAnswerStatus(2);
        d.setTitle(TITLE);
        d.setComments(REPLY);
        d.setContentFingerprint(ReplyDraftFingerprint.of(TITLE, REPLY));
        d.setFingerprintAlgorithm(EsmAnswerValidation.FINGERPRINT_ALGORITHM);
        d.setAuthorKind("MODEL");
        d.setCreatedBy("MODEL");
        drafts.save(d);
        cited.setOrgId(org);
        cited.setWorkItemId(wi.getId());
        cited.setDraftVersion(1);
        cited.setOrdinal(1);
        evidence.save(cited);
        return wi;
    }

    /**
     * The seller confirms while the date is ahead — the approval check passes and binds — and the channel cannot take
     * it yet, so it waits, approved, for the next dispatch.
     */
    private InquiryWorkItem approvedAndWaiting(InquiryDraftEvidence cited) {
        InquiryWorkItem wi = drafted(cited);
        adapter.next = ReplyPublishResult.retryableFailure();
        PublishStatusView v = publish.confirmAndPublish(org, wi.getId(), user, "cmd-" + wi.getId(),
                ReplyDraftFingerprint.of(TITLE, REPLY));
        assertThat(v.category()).as(() -> "failure " + executions.findByWorkItemId(wi.getId())
                .map(InquiryExecution::getFailureReason).orElse(null)).isEqualTo(PublishOutcomeCategory.RETRYABLE_FAILURE);
        assertThat(workItems.findById(wi.getId()).orElseThrow().getPhase()).isEqualTo(InquiryWorkItemPhase.ACTION_PENDING);
        assertThat(approvals.findByWorkItemId(wi.getId())).isPresent();
        adapter.next = ReplyPublishResult.confirmed("PROV-1");
        adapter.published.clear();
        return wi;
    }

    private void sent(InquiryWorkItem wi) {
        publish.resume(org, wi.getId());
        assertThat(adapter.published).as("registered").hasSize(1);
        assertThat(executions.findByWorkItemId(wi.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InquiryExecutionStatus.ACTION_PENDING);
        assertThat(holds.holdFor(org, ReplyDraftHold.Lane.INQUIRY, wi.getId())).as("nothing held").isNull();
    }

    private ReplyHoldService.HoldView stopped(InquiryWorkItem wi) {
        assertThatThrownBy(() -> publish.resume(org, wi.getId())).isInstanceOf(ApiException.class)
                .hasMessageContaining("날짜가 모두 지났어요");
        assertThat(adapter.published).as("not registered").isEmpty();
        assertThat(executions.findByWorkItemId(wi.getId()).orElseThrow().getStatus())
                .isEqualTo(InquiryExecutionStatus.ACTION_PENDING);
        ReplyHoldService.HoldView hold = holds.holdFor(org, ReplyDraftHold.Lane.INQUIRY, wi.getId());
        assertThat(hold).isNotNull();
        assertThat(hold.approved()).isTrue();
        assertThat(hold.codes()).as("the expiry alone — no other reason stopped it")
                .containsExactly(InquiryDraftRecheck.SCHEDULE_EXPIRED);
        assertThat(hold.needs()).isEqualTo("CONFIRM");
        return hold;
    }

    // ── a document table ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("document table: approved while ahead, sent while ahead (control) · not sent once its date passed")
    void aTableThatExpiredAfterApproval() {
        InquiryWorkItem control = approvedAndWaiting(citedTable(due));
        sent(control);

        InquiryWorkItem wi = approvedAndWaiting(citedTable(due));
        dayIs(due.plusDays(1));
        ReplyHoldService.HoldView hold = stopped(wi);
        assertThat(hold.headline()).contains("입고 일정").contains("날짜가 모두 지나");
        assertThat(hold.reasons()).anyMatch(r -> r.contains("「입고 일정」") && r.contains("날짜가 모두 지났어요"));
    }

    @Test
    @DisplayName("document table: the seller's explicit 「확인하고 진행」 on the expiry lets that answer go, recorded")
    void theSellerMayConfirmPastIt() {
        InquiryWorkItem wi = approvedAndWaiting(citedTable(due));
        dayIs(due.plusDays(1));
        ReplyHoldService.HoldView hold = stopped(wi);
        holds.confirm(org, ReplyDraftHold.Lane.INQUIRY, wi.getId(), 1, hold.checkId(), user);
        publish.resume(org, wi.getId());
        assertThat(adapter.published).hasSize(1);
    }

    @Test
    @DisplayName("kept: a wording change of the cited document alone does not stop an approved answer")
    void aWordingChangeAloneStillGoes() {
        InquiryDraftEvidence cited = citedTable(due);
        InquiryWorkItem wi = approvedAndWaiting(cited);
        orgKnowledge.update(org, cited.getSourceId(), new OrgKnowledgeRequest(OrgKnowledgeType.OTHER, "입고 일정",
                table(due).replace("재입고 대기", "재입고 대기(공장 출고 확인 중)"), null, List.of()));
        assertThat(recheck.check(wi, 1, REPLY, "MODEL").codes()).as("it is a changed citation, nothing else")
                .containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.SOURCE_CHANGED);
        sent(wi);
    }

    // ── a structured schedule line ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("structured line: approved while ahead, sent while ahead (control) · not sent once its date passed")
    void aLineThatExpiredAfterApproval() {
        InquiryWorkItem control = approvedAndWaiting(citedLine(due));
        sent(control);

        InquiryWorkItem wi = approvedAndWaiting(citedLine(due));
        dayIs(due.plusDays(1));
        ReplyHoldService.HoldView hold = stopped(wi);
        assertThat(hold.reasons()).anyMatch(r -> r.contains("「입고·출고 일정 · 그레이」"));
    }

    @Test
    @DisplayName("on the date itself the line still stands — only a date before today is history")
    void onTheDayItStands() {
        InquiryWorkItem wi = approvedAndWaiting(citedLine(due));
        dayIs(due);
        sent(wi);
    }

    @Test
    @DisplayName("an answer whose schedule passed before the confirm is refused at the approval, with the same reason")
    void expiredBeforeTheConfirm() {
        InquiryWorkItem wi = drafted(citedLine(due));
        dayIs(due.plusDays(1));
        assertThatThrownBy(() -> publish.confirmAndPublish(org, wi.getId(), user, "cmd-" + wi.getId(),
                ReplyDraftFingerprint.of(TITLE, REPLY))).isInstanceOf(ApiException.class)
                .hasMessageContaining("날짜가 모두 지났어요");
        assertThat(approvals.findByWorkItemId(wi.getId())).isEmpty();
        assertThat(adapter.published).isEmpty();
    }

    // ── the channel ────────────────────────────────────────────────────────────────────────────────────────────────

    static final class Adapter implements ChannelReplyAdapter {
        ReplyPublishResult next = ReplyPublishResult.confirmed("PROV-1");
        final List<ReplyPublishCommand> published = new ArrayList<>();

        @Override
        public String channelCode() {
            return CH_CODE;
        }

        @Override
        public ReplyPublishResult publish(ReplyPublishCommand command) {
            published.add(command);
            return next;
        }

        @Override
        public ReplyVerificationResult verify(ReplyVerificationCommand command) {
            return ReplyVerificationResult.completed("DONE");
        }
    }

    private static InquiryTargetStateReader provenTarget() {
        return new InquiryTargetStateReader(null, null) {
            @Override
            public PreSendCheck read(UUID orgId, UUID channelId) {
                return PreSendCheck.proven();
            }
        };
    }
}
