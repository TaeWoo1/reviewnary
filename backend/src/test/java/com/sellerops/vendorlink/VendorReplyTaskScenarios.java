package com.sellerops.vendorlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.attention.VocItemRef;
import com.sellerops.attention.reply.OperatorOutcome;
import com.sellerops.attention.reply.ReviewReplyApprovalService;
import com.sellerops.attention.reply.ReviewReplyDraftService;
import com.sellerops.attention.reply.ReviewReplyFingerprint;
import com.sellerops.attention.reply.ReviewReplyOutcome;
import com.sellerops.attention.reply.ReviewReplyOutcomeRepository;
import com.sellerops.attention.reply.ReviewReplyOutcomeService;
import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.attention.reply.dto.ReviewReplyDelegationView;
import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.attention.triage.ReviewTriageService;
import com.sellerops.attention.triage.ReviewTriageWriter;
import com.sellerops.attention.triage.TriageDisposition;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.credential.LocalSecretSealer;
import com.sellerops.credential.VaultKeyRing;
import com.sellerops.ingest.IngestFollowUp;
import com.sellerops.ingest.IngestionService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.ReviewRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.sync.SyncJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A reply goes to the phone only when the seller sends it, travels one road, and comes back as a reported, unverified
 * post</b> — contract {@code docs/vendor_server_contract_v1.md} §4 and the owner's decisions of 2026-10-21.
 *
 * <p>The PC's own reply lane is the container's ({@link ReviewReplyService}, wired with the vendor lane as its
 * {@code DelegatedReplyLane}); the vendor server is {@link FakeVendorServer}. Every review and reply is synthetic.
 * The database is the subclass's: H2 ({@link VendorReplyTaskServiceTest}) or Flyway-migrated PostgreSQL
 * ({@link VendorReplyTaskPostgresProofIT}).
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class VendorReplyTaskScenarios {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32});
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);

    @Autowired VendorInstallLinkRepository links;
    @Autowired VendorStoreBindingRepository bindings;
    @Autowired VendorReviewRefRepository refs;
    @Autowired VendorReplyTaskRepository taskCopies;
    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired SellerAccountRepository accounts;
    @Autowired IngestionService ingestion;
    @Autowired IngestFollowUp followUp;
    @Autowired SyncJobRepository syncJobs;
    @Autowired ReviewReplyDraftService drafts;
    @Autowired ReviewReplyApprovalService approvals;
    @Autowired ReviewReplyOutcomeService outcomes;
    @Autowired ReviewReplyOutcomeRepository outcomeRows;
    @Autowired ReviewReplyService replyLane;
    @Autowired com.sellerops.review.memory.ReviewAnswerMemoryHook answerMemoryHook;
    @Autowired com.sellerops.knowledge.memory.AnswerMemoryRepository memories;
    @Autowired com.sellerops.knowledge.spine.adapter.ReviewReplyAdapter pastReplies;
    @Autowired com.sellerops.knowledge.spine.KnowledgeSpineService spine;
    @Autowired ReviewTriageService triage;
    @Autowired ReviewTriageWriter triageWriter;
    @Autowired ReviewTriageRepository triageRows;
    @Autowired com.sellerops.review.draft.ReviewDraftBasis draftBasis;
    @Autowired com.sellerops.review.draft.ReviewDraftRecheck draftRecheck;
    @Autowired com.sellerops.knowledge.org.OrgKnowledgeChunkRepository companyRuleChunks;
    @Autowired com.sellerops.knowledge.style.AnswerStyleService answerStyles;
    @Autowired com.sellerops.review.draft.ReviewStyleGuard styleGuard;
    @Autowired com.sellerops.knowledge.org.SellerOperationsKnowledgeService companyRules;
    @Autowired com.sellerops.knowledge.org.OrgKnowledgeSourceRepository companyRuleRows;
    @Autowired com.sellerops.review.draft.ReviewDraftEvidenceRepository draftEvidence;
    @Autowired com.sellerops.attention.reply.ReviewReplyTemplateRepository templateRows;
    @Autowired com.sellerops.knowledge.memory.AnswerMemoryService answerMemory;

    private FakeVendorServer vendor;
    private VendorReplyTaskService tasks;
    private VendorReviewPullService pull;
    private UUID org;
    private UUID account;
    private UUID seller;

    /** 「답글 등록 요청」 on the version the seller has on screen — the head, as a fresh panel shows it. */
    private VendorReplyTaskService.TaskView handOver(UUID review) {
        return tasks.handOver(org, review, drafts.currentVersion(review));
    }

    @BeforeEach
    void setUp() {
        taskCopies.deleteAll();
        refs.deleteAll();
        bindings.deleteAll();
        links.deleteAll();
        Channel naver = channels.findByCode("NAVER").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("NAVER");
            c.setNameKo("네이버");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSupportsReview(true);
            c.setSortOrder(0);
            return channels.save(c);
        });
        Organization o = new Organization();
        o.setName("합성 판매자");
        org = organizations.save(o).getId();
        SellerAccount a = new SellerAccount();
        a.setOrgId(org);
        a.setChannelId(naver.getId());
        a.setConnectionStatus(ChannelStatus.PENDING);
        a.setFileUpload(false);
        account = accounts.save(a).getId();
        seller = UUID.randomUUID();

        vendor = new FakeVendorServer(UUID.randomUUID());
        VendorLinkService link = new VendorLinkService(links,
                new LocalSecretSealer(new VaultKeyRing(KEY, "test-key-1", "")), vendor, true, CLOCK);
        link.start();
        link.poll();
        tasks = new VendorReplyTaskService(link, bindings, refs, taskCopies, reviews, drafts, approvals, outcomes,
                triageRows, CLOCK);
        tasks.setAnswerMemory(answerMemoryHook);
        // A save's follow-up runs only where a test asks for it: the fake server is not shared across threads.
        tasks.setAfter(r -> {});
        replyLane.setDelegatedReplyLane(tasks);
        pull = new VendorReviewPullService(link, bindings, refs, reviews, channels, ingestion, followUp, syncJobs,
                tasks, CLOCK);
    }

    @Test
    void aDraftGoesUpForThePhoneWithoutHoldingTheReviewAndApprovingHereTakesItBack() {
        UUID review = pulledReview("v1", "9100000001", "배송이 빨라요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "빠른 배송으로 만족하셨다니 기쁩니다.", 0);

        VendorReplyTaskService.TaskView sent = tasks.upload(org, review);
        assertThat(sent.state()).isEqualTo("AWAITING_APPROVAL");
        assertThat(vendor.submitted).singleElement().satisfies(s -> {
            assertThat(s.reviewId()).isEqualTo(vendor.ids.get("v1"));
            assertThat(s.body()).isEqualTo("빠른 배송으로 만족하셨다니 기쁩니다.");
            assertThat(s.meta().reviewBodyFingerprint()).isEqualTo(refs.findByReviewId(review).orElseThrow()
                    .getBodyFingerprint());
            assertThat(List.of(s.meta().knowledgeFp(), s.meta().policyFp(), s.meta().voiceFp()))
                    .containsOnly(VendorReplyTaskService.UNVERSIONED);
        });

        // Waiting on the phone is not on its way (2026-10-25): no reply is out, so this PC's own road stays open…
        assertThat(tasks.holds(org, review)).isFalse();
        String ref = VocItemRef.forReview(review);
        assertThat(replyLane.view(org, account, ref).capabilities().canApprove()).isTrue();
        assertThat(replyLane.view(org, account, ref).delegation().canCancel()).as("nothing was requested").isFalse();
        // …and taking it means taking the draft back from the phone first, so the phone cannot approve a second one.
        replyLane.decideApproval(org, account, ref, "APPROVED", 1, "cmd-approve-1", seller);
        assertThat(vendor.tasks.get(vendorTaskOf(sent)).state()).isEqualTo("CANCELLED");
        assertThat(tasks.taskFor(org, review)).get().extracting(VendorReplyTaskService.TaskView::state)
                .isEqualTo("CANCELLED");
    }

    /* ── one draft, either device (owner decision 2026-10-25) ── */

    @Test
    void everyDraftForAReplyGoesUpOnThePullWithoutAPressAndOnlyThose() {
        UUID drafted = pulledReview("v1", "9100000101", "배송이 빨라요");
        needsReply(drafted);
        drafts.save(org, drafted, "SELLER:" + seller, "빠른 배송으로 만족하셨다니 기쁩니다.", 0);
        UUID undecided = pulledReview("v2", "9100000102", "그냥 그래요");
        drafts.save(org, undecided, "SELLER:" + seller, "의견 감사합니다.", 0);
        UUID noDraft = pulledReview("v3", "9100000103", "색이 예뻐요");
        needsReply(noDraft);

        pull.pullFor(org);

        assertThat(vendor.submitted).singleElement().satisfies(s -> {
            assertThat(s.reviewId()).isEqualTo(vendor.ids.get("v1"));
            assertThat(s.body()).isEqualTo("빠른 배송으로 만족하셨다니 기쁩니다.");
        });
        assertThat(vendor.approveCommands).as("up for the phone, never approved on its own").isEmpty();
        pull.pullFor(org);
        assertThat(vendor.submitted).as("the same text is not sent twice").hasSize(1);

        drafts.save(org, drafted, "SELLER:" + seller, "빠른 배송으로 만족하셨다니 정말 기쁩니다.", 1);
        pull.pullFor(org);
        UUID task = taskCopies.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(org, drafted).orElseThrow()
                .getVendorTaskId();
        assertThat(vendor.tasks.get(task)).satisfies(t -> {
            assertThat(t.draftVersion()).as("the edit here is the phone's next version").isEqualTo(2);
            assertThat(t.draftBody()).isEqualTo("빠른 배송으로 만족하셨다니 정말 기쁩니다.");
        });
    }

    @Test
    void aSaveHereGoesUpAtOnceAndARequestTheSellerCancelledIsNotSentAgainOnItsOwn() {
        tasks.setAfter(Runnable::run);
        UUID review = pulledReview("v1", "9100000104", "배송이 빨라요");
        needsReply(review);
        String ref = VocItemRef.forReview(review);
        replyLane.saveDraft(org, account, ref, "빠른 배송으로 만족하셨다니 기쁩니다.", 0, seller);
        assertThat(vendor.submitted).as("a save is followed up at once, not at the next pull").hasSize(1);

        UUID task = vendorTaskOf(handOver(review));
        tasks.cancel(org, review);
        assertThat(vendor.tasks.get(task).state()).isEqualTo("CANCELLED");
        int before = vendor.submitted.size();
        pull.pullFor(org);
        assertThat(vendor.submitted).as("the seller took it back; it stays back").hasSize(before);
    }

    @Test
    void anEditOnThePhoneBecomesThisPcsDraftInTheSellersName() {
        UUID review = pulledReview("v1", "9100000105", "포장이 꼼꼼해요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "꼼꼼한 포장을 알아봐 주셔서 감사합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        String phoneText = "꼼꼼한 포장을 알아봐 주셔서 감사합니다! 또 찾아 주세요.";
        vendor.move(task, "AWAITING_APPROVAL", phoneText, null, true);

        pull.pullFor(org);

        assertThat(drafts.latest(review)).get().satisfies(d -> {
            assertThat(d.getBody()).isEqualTo(phoneText);
            assertThat(d.getVersion()).isEqualTo(2);
            assertThat(d.getAuthorKind()).isEqualTo("SELLER");
            assertThat(d.getCreatedBy()).isEqualTo(VendorReplyTaskService.PHONE_ACTOR);
        });
        ReviewReplyDelegationView shown = tasks.describe(org, review, true);
        assertThat(shown.draftFromPhone()).isTrue();
        assertThat(shown.setAside()).as("nothing was written here meanwhile").isNull();
        assertThat(vendor.tasks.get(task).draftVersion()).as("nothing sent back up").isEqualTo(2);

        drafts.save(org, review, "SELLER:" + seller, phoneText + " 감사합니다.", 2);
        pull.pullFor(org);
        assertThat(vendor.tasks.get(task).draftVersion()).as("then this PC's edit goes up on top of it").isEqualTo(3);
        assertThat(vendor.tasks.get(task).editedOnMobile()).isFalse();
    }

    @Test
    void whenBothDevicesChangedTheTextThePhoneLeadsAndThisPcsWordsAreKeptForTheSeller() {
        UUID review = pulledReview("v1", "9100000106", "배송이 늦어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어져 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        vendor.move(task, "AWAITING_APPROVAL", "늦어져 정말 죄송합니다. 택배사와 이야기하겠습니다.", null, true);
        drafts.save(org, review, "SELLER:" + seller, "늦어져 죄송합니다. 다음엔 더 빨리 보내겠습니다.", 1);

        pull.pullFor(org);

        assertThat(drafts.latest(review)).get().extracting(d -> d.getBody())
                .isEqualTo("늦어져 정말 죄송합니다. 택배사와 이야기하겠습니다.");
        assertThat(vendor.tasks.get(task).draftBody()).as("the phone's words were not overwritten")
                .isEqualTo("늦어져 정말 죄송합니다. 택배사와 이야기하겠습니다.");
        ReviewReplyDelegationView shown = tasks.describe(org, review, true);
        assertThat(shown.setAside()).isNotNull().satisfies(a -> {
            assertThat(a.version()).isEqualTo(2);
            assertThat(a.body()).isEqualTo("늦어져 죄송합니다. 다음엔 더 빨리 보내겠습니다.");
        });

        // 「이 PC 문장 쓰기」: the set-aside words saved on top go up, and there is nothing left to choose.
        drafts.save(org, review, "SELLER:" + seller, "늦어져 죄송합니다. 다음엔 더 빨리 보내겠습니다.", 3);
        pull.pullFor(org);
        assertThat(vendor.tasks.get(task).draftBody()).isEqualTo("늦어져 죄송합니다. 다음엔 더 빨리 보내겠습니다.");
        assertThat(tasks.describe(org, review, true).setAside()).isNull();
    }

    @Test
    void keepingThePhonesWordsDropsTheSetAsideVersion() {
        UUID review = pulledReview("v1", "9100000107", "배송이 늦어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어져 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        vendor.move(task, "AWAITING_APPROVAL", "늦어져 정말 죄송합니다.", null, true);
        drafts.save(org, review, "SELLER:" + seller, "늦어져 죄송합니다!", 1);
        pull.pullFor(org);
        assertThat(tasks.describe(org, review, true).setAside()).isNotNull();

        tasks.dropSetAside(org, review);

        assertThat(tasks.describe(org, review, true).setAside()).isNull();
        assertThat(vendor.tasks.get(task).draftBody()).isEqualTo("늦어져 정말 죄송합니다.");
    }

    @Test
    void handingOverApprovesOnlyTheVersionOnTheScreenAndNeverAPhoneEditItHadNotShown() {
        UUID review = pulledReview("v1", "9100000108", "설명서가 작아요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "불편을 드려 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        drafts.save(org, review, "SELLER:" + seller, "불편을 드려 죄송합니다. 큰 글씨 설명서를 보내 드리겠습니다.", 1);

        assertThatThrownBy(() -> tasks.handOver(org, review, 1)).as("an older version than the head")
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(VendorReplyTaskService.CHANGED));
        assertThat(vendor.approveCommands).isEmpty();

        vendor.move(task, "AWAITING_APPROVAL", "불편을 드려 죄송합니다. 설명서 파일을 보내 드릴게요.", null, true);
        assertThatThrownBy(() -> tasks.handOver(org, review, 2)).as("the phone changed it unseen")
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(VendorReplyTaskService.CHANGED_ON_PHONE));
        assertThat(vendor.approveCommands).isEmpty();
        assertThat(drafts.latest(review)).get().extracting(d -> d.getBody())
                .as("the screen now shows the phone's words").isEqualTo("불편을 드려 죄송합니다. 설명서 파일을 보내 드릴게요.");

        int shown = drafts.currentVersion(review);
        assertThat(tasks.handOver(org, review, shown).state()).isEqualTo("APPROVED");
        assertThat(vendor.tasks.get(task).draftBody()).isEqualTo("불편을 드려 죄송합니다. 설명서 파일을 보내 드릴게요.");
    }

    @Test
    void approvingHereIsRefusedWhenThePhoneApprovedFirst() {
        UUID review = pulledReview("v1", "9100000002", "배송이 빨라요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "빠른 배송으로 만족하셨다니 기쁩니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        vendor.move(task, "APPROVED", "빠른 배송으로 만족하셨다니 기쁩니다.", vendor.tasks.get(task).draftFingerprint(),
                false);

        String ref = VocItemRef.forReview(review);
        assertThatThrownBy(() -> replyLane.decideApproval(org, account, ref, "APPROVED", 1, "cmd-approve-2", seller))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("휴대폰에서 이미"));
        assertThat(vendor.tasks.get(task).state()).as("the phone's approval stands").isEqualTo("APPROVED");
        assertThat(approvals.current(org, review)).isEmpty();
    }

    @Test
    void aDelegatesPostIsRecordedOnceAsReportedAndUnverifiedWithThePhoneText() {
        UUID review = pulledReview("v1", "9100000011", "포장이 꼼꼼해요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "꼼꼼한 포장을 알아봐 주셔서 감사합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        String phoneText = "꼼꼼한 포장을 알아봐 주셔서 감사합니다! 또 찾아 주세요.";
        vendor.move(task, "APPROVED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        vendor.move(task, "CLAIMED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        vendor.move(task, "POSTED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        assertThat(pull.pullFor(org).stores().get(0).taskChanges()).isEqualTo(4);

        assertThat(drafts.latest(review)).get().satisfies(d -> {
            assertThat(d.getBody()).as("the approved words become the head").isEqualTo(phoneText);
            assertThat(d.getAuthorKind()).isEqualTo("SELLER");
        });
        List<ReviewReplyOutcome> recorded = outcomeRows.findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(org, review);
        assertThat(recorded).singleElement().satisfies(o -> {
            assertThat(o.getOperatorOutcome()).isEqualTo(OperatorOutcome.OPERATOR_REPORTED_SUBMITTED);
            assertThat(o.getVerification().name()).isEqualTo("UNVERIFIED");
            assertThat(o.getRecordedFingerprint()).isEqualTo(ReviewReplyFingerprint.of(phoneText));
        });
        assertThat(tasks.holds(org, review)).as("a reported post still holds the review: no second reply from here")
                .isTrue();
        assertThat(replyLane.view(org, account, VocItemRef.forReview(review)).capabilities().canApprove()).isFalse();
        assertThatThrownBy(() -> tasks.upload(org, review))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("이미 담당자가"));

        VendorStoreBinding binding = bindings.findByVendorStoreId(vendor.store).orElseThrow();
        binding.setTaskCursor(0);
        bindings.save(binding);
        pull.pullFor(org);
        assertThat(outcomeRows.findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(org, review))
                .as("re-reading the changes records nothing twice").hasSize(1);
    }

    @Test
    void aReplyTheSellerRewordedOnThePhoneIsTheirAnswerTheNextSimilarReviewIsDraftedFrom() {
        UUID review = pulledReview("v1", "9100000051", "배송이 정말 빠르고 포장이 꼼꼼해서 좋았어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "감사합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        String phoneText = "빠른 배송과 꼼꼼한 포장을 알아봐 주셔서 감사합니다. 다음에도 정성껏 포장해 보내 드리겠습니다.";
        vendor.move(task, "APPROVED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        pull.pullFor(org);

        com.sellerops.attention.reply.ReviewReplyDraft head = drafts.latest(review).orElseThrow();
        assertThat(head.getBody()).as("the phone's words are the seller's version here").isEqualTo(phoneText);
        assertThat(head.getAuthorKind()).isEqualTo("SELLER");
        assertThat(taskCopies.findByVendorTaskId(task)).get()
                .satisfies(t -> assertThat(t.getApprovedDraftVersion()).isEqualTo(head.getVersion()));
        assertThat(memories.findByOrgIdAndOriginRef(org, "review-approved:" + review + ":" + head.getVersion()))
                .get().satisfies(m -> {
                    assertThat(m.getAnswerBody()).isEqualTo(phoneText);
                    assertThat(m.getStrength()).isEqualTo(com.sellerops.knowledge.memory.AnswerMemoryStrength.USER_APPROVED);
                });
        assertThat(pastReplies.read(org, null)).filteredOn(i -> i.entry().text().equals(phoneText)).singleElement()
                .satisfies(i -> assertThat(i.entry().provenance()).isEqualTo("리뷰 답글 · 판매자가 승인한 답글"));

        // The next review about the same thing is drafted with the seller's own sentence among its evidence. Lexical
        // retrieval only here (no embeddings in this context), so the review is a short one: a whole sentence fails
        // the lexical absence gate and needs the semantic lane — ReviewReplyPrecedentRetrievalTest and
        // ReviewPrecedentQualitySetTest hold that path (retrieval_runtime_closure_v1 §10).
        assertThat(spine.retrieveForProduct(org, null,
                        com.sellerops.knowledge.RetrievalQuery.ofCustomer(null, "포장 꼼꼼 배송 빠름"),
                        com.sellerops.product.library.KnowledgeVariantScope.unresolved())
                .lanes().passages())
                .anySatisfy(p -> assertThat(p.text()).isEqualTo(phoneText));

        vendor.move(task, "CLAIMED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        vendor.move(task, "POSTED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        pull.pullFor(org);
        assertThat(drafts.latest(review).orElseThrow().getVersion()).as("no second copy of the same words")
                .isEqualTo(head.getVersion());
        assertThat(pastReplies.read(org, null)).filteredOn(i -> i.entry().text().equals(phoneText)).singleElement()
                .satisfies(i -> assertThat(i.entry().provenance()).isEqualTo("리뷰 답글 · 채널에 등록된 것으로 기록된 답글"));
    }

    @Test
    void anApprovalTheSellerTookBackIsNotPrecedent() {
        UUID review = pulledReview("v1", "9100000061", "색감이 사진과 달라요");
        needsReply(review);
        String text = "사진과 색감이 달라 불편을 드려 죄송합니다. 교환을 도와 드리겠습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        vendor.move(task, "APPROVED", text, ReviewReplyFingerprint.of(text), false);
        pull.pullFor(org);
        assertThat(pastReplies.read(org, null)).anySatisfy(i -> assertThat(i.entry().text()).isEqualTo(text));

        vendor.move(task, "CANCELLED", text, ReviewReplyFingerprint.of(text), false);
        pull.pullFor(org);
        assertThat(pastReplies.read(org, null)).noneSatisfy(i -> assertThat(i.entry().text()).isEqualTo(text));
    }

    @Test
    void stoppingAStoreStopsNewReviewsButNotTheRepliesAlreadyHandedOver() {
        UUID review = pulledReview("v1", "9100000041", "사이즈가 딱 맞아요");
        UUID told = pulledReview("v2", "9100000042", "그럭저럭");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "딱 맞으셨다니 다행입니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        assertThat(pull.setPaused(org, vendor.store, true)).isTrue();

        String phoneText = "딱 맞으셨다니 다행입니다. 감사합니다.";
        vendor.move(task, "APPROVED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        vendor.move(task, "CLAIMED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        vendor.move(task, "POSTED", phoneText, ReviewReplyFingerprint.of(phoneText), true);
        triage.decide(org, told, TriageDisposition.NO_ACTION.name(), "cmd-triage-paused", seller);
        UUID later = vendor.ids.computeIfAbsent("v3", k -> UUID.randomUUID());
        vendor.event("NEW", new VendorServerClient.FeedReview(later, "9100000043", "SKU-합성-1", "합성 상품", 5,
                "멈춘 뒤 올라온 리뷰", com.sellerops.common.ReviewBodyFingerprint.of("멈춘 뒤 올라온 리뷰"), null, null,
                Instant.parse("2026-10-01T01:00:00Z"), "노출", false, null));

        VendorReviewPullService.StoreResult stopped = pull.pullBound().stores().get(0);
        assertThat(stopped.paused()).isTrue();
        assertThat(stopped.received()).as("no new review while stopped").isZero();
        assertThat(stopped.taskChanges()).as("the phone's approval and the delegate's post still arrive").isEqualTo(4);
        assertThat(stopped.noReplyReported()).isEqualTo(1);
        assertThat(taskCopies.findByVendorTaskId(task)).get().satisfies(t -> assertThat(t.getState())
                .isEqualTo("POSTED"));
        assertThat(outcomeRows.findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(org, review)).hasSize(1);
        assertThat(refs.findByVendorReviewId(later)).as("the review after the stop is not taken").isEmpty();
    }

    @Test
    void aRequestIsRefusedWhenAnotherRoadOrTheServerSaysNo() {
        UUID approvedHere = pulledReview("v1", "9100000021", "색이 예뻐요");
        needsReply(approvedHere);
        drafts.save(org, approvedHere, "SELLER:" + seller, "예쁘게 봐 주셔서 감사합니다.", 0);
        replyLane.decideApproval(org, account, VocItemRef.forReview(approvedHere), "APPROVED", 1, "cmd-approve-2",
                seller);
        assertThatThrownBy(() -> tasks.upload(org, approvedHere))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("해제"));

        UUID notNeeded = pulledReview("v3", "9100000023", "무난해요");
        needsReply(notNeeded);
        drafts.save(org, notNeeded, "SELLER:" + seller, "감사합니다.", 0);
        triage.decide(org, notNeeded, TriageDisposition.MONITOR.name(), "cmd-monitor-" + notNeeded, seller);
        assertThatThrownBy(() -> handOver(notNeeded)).as("a review the seller now only watches")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("대응 필요"));
        UUID undecided = pulledReview("v4", "9100000024", "그럭저럭");
        assertThatThrownBy(() -> tasks.upload(org, undecided)).as("and one nobody has decided")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("대응 필요"));
        assertThat(vendor.submitted).isEmpty();

        UUID noDraft = pulledReview("v2", "9100000022", "사이즈가 맞아요");
        needsReply(noDraft);
        assertThatThrownBy(() -> tasks.upload(org, noDraft))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("초안"));

        drafts.save(org, noDraft, "SELLER:" + seller, "잘 맞으셨다니 다행입니다.", 0);
        vendor.refuseNextSubmit = "task_in_progress";
        assertThatThrownBy(() -> tasks.upload(org, noDraft))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("취소"));
        assertThat(vendor.submitted).isEmpty();
        assertThat(tasks.holds(org, noDraft)).isFalse();

        vendor.refuseNextSubmit = "review_purged";
        assertThatThrownBy(() -> tasks.upload(org, noDraft)).as("deleted on the server at retention's end")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("판매자센터에서 직접"));
        assertThat(tasks.holds(org, noDraft)).isFalse();
    }

    @Test
    void handingOverApprovesTheSavedDraftHereAndTheSellerCanTakeItBack() {
        UUID review = pulledReview("v1", "9100000041", "설명서가 작아요");
        needsReply(review);
        String text = "불편을 드려 죄송합니다. 큰 글씨 설명서를 보내 드리겠습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        String ref = VocItemRef.forReview(review);
        assertThat(replyLane.view(org, account, ref).delegation()).satisfies(d -> {
            assertThat(d.taskState()).isNull();
            assertThat(d.canSend()).isTrue();
            assertThat(d.canCancel()).isFalse();
        });

        VendorReplyTaskService.TaskView handed = handOver(review);
        assertThat(handed.state()).isEqualTo("APPROVED");
        UUID task = vendorTaskOf(handed);
        assertThat(vendor.tasks.get(task).approvedFingerprint()).as("approved: exactly the text sent")
                .isEqualTo(ReviewReplyFingerprint.of(text));
        assertThat(vendor.approveCommands).containsExactly("pc-approve:" + task + ":1");
        assertThat(approvals.current(org, review)).as("the server holds the approval, not this PC's copy road")
                .isEmpty();
        ReviewReplyDelegationView held = replyLane.view(org, account, ref).delegation();
        assertThat(held.taskState()).isEqualTo("APPROVED");
        assertThat(held.canSend()).as("approved: not sent again").isFalse();
        assertThat(held.canCancel()).isTrue();
        assertThatThrownBy(() -> handOver(review))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("취소"));

        assertThat(tasks.cancel(org, review).state()).isEqualTo("CANCELLED");
        assertThat(tasks.holds(org, review)).isFalse();
        ReviewReplyDelegationView after = replyLane.view(org, account, ref).delegation();
        assertThat(after.canSend()).as("cancelled: the seller may send again").isTrue();
        assertThat(after.canCancel()).isFalse();
        assertThat(replyLane.view(org, account, ref).capabilities().canApprove()).as("and approve here again")
                .isTrue();
        assertThatThrownBy(() -> tasks.cancel(org, review))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("취소할 수 있는"));
    }

    @Test
    void aClaimedTaskIsOnlyAskedToStopAndAReviewNotFromTheServerHasNoLane() {
        UUID review = pulledReview("v1", "9100000051", "배송이 늦어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어져 죄송합니다.", 0);
        UUID task = vendorTaskOf(handOver(review));
        vendor.move(task, "CLAIMED", "늦어져 죄송합니다.", vendor.tasks.get(task).approvedFingerprint(), false);
        pull.pullFor(org);

        assertThat(tasks.cancel(org, review).state()).isEqualTo("CANCEL_REQUESTED");
        assertThat(tasks.holds(org, review)).as("the delegate may already have saved it").isTrue();
        assertThat(replyLane.view(org, account, VocItemRef.forReview(review)).delegation().canCancel()).isFalse();

        assertThat(tasks.describe(org, UUID.randomUUID(), true)).as("not received from the server").isNull();
    }

    @Test
    void aListAsksTheNewestTaskOfEachReviewAndHearsNothingOfAReviewWithoutOne() {
        UUID resent = pulledReview("v1", "9100000061", "포장이 찢겨 왔어요");
        UUID untouched = pulledReview("v2", "9100000062", "잘 받았습니다");
        needsReply(resent);
        drafts.save(org, resent, "SELLER:" + seller, "불편을 드려 죄송합니다.", 0);
        handOver(resent);
        assertThat(tasks.cancel(org, resent).state()).isEqualTo("CANCELLED");
        handOver(resent);

        assertThat(tasks.statesOf(org, List.of(resent, untouched)))
                .as("the second hand-over, not the cancelled one").containsExactlyEntriesOf(Map.of(resent, "APPROVED"));
        assertThat(tasks.statesOf(org, List.of())).isEmpty();
    }

    @Test
    void onlyTheSellersOwnNoReplyIsToldToTheServerAndOnlyOnce() {
        UUID sellerSaid = pulledReview("v1", "9100000031", "그냥 그래요");
        UUID machineSaid = pulledReview("v2", "9100000032", "보통이에요");
        triage.decide(org, sellerSaid, TriageDisposition.NO_ACTION.name(), "cmd-triage-1", seller);
        triageWriter.applyDecision(org, machineSaid, channels.findByCode("NAVER").orElseThrow().getId(),
                TriageDisposition.NO_ACTION, "cmd-triage-2", "AUTO_CHECK:synthetic");

        assertThat(pull.pullFor(org).stores().get(0).noReplyReported()).isEqualTo(1);
        assertThat(vendor.noReply).containsExactly(vendor.ids.get("v1"));
        assertThat(vendor.noReplyCommands).containsExactly("no-reply:" + vendor.ids.get("v1"));
        pull.pullFor(org);
        assertThat(vendor.noReply).as("told once").hasSize(1);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /* ── what drafts are written from changes (2026-10-22 review; interview question 34) ── */

    @Test
    void aDraftGoesOutUnderTheSellersCurrentFingerprintsReportedFirst() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000301", "배송이 빨라요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "빠른 배송으로 만족하셨다니 기쁩니다.", 0);

        assertThat(tasks.upload(org, review).state()).isEqualTo("AWAITING_APPROVAL");

        var now = draftBasis.of(org);
        assertThat(List.of(now.knowledge(), now.policy(), now.voice())).allMatch(fp -> fp.matches("v1:[0-9a-f]{32}"));
        assertThat(vendor.policyChecks).as("reported once, before the draft").hasSize(1);
        assertThat(vendor.submitted).singleElement().satisfies(s -> assertThat(List.of(s.meta().knowledgeFp(),
                s.meta().policyFp(), s.meta().voiceFp())).containsExactly(now.knowledge(), now.policy(), now.voice()));
        pull.pullFor(org);
        assertThat(vendor.policyChecks).as("nothing changed: not reported again").hasSize(1);
    }

    @Test
    void aWaitingDraftSentBackByANewRuleThatStillStandsReturnsToWaitingUnchanged() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000302", "배송이 빨라요");
        needsReply(review);
        String text = "빠른 배송으로 만족하셨다니 기쁩니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                "평일 오후 2시 전 주문은 당일 출고합니다.", null), null, "합성 판매자");
        pull.pullFor(org);

        assertThat(vendor.policyChecks).hasSize(2);
        assertThat(vendor.tasks.get(task)).satisfies(t -> {
            assertThat(t.state()).isEqualTo("AWAITING_APPROVAL");
            assertThat(t.draftBody()).as("the text is the text").isEqualTo(text);
        });
        assertThat(vendor.submitted).last().satisfies(s ->
                assertThat(s.meta().policyFp()).isEqualTo(draftBasis.of(org).policy()));
        assertThat(vendor.rechecks).as("not a phone edit: nothing to mark").isEmpty();
        assertThat(tasks.taskFor(org, review)).get().extracting(VendorReplyTaskService.TaskView::state)
                .isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aPromiseTheCurrentRulesNoLongerMakeIsHeldForTheSellerOnceAndNotRewritten() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var rule = companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.EXCHANGE_REFUND_POLICY, "교환 안내",
                "사이즈 교환은 수령 후 7일 이내 무료로 해 드립니다.", null), null, "합성 판매자");
        UUID review = pulledReview("v1", "9100000303", "사이즈 교환 되나요");
        needsReply(review);
        String text = "수령 후 7일 이내라면 사이즈 교환을 도와드리겠습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        var retired = companyRuleRows.findById(rule.id()).orElseThrow();
        retired.setActive(false);
        companyRuleRows.save(retired);
        pull.pullFor(org);

        assertThat(vendor.rechecks).singleElement().satisfies(r -> {
            assertThat(r.taskId()).isEqualTo(task);
            assertThat(r.passed()).isFalse();
            assertThat(r.codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.UNSUPPORTED_PROMISE);
            assertThat(r.note().promises()).as("the word that lost its ground").containsExactly("교환");
            assertThat(r.note().sentences()).as("the reply's own sentence").containsExactly(text);
        });
        assertThat(vendor.tasks.get(task)).satisfies(t -> {
            assertThat(t.state()).isEqualTo("RECHECK_NEEDED");
            assertThat(t.draftBody()).isEqualTo(text);
        });
        assertThat(tasks.describe(org, review, true).note()).as("the seller's screen is told why").satisfies(n ->
                assertThat(n.promises()).containsExactly("교환"));
        pull.pullFor(org);
        assertThat(vendor.rechecks).as("held once, not on every pull").hasSize(1);
        assertThat(tasks.describe(org, review, true).canSend()).as("the seller can draft it again").isTrue();
    }

    @Test
    void theSellersOwnWordingIsTheirStandingWordNotAnUnsupportedPromise() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        String own = "찾아 주셔서 감사합니다. 사이즈가 맞지 않으면 교환해 드립니다.";
        com.sellerops.attention.reply.ReviewReplyTemplate template = new com.sellerops.attention.reply.ReviewReplyTemplate();
        template.setOrgId(org);
        template.setTemplateKey(com.sellerops.attention.reply.ReviewReplyTemplateKey.POSITIVE.category());
        template.setBody(own);
        templateRows.save(template);
        UUID review = pulledReview("v1", "9100000307", "좋아요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, own, 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.GENERAL_CS_FAQ, "공통 안내",
                "문의는 평일 10시부터 17시까지 받습니다.", null), null, "합성 판매자");
        pull.pullFor(org);

        assertThat(vendor.tasks.get(task).state()).isEqualTo("AWAITING_APPROVAL");
        assertThat(vendor.rechecks).isEmpty();
    }

    @Test
    void anExchangeOnceGrantedInAPastReplyDoesNotLicenseOneNow() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        // The seller once granted an exchange for a review like this one. A way of saying, never a policy.
        UUID earlier = pulledReview("v0", "9100000308", "사이즈 교환 되나요");
        answerMemory.remember(new com.sellerops.knowledge.memory.AnswerMemoryService.RememberCommand(org,
                "review-approved:" + earlier + ":1", com.sellerops.knowledge.memory.AnswerMemoryStrength.USER_APPROVED,
                "사이즈 교환 되나요", "리뷰 답글 · 별점 5점", "이번에는 특별히 사이즈 교환을 도와드리겠습니다.", null,
                "NAVER", null, null, null, null, 1, null, null, null, null, earlier));
        UUID review = pulledReview("v1", "9100000309", "사이즈 교환 되나요");
        needsReply(review);
        String text = "사이즈 교환을 도와드리겠습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.GENERAL_CS_FAQ, "공통 안내",
                "문의는 평일 10시부터 17시까지 받습니다.", null), null, "합성 판매자");
        pull.pullFor(org);

        // Found when it went up (the phone shows it), and found again when the new rule sent it back.
        assertThat(vendor.rechecks).hasSize(2).allSatisfy(r -> {
            assertThat(r.taskId()).isEqualTo(task);
            assertThat(r.codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.UNSUPPORTED_PROMISE);
        });
    }

    @Test
    void aPhoneEditThatStillStandsKeepsItsMarkAndGetsThisPcsPass() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000304", "포장이 꼼꼼해요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "꼼꼼한 포장을 알아봐 주셔서 감사합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        String phoneText = "꼼꼼한 포장을 알아봐 주셔서 감사합니다! 또 찾아 주세요.";
        vendor.move(task, "AWAITING_APPROVAL", phoneText, null, true);

        companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.GENERAL_CS_FAQ, "공통 안내",
                "문의는 평일 10시부터 17시까지 받습니다.", null), null, "합성 판매자");
        pull.pullFor(org);

        assertThat(vendor.tasks.get(task)).satisfies(t -> {
            assertThat(t.state()).isEqualTo("AWAITING_APPROVAL");
            assertThat(t.draftBody()).isEqualTo(phoneText);
            assertThat(t.editedOnMobile()).as("still the phone's words").isTrue();
        });
        assertThat(vendor.rechecks).singleElement().satisfies(r -> {
            assertThat(r.passed()).isTrue();
            assertThat(r.draftVersion()).isEqualTo(vendor.tasks.get(task).draftVersion());
        });
    }

    @Test
    void aDraftWhoseCitedRuleWasEditedSinceIsHeldEvenWithoutAPromise() throws Exception {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var rule = companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                "평일 오후 2시 전 주문은 당일 출고합니다.", null), null, "합성 판매자");
        UUID review = pulledReview("v1", "9100000305", "배송이 빨라요");
        needsReply(review);
        String text = "오후 2시 전 주문은 당일 출고해 빠르게 받아 보실 수 있습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        // As the composer records it: this version was written from that rule.
        com.sellerops.review.draft.ReviewDraftEvidence cited = new com.sellerops.review.draft.ReviewDraftEvidence();
        cited.setOrgId(org);
        cited.setReviewId(review);
        cited.setDraftVersion(drafts.currentVersion(review));
        cited.setOrdinal(0);
        cited.setKind(com.sellerops.inquiry.draft.InquiryDraftEvidence.KIND_ORG_POLICY);
        cited.setSourceId(rule.id());
        cited.setTitle("배송 안내");
        draftEvidence.save(cited);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        Thread.sleep(5);
        companyRules.update(org, rule.id(), new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                "평일 오전 11시 전 주문만 당일 출고합니다.", null));
        pull.pullFor(org);

        assertThat(vendor.rechecks).singleElement().satisfies(r -> {
            assertThat(r.taskId()).isEqualTo(task);
            assertThat(r.passed()).isFalse();
            assertThat(r.codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.SOURCE_CHANGED);
        });
        assertThat(vendor.tasks.get(task).state()).isEqualTo("RECHECK_NEEDED");
    }

    /* ── the company's forbidden phrases reach review replies (2026-10-23, B24) ── */

    private void ban(String... phrases) {
        answerStyles.save(org, new com.sellerops.knowledge.style.dto.AnswerStyleRequest(null, null, null, null, null,
                null, java.util.List.of(), java.util.List.of(phrases), null), seller);
    }

    @Test
    void anApprovedReplyCarryingAPhraseBannedSinceIsHeldWithThePhrase() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000317", "배송이 늦었어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어서 죄송합니다. 고객님 탓이 아닙니다.", 0);
        UUID task = vendorTaskOf(handOver(review));

        ban("고객님 탓", "환불");
        pull.pullFor(org);

        assertThat(vendor.approvedChecks).singleElement().satisfies(c -> {
            assertThat(c.hold()).isTrue();
            assertThat(c.note().codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.FORBIDDEN_PHRASE);
            assertThat(c.note().phrases()).as("a bare topic word is not enforced").containsExactly("고객님 탓");
            assertThat(c.note().sentences()).containsExactly("고객님 탓이 아닙니다.");
        });
        assertThat(vendor.tasks.get(task).state()).isEqualTo("HELD");
        assertThat(tasks.describe(org, review, true).note().phrases()).containsExactly("고객님 탓");
    }

    @Test
    void aDraftCarryingABannedPhraseIsNeverApprovedAndGoesToThePhoneMarked() {
        tasks.setStyleGuard(styleGuard);
        ban("고객님 탓");
        UUID review = pulledReview("v1", "9100000319", "배송이 늦었어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어서 죄송합니다. 고객님 탓이 아닙니다.", 0);
        assertThatThrownBy(() -> handOver(review)).isInstanceOf(com.sellerops.common.ApiException.class)
                .hasMessageContaining("「고객님 탓」");
        assertThat(vendor.approveCommands).as("never approved").isEmpty();
        // It still goes up for the phone — an older text there would be worse — marked so the phone cannot approve it.
        UUID task = vendorTaskOf(tasks.upload(org, review));
        assertThat(vendor.rechecks).singleElement().satisfies(r -> {
            assertThat(r.taskId()).isEqualTo(task);
            assertThat(r.passed()).isFalse();
            assertThat(r.codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.FORBIDDEN_PHRASE);
            assertThat(r.note().phrases()).containsExactly("고객님 탓");
        });
    }

    @Test
    void aPhoneEditCarryingABannedPhraseApprovedBeforeThisPcSawItIsHeld() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        ban("고객님 탓");
        UUID review = pulledReview("v1", "9100000320", "배송이 늦었어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어서 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        String phoneText = "늦어서 죄송합니다. 고객님 탓이 아닙니다.";
        vendor.move(task, "AWAITING_APPROVAL", phoneText, null, true);
        vendor.move(task, "APPROVED", phoneText, vendor.tasks.get(task).draftFingerprint(), false);

        pull.pullFor(org);

        assertThat(vendor.rechecks).as("its first check holds it on the server, in one step").singleElement()
                .satisfies(r -> {
                    assertThat(r.passed()).isFalse();
                    assertThat(r.note().phrases()).containsExactly("고객님 탓");
                });
        assertThat(vendor.approvedChecks).isEmpty();
        assertThat(vendor.tasks.get(task).state()).isEqualTo("HELD");
    }

    /* ── a check that did not reach the server is not a check (2026-10-23 external review) ── */

    @Test
    void aPhoneEditCheckTheServerRefusedIsAskedAgainOnTheNextPull() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000330", "사이즈가 안 맞아요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "불편을 드려 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        vendor.move(task, "AWAITING_APPROVAL", "불편을 드려 죄송합니다. 확인해 보겠습니다.", null, true);

        vendor.refuseNextRecheck = "stale";
        pull.pullFor(org);
        assertThat(vendor.rechecks).isEmpty();
        assertThat(vendor.tasks.get(task).pcRecheckVersion()).isNull();

        pull.pullFor(org);
        assertThat(vendor.rechecks).singleElement().satisfies(r -> assertThat(r.draftVersion()).isEqualTo(2));
        pull.pullFor(org);
        assertThat(vendor.rechecks).as("once recorded, once only").hasSize(1);
    }

    @Test
    void aPhoneEditMadeAgainWhileThisPcCheckedIsCheckedAtItsNewVersion() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000331", "사이즈가 안 맞아요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "불편을 드려 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        vendor.move(task, "AWAITING_APPROVAL", "첫 휴대폰 수정입니다.", null, true);
        vendor.beforeRecheck = () -> vendor.move(task, "AWAITING_APPROVAL", "두 번째 휴대폰 수정입니다.", null, true);

        pull.pullFor(org);
        assertThat(vendor.rechecks).as("the check of the old words was refused as stale").isEmpty();

        pull.pullFor(org);
        assertThat(vendor.rechecks).singleElement().satisfies(r -> assertThat(r.draftVersion()).isEqualTo(3));
    }

    @Test
    void anApprovedCheckTheServerRefusedIsAskedAgainOnTheNextPull() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000332", "배송이 늦었어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어서 죄송합니다. 고객님 탓이 아닙니다.", 0);
        UUID task = vendorTaskOf(handOver(review));
        ban("고객님 탓");

        vendor.refuseNextApprovedCheck = "stale";
        pull.pullFor(org);
        assertThat(vendor.approvedChecks).isEmpty();
        assertThat(vendor.tasks.get(task).state()).isEqualTo("APPROVED");

        pull.pullFor(org);
        assertThat(vendor.approvedChecks).singleElement().satisfies(c -> assertThat(c.hold()).isTrue());
        assertThat(vendor.tasks.get(task).state()).isEqualTo("HELD");
    }

    @Test
    void aSentBackCheckTheServerRefusedIsAskedAgainOnTheNextPull() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var rule = exchangeRule();
        UUID review = pulledReview("v1", "9100000335", "사이즈 교환 되나요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "수령 후 7일 이내라면 사이즈 교환을 도와드리겠습니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        retire(rule.id());

        vendor.refuseNextRecheck = "stale";
        pull.pullFor(org);
        assertThat(vendor.rechecks).isEmpty();
        assertThat(vendor.tasks.get(task).state()).isEqualTo("RECHECK_NEEDED");

        pull.pullFor(org);
        assertThat(vendor.rechecks).singleElement().satisfies(r -> assertThat(r.passed()).isFalse());
    }

    @Test
    void phoneEditsApprovedWhileThisPcWasOffAreCheckedWhenItComesBackEvenUnderAMovedBasis() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID clean = pulledReview("v1", "9100000333", "배송이 늦었어요");
        UUID promising = pulledReview("v2", "9100000334", "사이즈가 안 맞아요");
        needsReply(clean);
        needsReply(promising);
        drafts.save(org, clean, "SELLER:" + seller, "늦어서 죄송합니다.", 0);
        drafts.save(org, promising, "SELLER:" + seller, "불편을 드려 죄송합니다.", 0);
        UUID cleanTask = vendorTaskOf(tasks.upload(org, clean));
        UUID promisingTask = vendorTaskOf(tasks.upload(org, promising));
        // This PC is off: the seller edits and approves both on the phone, and the company rules change meanwhile.
        String cleanText = "늦어서 정말 죄송합니다.";
        String promisingText = "불편을 드려 죄송합니다. 사이즈 교환을 도와드리겠습니다.";
        vendor.move(cleanTask, "AWAITING_APPROVAL", cleanText, null, true);
        vendor.move(cleanTask, "APPROVED", cleanText, vendor.tasks.get(cleanTask).draftFingerprint(), false);
        vendor.move(promisingTask, "AWAITING_APPROVAL", promisingText, null, true);
        vendor.move(promisingTask, "APPROVED", promisingText, vendor.tasks.get(promisingTask).draftFingerprint(),
                false);
        companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.GENERAL_CS_FAQ, "공통 안내",
                "문의는 평일 10시부터 17시까지 받습니다.", null), null, "합성 판매자");

        pull.pullFor(org);

        assertThat(vendor.tasks.get(cleanTask).pcRecheckVersion()).as("checked: the delegate may take it")
                .isEqualTo(vendor.tasks.get(cleanTask).draftVersion());
        assertThat(vendor.tasks.get(cleanTask).state()).isEqualTo("APPROVED");
        assertThat(vendor.tasks.get(promisingTask).state()).as("the seller never saw this promise flagged")
                .isEqualTo("HELD");
        assertThat(vendor.rechecks).filteredOn(r -> r.taskId().equals(promisingTask)).singleElement()
                .satisfies(r -> assertThat(r.note().promises()).containsExactly("교환"));
    }

    @Test
    void aWaitingDraftCarryingABannedPhraseIsSentBackForTheSeller() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000318", "배송이 늦었어요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "늦어서 죄송합니다. 고객님 탓이 아닙니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));

        ban("고객님 탓");
        pull.pullFor(org);

        assertThat(vendor.rechecks).singleElement().satisfies(r -> {
            assertThat(r.codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.FORBIDDEN_PHRASE);
            assertThat(r.note().phrases()).containsExactly("고객님 탓");
        });
        assertThat(vendor.tasks.get(task).state()).isEqualTo("RECHECK_NEEDED");
    }

    /* ── stage 2: an edit counts only when it changed the passage the draft used (2026-10-23) ── */

    private static final String SHIPPING = "평일 오후 2시 전 주문은 당일 출고합니다. ".repeat(14).strip();
    private static final String ISLANDS = "제주와 도서 지역은 배송에 이틀이 더 걸립니다. ".repeat(14).strip();

    /** A two-section shipping rule, and a waiting draft that cites its first section the way the composer does. */
    private UUID[] draftCitingTheFirstSection(String reviewNo) {
        var rule = companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내", SHIPPING + "\n\n" + ISLANDS,
                null), null, "합성 판매자");
        var chunks = companyRuleChunks.findAllBySourceId(rule.id());
        assertThat(chunks).as("two sections, two passages").hasSize(2);
        UUID review = pulledReview("v1", reviewNo, "배송이 빨라요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "오후 2시 전 주문은 당일 출고해 드립니다.", 0);
        com.sellerops.review.draft.ReviewDraftEvidence cited = new com.sellerops.review.draft.ReviewDraftEvidence();
        cited.setOrgId(org);
        cited.setReviewId(review);
        cited.setDraftVersion(drafts.currentVersion(review));
        cited.setOrdinal(0);
        cited.setKind(com.sellerops.inquiry.draft.InquiryDraftEvidence.KIND_ORG_POLICY);
        cited.setSourceId(rule.id());
        cited.setChunkId(chunks.get(0).getId());
        cited.setTitle("배송 안내");
        cited.setPassageFingerprint(com.sellerops.review.draft.ReviewDraftEvidence.fingerprintOf(
                chunks.stream().filter(c -> c.getContent().startsWith("평일")).findFirst().orElseThrow().getContent()));
        draftEvidence.save(cited);
        return new UUID[] {rule.id(), review, vendorTaskOf(tasks.upload(org, review))};
    }

    @Test
    void anEditToAnotherSectionOfTheCitedDocumentDoesNotSendTheDraftBack() throws Exception {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID[] ids = draftCitingTheFirstSection("9100000315");

        Thread.sleep(5);
        companyRules.update(org, ids[0], new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                SHIPPING + "\n\n" + ISLANDS.replace("이틀이", "사흘이"), null));
        pull.pullFor(org);

        assertThat(vendor.rechecks).as("the passage it used is word for word as it was").isEmpty();
        assertThat(vendor.tasks.get(ids[2]).state()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void anEditToTheCitedSectionStillSendsTheDraftBack() throws Exception {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID[] ids = draftCitingTheFirstSection("9100000316");

        Thread.sleep(5);
        companyRules.update(org, ids[0], new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                SHIPPING.replace("오후 2시", "오전 11시") + "\n\n" + ISLANDS, null));
        pull.pullFor(org);

        assertThat(vendor.rechecks).singleElement().satisfies(r -> {
            assertThat(r.codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.SOURCE_CHANGED);
            assertThat(r.note().sources()).singleElement().satisfies(src -> assertThat(src.title()).isEqualTo("배송 안내"));
        });
        assertThat(vendor.tasks.get(ids[2]).state()).isEqualTo("RECHECK_NEEDED");
    }

    @Test
    void anApprovalAlreadyGivenIsNotSentBackByANewRule() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000306", "배송이 빨라요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "빠른 배송으로 만족하셨다니 기쁩니다.", 0);
        UUID task = vendorTaskOf(handOver(review));

        companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                "평일 오후 2시 전 주문은 당일 출고합니다.", null), null, "합성 판매자");
        pull.pullFor(org);

        assertThat(vendor.tasks.get(task).state()).isEqualTo("APPROVED");
        assertThat(vendor.rechecks).isEmpty();
        assertThat(vendor.approvedChecks).as("nothing in it conflicts: nothing sent").isEmpty();
    }

    /* ── approvals held only for a substantive conflict (owner decision 2026-10-23) ── */

    private com.sellerops.knowledge.org.dto.OrgKnowledgeView exchangeRule() {
        return companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.EXCHANGE_REFUND_POLICY, "교환 안내",
                "사이즈 교환은 수령 후 7일 이내 무료로 해 드립니다.", null), null, "합성 판매자");
    }

    private void retire(UUID ruleId) {
        var retired = companyRuleRows.findById(ruleId).orElseThrow();
        retired.setActive(false);
        companyRuleRows.save(retired);
    }

    @Test
    void anApprovedPromiseWhoseRuleWasRetiredIsHeldOnceWithItsReason() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var rule = exchangeRule();
        UUID review = pulledReview("v1", "9100000310", "사이즈 교환 되나요");
        needsReply(review);
        String text = "불편을 드려 죄송합니다. 수령 후 7일 이내라면 사이즈 교환을 도와드리겠습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        UUID task = vendorTaskOf(handOver(review));

        retire(rule.id());
        pull.pullFor(org);

        assertThat(vendor.approvedChecks).singleElement().satisfies(c -> {
            assertThat(c.taskId()).isEqualTo(task);
            assertThat(c.hold()).isTrue();
            assertThat(c.approvedVersion()).isEqualTo(1);
            assertThat(c.note().promises()).containsExactly("교환");
            assertThat(c.note().sentences()).containsExactly("수령 후 7일 이내라면 사이즈 교환을 도와드리겠습니다.");
        });
        assertThat(vendor.tasks.get(task).state()).isEqualTo("HELD");
        var shown = tasks.describe(org, review, true);
        assertThat(shown.taskState()).isEqualTo("HELD");
        assertThat(shown.note().sentences()).containsExactly("수령 후 7일 이내라면 사이즈 교환을 도와드리겠습니다.");
        assertThat(shown.canSend()).as("the seller can draft it again").isTrue();
        assertThat(tasks.holds(org, review)).as("still one road: no approval here meanwhile").isTrue();

        pull.pullFor(org);
        assertThat(vendor.approvedChecks).as("checked once, not on every pull").hasSize(1);
    }

    @Test
    void aClaimedPromiseWhoseRuleWasRetiredAsksItsDelegateToStop() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var rule = exchangeRule();
        UUID review = pulledReview("v1", "9100000311", "사이즈 교환 되나요");
        needsReply(review);
        String text = "수령 후 7일 이내라면 사이즈 교환을 도와드리겠습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        UUID task = vendorTaskOf(handOver(review));
        vendor.move(task, "CLAIMED", text, vendor.tasks.get(task).approvedFingerprint(), false);

        retire(rule.id());
        pull.pullFor(org);

        assertThat(vendor.approvedChecks).singleElement().satisfies(c -> assertThat(c.hold()).isTrue());
        assertThat(vendor.tasks.get(task).state()).isEqualTo("HOLD_REQUESTED");
        assertThat(tasks.describe(org, review, true).canCancel()).isTrue();
    }

    @Test
    void aPhoneEditIsCheckedBeforeApprovalAndAPromiseTheSellerApprovedAnywayIsNotHeldAgain() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        UUID review = pulledReview("v1", "9100000313", "사이즈가 안 맞아요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "불편을 드려 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        String phoneText = "불편을 드려 죄송합니다. 사이즈 교환을 도와드리겠습니다.";
        vendor.move(task, "AWAITING_APPROVAL", phoneText, null, true);

        pull.pullFor(org);
        assertThat(vendor.rechecks).as("checked under the basis it was written in, before approval").singleElement()
                .satisfies(r -> {
                    assertThat(r.passed()).isFalse();
                    assertThat(r.note().promises()).containsExactly("교환");
                    assertThat(r.note().sentences()).containsExactly("사이즈 교환을 도와드리겠습니다.");
                });
        pull.pullFor(org);
        assertThat(vendor.rechecks).as("once per version").hasSize(1);

        // The seller saw it flagged and approved it on the phone anyway.
        vendor.move(task, "APPROVED", phoneText, vendor.tasks.get(task).draftFingerprint(), false);
        companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.GENERAL_CS_FAQ, "공통 안내",
                "문의는 평일 10시부터 17시까지 받습니다.", null), null, "합성 판매자");
        pull.pullFor(org);

        assertThat(vendor.approvedChecks).as("the same promise is the seller's word, not a new conflict").isEmpty();
        assertThat(vendor.tasks.get(task).state()).isEqualTo("APPROVED");
    }

    @Test
    void aPromiseThatLosesItsGroundAfterApprovalIsHeldEvenBesideOneTheSellerAccepted() {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var exchanges = exchangeRule();
        UUID review = pulledReview("v1", "9100000314", "사이즈 교환 되나요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "불편을 드려 죄송합니다.", 0);
        UUID task = vendorTaskOf(tasks.upload(org, review));
        String phoneText = "불편을 드려 죄송합니다. 사이즈 교환 또는 환불을 도와드리겠습니다.";
        vendor.move(task, "AWAITING_APPROVAL", phoneText, null, true);
        pull.pullFor(org);
        assertThat(vendor.rechecks).singleElement().satisfies(r -> assertThat(r.note().promises())
                .containsExactly("환불"));
        vendor.move(task, "APPROVED", phoneText, vendor.tasks.get(task).draftFingerprint(), false);

        retire(exchanges.id());
        pull.pullFor(org);

        assertThat(vendor.approvedChecks).singleElement().satisfies(c -> {
            assertThat(c.hold()).isTrue();
            assertThat(c.note().promises()).as("only the promise that lost its ground since").containsExactly("교환");
        });
        assertThat(vendor.tasks.get(task).state()).isEqualTo("HELD");
    }

    @Test
    void anApprovalWhoseCitedRuleWasOnlyEditedStandsWithANote() throws Exception {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var rule = companyRules.create(org, new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                "평일 오후 2시 전 주문은 당일 출고합니다.", null), null, "합성 판매자");
        UUID review = pulledReview("v1", "9100000312", "배송이 빨라요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "빠르게 받아 보셨다니 기쁩니다.", 0);
        com.sellerops.review.draft.ReviewDraftEvidence cited = new com.sellerops.review.draft.ReviewDraftEvidence();
        cited.setOrgId(org);
        cited.setReviewId(review);
        cited.setDraftVersion(drafts.currentVersion(review));
        cited.setOrdinal(0);
        cited.setKind(com.sellerops.inquiry.draft.InquiryDraftEvidence.KIND_ORG_POLICY);
        cited.setSourceId(rule.id());
        cited.setTitle("배송 안내");
        draftEvidence.save(cited);
        UUID task = vendorTaskOf(handOver(review));

        Thread.sleep(5);
        companyRules.update(org, rule.id(), new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                "평일 오전 11시 전 주문만 당일 출고합니다.", null));
        pull.pullFor(org);

        assertThat(vendor.approvedChecks).singleElement().satisfies(c -> {
            assertThat(c.hold()).as("a document edit alone cancels no approval").isFalse();
            assertThat(c.note().codes()).containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.SOURCE_CHANGED);
            assertThat(c.note().sources()).singleElement().satisfies(src -> {
                assertThat(src.title()).isEqualTo("배송 안내");
                assertThat(src.kind()).isEqualTo("ORG_POLICY");
                assertThat(src.removed()).isFalse();
                assertThat(src.changedAt()).isNotNull();
            });
        });
        assertThat(vendor.tasks.get(task).state()).isEqualTo("APPROVED");
        assertThat(tasks.describe(org, review, true).note().codes())
                .containsExactly(com.sellerops.review.draft.ReviewDraftRecheck.SOURCE_CHANGED);

        pull.pullFor(org);
        assertThat(vendor.approvedChecks).as("a standing approval is checked once, not on every pull").hasSize(1);
    }

    @Test
    void anApprovedPromiseWhosePeriodAndCostChangedIsHeldThoughTheRuleStillSaysExchange() throws Exception {
        tasks.setDraftBasis(draftBasis, draftRecheck);
        var rule = exchangeRule();
        UUID review = pulledReview("v1", "9100000336", "사이즈 교환 되나요");
        needsReply(review);
        String text = "불편을 드려 죄송합니다. 수령 후 7일 이내 무료로 사이즈 교환해 드리겠습니다.";
        drafts.save(org, review, "SELLER:" + seller, text, 0);
        UUID task = vendorTaskOf(handOver(review));

        Thread.sleep(5);
        companyRules.update(org, rule.id(), new com.sellerops.knowledge.org.dto.OrgKnowledgeRequest(
                com.sellerops.knowledge.org.OrgKnowledgeType.EXCHANGE_REFUND_POLICY, "교환 안내",
                "사이즈 교환은 수령 후 14일 이내 가능하며 왕복 배송비는 고객님 부담입니다.", null));
        pull.pullFor(org);

        assertThat(vendor.approvedChecks).singleElement().satisfies(c -> {
            assertThat(c.hold()).as("the rule still says 교환; its terms no longer match").isTrue();
            assertThat(c.note().promises()).containsExactly("7일", "무료");
            assertThat(c.note().sentences()).containsExactly("수령 후 7일 이내 무료로 사이즈 교환해 드리겠습니다.");
        });
        assertThat(vendor.tasks.get(task).state()).isEqualTo("HELD");
    }

    private UUID pulledReview(String key, String reviewNo, String body) {
        UUID id = vendor.ids.computeIfAbsent(key, k -> UUID.randomUUID());
        vendor.event("NEW", new VendorServerClient.FeedReview(id, reviewNo, "SKU-합성-1", "합성 상품", 5, body,
                com.sellerops.common.ReviewBodyFingerprint.of(body), null, null,
                Instant.parse("2026-10-01T01:00:00Z"), "노출", false, null));
        pull.start(org, List.of(vendor.store));
        UUID naver = channels.findByCode("NAVER").orElseThrow().getId();
        return reviews.findByOrgIdAndChannelIdAndExternalId(org, naver, reviewNo).orElseThrow().getId();
    }

    private void needsReply(UUID review) {
        triage.decide(org, review, TriageDisposition.RESPONSE_NEEDED.name(), "cmd-needs-" + review, seller);
    }

    private UUID vendorTaskOf(VendorReplyTaskService.TaskView view) {
        return taskCopies.findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(org, view.reviewId()).orElseThrow()
                .getVendorTaskId();
    }
}
