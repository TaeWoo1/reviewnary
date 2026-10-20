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
    @Autowired ReviewTriageService triage;
    @Autowired ReviewTriageWriter triageWriter;

    private FakeVendorServer vendor;
    private VendorReplyTaskService tasks;
    private VendorReviewPullService pull;
    private UUID org;
    private UUID account;
    private UUID seller;

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
                CLOCK);
        pull = new VendorReviewPullService(link, bindings, refs, reviews, channels, ingestion, followUp, syncJobs,
                tasks, CLOCK);
    }

    @Test
    void theSellersRequestSendsTheCurrentDraftAndShutsThisPcsOwnRoad() {
        UUID review = pulledReview("v1", "9100000001", "배송이 빨라요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "빠른 배송으로 만족하셨다니 기쁩니다.", 0);

        VendorReplyTaskService.TaskView sent = tasks.requestApproval(org, review);
        assertThat(sent.state()).isEqualTo("AWAITING_APPROVAL");
        assertThat(vendor.submitted).singleElement().satisfies(s -> {
            assertThat(s.reviewId()).isEqualTo(vendor.ids.get("v1"));
            assertThat(s.body()).isEqualTo("빠른 배송으로 만족하셨다니 기쁩니다.");
            assertThat(s.meta().reviewBodyFingerprint()).isEqualTo(refs.findByReviewId(review).orElseThrow()
                    .getBodyFingerprint());
            assertThat(List.of(s.meta().knowledgeFp(), s.meta().policyFp(), s.meta().voiceFp()))
                    .containsOnly(VendorReplyTaskService.UNVERSIONED);
        });

        assertThat(tasks.openOn(org, review)).isTrue();
        String ref = VocItemRef.forReview(review);
        assertThat(replyLane.view(org, account, ref).capabilities().canApprove())
                .as("the flag says what the guard enforces").isFalse();
        assertThatThrownBy(() -> replyLane.decideApproval(org, account, ref, "APPROVED", 1, "cmd-approve-1", seller))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("휴대폰"));
    }

    @Test
    void aDelegatesPostIsRecordedOnceAsReportedAndUnverifiedWithThePhoneText() {
        UUID review = pulledReview("v1", "9100000011", "포장이 꼼꼼해요");
        needsReply(review);
        drafts.save(org, review, "SELLER:" + seller, "꼼꼼한 포장을 알아봐 주셔서 감사합니다.", 0);
        UUID task = vendorTaskOf(tasks.requestApproval(org, review));

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
        assertThat(tasks.openOn(org, review)).as("POSTED ends it").isFalse();

        VendorStoreBinding binding = bindings.findByVendorStoreId(vendor.store).orElseThrow();
        binding.setTaskCursor(0);
        bindings.save(binding);
        pull.pullFor(org);
        assertThat(outcomeRows.findAllByOrgIdAndReviewIdOrderByCreatedAtDesc(org, review))
                .as("re-reading the changes records nothing twice").hasSize(1);
    }

    @Test
    void aRequestIsRefusedWhenAnotherRoadOrTheServerSaysNo() {
        UUID approvedHere = pulledReview("v1", "9100000021", "색이 예뻐요");
        needsReply(approvedHere);
        drafts.save(org, approvedHere, "SELLER:" + seller, "예쁘게 봐 주셔서 감사합니다.", 0);
        replyLane.decideApproval(org, account, VocItemRef.forReview(approvedHere), "APPROVED", 1, "cmd-approve-2",
                seller);
        assertThatThrownBy(() -> tasks.requestApproval(org, approvedHere))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("철회"));

        UUID noDraft = pulledReview("v2", "9100000022", "사이즈가 맞아요");
        assertThatThrownBy(() -> tasks.requestApproval(org, noDraft))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("초안"));

        drafts.save(org, noDraft, "SELLER:" + seller, "잘 맞으셨다니 다행입니다.", 0);
        vendor.refuseNextSubmit = "task_in_progress";
        assertThatThrownBy(() -> tasks.requestApproval(org, noDraft))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("취소"));
        assertThat(vendor.submitted).isEmpty();
        assertThat(tasks.openOn(org, noDraft)).isFalse();
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

    private UUID pulledReview(String key, String reviewNo, String body) {
        UUID id = vendor.ids.computeIfAbsent(key, k -> UUID.randomUUID());
        vendor.event("NEW", new VendorServerClient.FeedReview(id, reviewNo, "SKU-합성-1", "합성 상품", 5, body,
                com.sellerops.common.ReviewBodyFingerprint.of(body), null, null,
                Instant.parse("2026-10-01T01:00:00Z"), "노출", false, null));
        pull.pullFor(org);
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
