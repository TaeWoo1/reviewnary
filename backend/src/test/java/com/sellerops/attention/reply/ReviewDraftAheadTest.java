package com.sellerops.attention.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.attention.VocItemRef;
import com.sellerops.attention.triage.ReviewTriage;
import com.sellerops.attention.triage.ReviewTriageAuditRepository;
import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.attention.triage.ReviewTriageService;
import com.sellerops.attention.triage.ReviewTriageWriter;
import com.sellerops.attention.triage.TriageDisposition;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewDraftComposer;
import com.sellerops.review.draft.dto.GeneratedReviewDraftView;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A review's draft written ahead, and the seller's approval of it as their 「대응 필요」</b> (product-owner,
 * 2026-10-28). Driven through the real reply service against a real (H2) database, with the model-backed composer
 * replaced by one that writes a synthetic version through the real draft service — what is under test is which reviews
 * get a draft and what approving one records, not what the model would say.
 *
 * <p>Isolation is {@code ReviewReplyServiceTest}'s and for its reason: the writers commit in their own transactions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewDraftAheadTest {

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:sellerops_review_draft_ahead;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("sellerops.seed.enabled", () -> "false");
        registry.add("sellerops.seed.channel-catalogue", () -> "false");
    }

    @Autowired ReviewReplyDraftRepository draftRepo;
    @Autowired ReviewReplyApprovalRepository approvalRepo;
    @Autowired ReviewReplyApprovalAuditRepository approvalAudits;
    @Autowired ReviewReplySubmissionRefRepository submissionRefRepo;
    @Autowired ReviewReplyOutcomeRepository outcomeRepo;
    @Autowired ReviewTriageRepository triages;
    @Autowired ReviewTriageAuditRepository triageAudits;
    @Autowired ReviewRepository reviews;
    @Autowired ProductRepository products;
    @Autowired SellerAccountRepository sellerAccounts;
    @Autowired ChannelRepository channels;
    @Autowired PlatformTransactionManager txManager;

    private ReviewReplyService service;
    private ReviewDraftComposer composer;
    private ReviewTriageService triageService;
    private ReviewReplyWorkDismissalRepository dismissals;

    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private UUID account;
    private UUID naver;

    @BeforeEach
    void setUp() {
        ReviewReplyDraftService draftService = new ReviewReplyDraftService(draftRepo);
        composer = mock(ReviewDraftComposer.class);
        when(composer.compose(eq(org), any(Review.class), anyString(), anyString())).thenAnswer(call -> {
            Review r = call.getArgument(1);
            draftService.save(org, r.getId(), call.getArgument(3), "합성-미리 쓴 답글입니다.", 0);
            return mock(GeneratedReviewDraftView.class);
        });
        ReviewTriageWriter writer = new ReviewTriageWriter(triages, triageAudits, txManager);
        service = new ReviewReplyService(reviews, products, sellerAccounts, triages, draftService,
                new ReviewReplyApprovalService(approvalRepo, approvalAudits,
                        new ReviewReplyApprovalWriter(approvalRepo, approvalAudits, txManager)),
                new ReviewReplyOutcomeService(submissionRefRepo, outcomeRepo,
                        new ReviewReplyOutcomeWriter(outcomeRepo, txManager)),
                new RuleBasedReviewReplyProvider(), com.sellerops.identity.ExecutableIdentityResolver.unresolved(),
                channels, composer, null);
        service.setTriageWriter(writer);
        dismissals = mock(ReviewReplyWorkDismissalRepository.class);
        service.setDismissals(dismissals);
        triageService = new ReviewTriageService(triages, triageAudits, reviews, sellerAccounts, writer);
        naver = channel("NAVER");
        account = account(naver);
    }

    @AfterEach
    void cleanUp() {
        outcomeRepo.deleteAll();
        submissionRefRepo.deleteAll();
        approvalAudits.deleteAll();
        approvalRepo.deleteAll();
        draftRepo.deleteAll();
        triageAudits.deleteAll();
        triages.deleteAll();
        reviews.deleteAll();
        sellerAccounts.deleteAll();
        channels.deleteAll();
    }

    @Test
    @DisplayName("an undecided review is drafted ahead; approving it records 대응 필요 as the seller, then approves")
    void anUndecidedReviewIsDraftedAheadAndApprovingItIsTheSellersJudgement() {
        Review review = review(naver, 2, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());

        assertThat(service.prepareAhead(org, review)).isPresent();
        assertThat(draftRepo.findAll()).singleElement()
                .extracting(ReviewReplyDraft::getCreatedBy).isEqualTo(ReviewReplyService.PREPARED_AHEAD_ACTOR);
        // Nobody has judged it, and the screen still offers the approval the server will allow.
        assertThat(triages.findByOrgIdAndReviewId(org, review.getId())).isEmpty();
        assertThat(service.view(org, account, ref).capabilities().canApprove()).isTrue();

        service.decideApproval(org, account, ref, "APPROVED", 1, UUID.randomUUID().toString(), user);

        ReviewTriage judged = triages.findByOrgIdAndReviewId(org, review.getId()).orElseThrow();
        assertThat(judged.getDisposition()).isEqualTo(TriageDisposition.RESPONSE_NEEDED);
        assertThat(judged.getDecidedBy()).isEqualTo("SELLER:" + user);
        assertThat(approvalRepo.findAll()).singleElement()
                .extracting(ReviewReplyApproval::getState).isEqualTo(ReviewReplyApprovalState.APPROVED);
    }

    @Test
    @DisplayName("editing a draft written ahead is also the seller's 대응 필요")
    void editingAPreparedDraftRecordsTheDecision() {
        Review review = review(naver, 3, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        service.prepareAhead(org, review);

        service.saveDraft(org, account, ref, "합성-판매자가 고친 답글입니다.", 1, user);

        assertThat(triages.findByOrgIdAndReviewId(org, review.getId())).get()
                .extracting(ReviewTriage::getDisposition).isEqualTo(TriageDisposition.RESPONSE_NEEDED);
    }

    @Test
    @DisplayName("a review the seller judged otherwise, set aside, or already answered gets no draft ahead")
    void aJudgedSetAsideOrAnsweredReviewIsNeverDraftedAhead() {
        Review watched = review(naver, 2, ReviewReplyState.PENDING);
        triageService.decide(org, account, VocItemRef.forReview(watched.getId()), TriageDisposition.MONITOR.name(),
                UUID.randomUUID().toString(), user);
        Review answered = review(naver, 1, ReviewReplyState.ANSWERED);
        Review setAside = review(naver, 2, ReviewReplyState.PENDING);
        when(dismissals.existsByOrgIdAndReviewId(org, setAside.getId())).thenReturn(true);

        assertThat(service.prepareAhead(org, watched)).isEmpty();
        assertThat(service.prepareAhead(org, answered)).isEmpty();
        assertThat(service.prepareAhead(org, setAside)).isEmpty();
        assertThat(draftRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a review that already has a draft is never drafted over — a seller's version is never stacked on")
    void aReviewWithADraftIsNotDraftedAgain() {
        Review review = review(naver, 2, ReviewReplyState.PENDING);
        triageService.decide(org, account, VocItemRef.forReview(review.getId()),
                TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(), user);
        service.saveDraft(org, account, VocItemRef.forReview(review.getId()), "합성-판매자 답글", 0, user);

        assertThat(service.prepareAhead(org, review)).isEmpty();
        assertThat(draftRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a channel where a seller cannot reply gets no draft ahead")
    void aChannelWithNoReplyFlowIsNotDraftedAhead() {
        UUID coupang = channel("COUPANG");
        Review review = review(coupang, 1, ReviewReplyState.UNKNOWN);

        assertThat(service.prepareAhead(org, review)).isEmpty();
        assertThat(draftRepo.findAll()).isEmpty();
        // …and a seller's press to the reply agent is refused there too (review 2026-10-28).
        assertThat(service.maySellerWrite(org, review)).isFalse();
    }

    @Test
    @DisplayName("a draft written ahead on a review later judged 지켜보기 is not approvable — the judgement stands")
    void aLaterJudgementOtherwiseStillRefusesApproval() {
        Review review = review(naver, 2, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        service.prepareAhead(org, review);
        triageService.decide(org, account, ref, TriageDisposition.MONITOR.name(), UUID.randomUUID().toString(), user);

        assertThatThrownBy(() -> service.decideApproval(org, account, ref, "APPROVED", 1,
                UUID.randomUUID().toString(), user)).isInstanceOf(ApiException.class);
        assertThat(approvalRepo.findAll()).isEmpty();
        assertThat(triages.findByOrgIdAndReviewId(org, review.getId())).get()
                .extracting(ReviewTriage::getDisposition).isEqualTo(TriageDisposition.MONITOR);
    }

    @Test
    @DisplayName("an earlier draft does not answer a handoff — only a person acting after it does (review 2026-10-28)")
    void anEarlierDraftDoesNotAnswerAHandoff() throws InterruptedException {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        service.prepareAhead(org, review);
        Instant handedOff = draftRepo.findAll().get(0).getCreatedAt().plusMillis(1);

        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff))
                .as("the draft written ahead before the handoff is not a person's answer").isTrue();
        assertThat(service.hasDraft(review.getId())).isTrue();

        Thread.sleep(5);
        new ReviewReplyDraftService(draftRepo).save(org, review.getId(), user.toString(), "제가 고쳐 쓴 답글입니다.", 1);
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff))
                .as("a version a person wrote after the handoff is").isFalse();
    }

    @Test
    @DisplayName("with the agent on, the seller's press runs the agent and never the old drafter (owner, 2026-10-28)")
    void theSellersPressRunsTheAgent() {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        triageService.decide(org, account, ref, TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(),
                user);
        com.sellerops.agent.reply.ReplyRunStarter agent = mock(com.sellerops.agent.reply.ReplyRunStarter.class);
        when(agent.available(org)).thenReturn(true);
        when(agent.startReviewRun(org, review.getId(), "SELLER", user)).thenReturn(java.util.Optional.of(
                new com.sellerops.agent.reply.ReplyRunStarter.Started(UUID.randomUUID(), java.util.Optional.of(
                        new com.sellerops.agent.reply.AgentRuntimeClient.RunReport("HANDOFF", "S", "CATEGORY_S", null)))));
        service.setAgentRuns(agent);

        GeneratedReviewDraftView handed = service.generateDraft(org, account, ref, user);
        assertThat(handed.draft()).isNull();
        assertThat(handed.unavailableMessage()).contains("안전 관련").contains("법정 보고");
        verify(composer, never()).compose(any(), any(), anyString(), anyString());

        GeneratedReviewDraftView saved = mock(GeneratedReviewDraftView.class);
        when(agent.startReviewRun(org, review.getId(), "SELLER", user)).thenReturn(java.util.Optional.of(
                new com.sellerops.agent.reply.ReplyRunStarter.Started(UUID.randomUUID(), java.util.Optional.of(
                        new com.sellerops.agent.reply.AgentRuntimeClient.RunReport("SAVED", "R", null, 3)))));
        when(composer.agentDraftView(org, review.getId(), 3)).thenReturn(saved);
        assertThat(service.generateDraft(org, account, ref, user)).isSameAs(saved);
        verify(composer, never()).compose(any(), any(), anyString(), anyString());

        when(agent.startReviewRun(org, review.getId(), "SELLER", user)).thenReturn(java.util.Optional.of(
                new com.sellerops.agent.reply.ReplyRunStarter.Started(UUID.randomUUID(), java.util.Optional.empty())));
        assertThat(service.generateDraft(org, account, ref, user).unavailableMessage())
                .as("a runtime that did not answer is said, not covered by the old drafter").contains("만들지 못했어요");
        verify(composer, never()).compose(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("on an install the agent is required: when it cannot run, no draft — never the old drafter's (2026-10-29)")
    void anInstallNeverFallsBackToTheOldDrafter() {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        triageService.decide(org, account, ref, TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(),
                user);
        com.sellerops.agent.reply.ReplyRunStarter agent = mock(com.sellerops.agent.reply.ReplyRunStarter.class);
        when(agent.required()).thenReturn(true);
        when(agent.available(org)).thenReturn(false);
        when(agent.startReviewRun(any(), any(), anyString(), any())).thenReturn(java.util.Optional.empty());
        service.setAgentRuns(agent);

        GeneratedReviewDraftView press = service.generateDraft(org, account, ref, user);
        assertThat(press.draft()).isNull();
        assertThat(press.unavailableMessage()).isEqualTo(ReviewReplyService.AGENT_NOT_RUNNING);

        Review untouched = review(naver, 2, ReviewReplyState.PENDING);
        assertThat(service.prepareAhead(org, untouched)).as("nothing is written ahead either").isEmpty();

        verify(composer, never()).compose(any(), any(), anyString(), anyString());
        assertThat(draftRepo.findAll()).isEmpty();
    }

    // --- fixtures -------------------------------------------------------------------    // --- fixtures -------------------------------------------------------------------

    private UUID channel(String code) {
        Channel ch = new Channel();
        ch.setCode(code);
        ch.setNameKo(code);
        ch.setStatus(ChannelStatus.AVAILABLE);
        ch.setSupportsReview(true);
        ch.setSortOrder(0);
        return channels.save(ch).getId();
    }

    private UUID account(UUID channelId) {
        SellerAccount acc = new SellerAccount();
        acc.setOrgId(org);
        acc.setChannelId(channelId);
        acc.setConnectionStatus(ChannelStatus.CONNECTED);
        acc.setFileUpload(true);
        return sellerAccounts.save(acc).getId();
    }

    private Review review(UUID channelId, int rating, ReviewReplyState state) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(channelId);
        r.setRating(rating);
        r.setBody("합성-리뷰-본문: 하루 만에 떨어졌어요");
        r.setNegative(rating <= 2);
        r.setReplyState(state);
        r.setReceivedAt(Instant.parse("2026-10-27T03:00:00Z"));
        return reviews.save(r);
    }
}
