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
@org.springframework.context.annotation.Import(com.sellerops.auth.runtoken.ReplyRunTokenService.class)
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
    @Autowired com.sellerops.auth.runtoken.ReplyAgentRunRepository agentRunRepo;
    @Autowired com.sellerops.auth.runtoken.ReplyRunTokenService runTokens;

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
        agentRunRepo.deleteAll();
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
    @DisplayName("a second press while a run of the review is open says so and starts no second run (2026-11-09)")
    void aSecondPressWhileARunIsOpenStartsNoSecondRun() {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        triageService.decide(org, account, ref, TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(),
                user);
        com.sellerops.agent.reply.ReplyRunStarter agent = mock(com.sellerops.agent.reply.ReplyRunStarter.class);
        when(agent.available(org)).thenReturn(true);
        when(agent.running(org, review.getId())).thenReturn(true);
        service.setAgentRuns(agent);

        GeneratedReviewDraftView press = service.generateDraft(org, account, ref, user);

        assertThat(press.draft()).isNull();
        assertThat(press.unavailableMessage()).isEqualTo(ReviewReplyService.AGENT_STILL_RUNNING);
        verify(agent, never()).startReviewRun(any(), any(), anyString(), any());
        verify(composer, never()).compose(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a run is open while it is unfinished and its token unexpired — and only for its own review (2026-11-09)")
    void aRunIsOpenOnlyWhileUnfinishedAndUnexpired() {
        UUID reviewId = review(naver, 1, ReviewReplyState.PENDING).getId();
        com.sellerops.agent.reply.ReplyAgentService replyAgent = mock(com.sellerops.agent.reply.ReplyAgentService.class);
        when(replyAgent.isEnabledFor(org)).thenReturn(true);
        java.util.concurrent.atomic.AtomicInteger runtimeCalls = new java.util.concurrent.atomic.AtomicInteger();
        com.sellerops.agent.reply.ReplyRunStarter starter = new com.sellerops.agent.reply.ReplyRunStarter(runTokens,
                countingRuntime(runtimeCalls), replyAgent, agentRunRepo);
        assertThat(starter.running(org, reviewId)).isFalse();

        com.sellerops.auth.runtoken.ReplyAgentRun open = run(reviewId, java.time.Instant.now().plusSeconds(300));
        assertThat(starter.running(org, reviewId)).isTrue();
        assertThat(starter.running(org, UUID.randomUUID())).as("another review").isFalse();
        assertThat(starter.running(UUID.randomUUID(), reviewId)).as("another org").isFalse();
        assertThat(starter.startReviewRun(org, reviewId, "SELLER", user)).isEmpty();
        assertThat(runtimeCalls.get()).as("refused before the runtime is called").isZero();
        assertThat(agentRunRepo.count()).as("and before a run is minted").isEqualTo(1);

        open.setFinishedAt(java.time.Instant.now());
        open.setOutcome("SAVED");
        agentRunRepo.save(open);
        assertThat(starter.running(org, reviewId)).as("finished").isFalse();

        run(reviewId, java.time.Instant.now().minusSeconds(1));
        assertThat(starter.running(org, reviewId)).as("expired token, never finished").isFalse();

        // Nothing open any more: the next press starts exactly one run, and closes it.
        assertThat(starter.startReviewRun(org, reviewId, "SELLER", user)).isPresent();
        assertThat(runtimeCalls.get()).isEqualTo(1);
        assertThat(starter.running(org, reviewId)).as("closed after the runtime returned").isFalse();
    }

    @Test
    @DisplayName("a review that is not this org's starts no run — the lock finds no row and fails closed (2026-11-09)")
    void aReviewOfAnotherOrgStartsNoRun() {
        UUID reviewId = review(naver, 1, ReviewReplyState.PENDING).getId();
        UUID otherOrg = UUID.randomUUID();
        com.sellerops.agent.reply.ReplyAgentService replyAgent = mock(com.sellerops.agent.reply.ReplyAgentService.class);
        when(replyAgent.isEnabledFor(otherOrg)).thenReturn(true);
        java.util.concurrent.atomic.AtomicInteger runtimeCalls = new java.util.concurrent.atomic.AtomicInteger();
        com.sellerops.agent.reply.ReplyRunStarter starter = new com.sellerops.agent.reply.ReplyRunStarter(runTokens,
                countingRuntime(runtimeCalls), replyAgent, agentRunRepo);

        assertThat(starter.startReviewRun(otherOrg, reviewId, "SELLER", user)).isEmpty();
        assertThat(runtimeCalls.get()).isZero();
        assertThat(agentRunRepo.count()).isZero();
    }

    @Test
    @DisplayName("the template saved as it came is the template's, not the seller's; the first changed word is theirs (2026-11-09)")
    void anUnchangedSuggestionIsSavedAsTheTemplate() {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        triageService.decide(org, account, ref, TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(),
                user);
        String suggestion = service.view(org, account, ref).suggestion().body();

        service.saveDraft(org, account, ref, suggestion, 0, user);
        assertThat(service.view(org, account, ref).draftAuthorKind()).isEqualTo("RULE");

        service.saveDraft(org, account, ref, suggestion + " 합성-판매자가 한 문장 더했어요.", 1, user);
        assertThat(service.view(org, account, ref).draftAuthorKind()).isEqualTo("SELLER");
    }

    @Test
    @DisplayName("a handoff stays waiting when only the template was loaded and auto-saved — the seller's edit ends it (§B127)")
    void aLoadedTemplateDoesNotAnswerAHandoff() throws InterruptedException {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        triageService.decide(org, account, ref, TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(),
                user);
        Instant handedOff = Instant.now();
        Thread.sleep(5);
        String template = service.view(org, account, ref).suggestion().body();

        // 「기본 문구 불러오기」 then the editor's auto-save: the seller pressed, but the words are the template's.
        service.saveDraft(org, account, ref, template, 0, user);
        assertThat(draftRepo.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getCreatedBy()).as("the seller pressed save").startsWith("SELLER:");
            assertThat(d.getAuthorKind()).isEqualTo("RULE");
        });
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff))
                .as("an untouched template saved from the seller's press is not a person's answer").isTrue();

        // One word changed is the seller's sentence.
        service.saveDraft(org, account, ref, template + " 합성-판매자가 고친 문장.", 1, user);
        assertThat(draftRepo.findAll()).extracting(d -> d.getAuthorKind()).containsExactlyInAnyOrder("RULE", "SELLER");
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff)).as("the seller edited it").isFalse();
    }

    @Test
    @DisplayName("an AI draft the seller asked for still answers a handoff, recorded as MODEL — unchanged by §B127")
    void aSellerRequestedModelDraftAnswersAHandoffAsBefore() throws InterruptedException {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        Instant handedOff = Instant.now();
        Thread.sleep(5);

        // What the reply agent's save step writes for a seller's press (ReplyRunService: actor SELLER:<user>).
        new ReviewReplyDraftService(draftRepo).saveAs(org, review.getId(), "SELLER:" + user, "합성-모델 초안 문장입니다.", 0,
                new ReviewReplyDraftService.Provenance("MODEL", "fake/v1", null, null, null));

        assertThat(draftRepo.findAll()).singleElement().extracting(d -> d.getAuthorKind())
                .as("the AI draft keeps its own author — never relabelled SELLER").isEqualTo("MODEL");
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff)).as("as before §B127").isFalse();
    }

    @Test
    @DisplayName("a draft the system wrote after a handoff — model or template — does not answer it, as before (§B127)")
    void aSystemDraftAfterAHandoffDoesNotAnswerIt() throws InterruptedException {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        Instant handedOff = Instant.now();
        Thread.sleep(5);
        ReviewReplyDraftService drafts = new ReviewReplyDraftService(draftRepo);

        drafts.saveAs(org, review.getId(), ReviewReplyService.PREPARED_AHEAD_ACTOR, "합성-자동 실행 초안입니다.", 0,
                new ReviewReplyDraftService.Provenance("MODEL", "fake/v1", null, null, null));
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff)).as("the automatic run's model text").isTrue();

        drafts.saveAs(org, review.getId(), ReviewReplyService.PREPARED_AHEAD_ACTOR, "합성-자동 기본 문구입니다.", 1,
                new ReviewReplyDraftService.Provenance("RULE", "template/v1", null, null, null));
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff)).as("the system's template").isTrue();
    }

    @Test
    @DisplayName("approving the untouched template still ends the wait — a person decided (§B127)")
    void approvingTheTemplateAnswersAHandoff() throws InterruptedException {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        triageService.decide(org, account, ref, TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(),
                user);
        Instant handedOff = Instant.now();
        Thread.sleep(5);
        service.saveDraft(org, account, ref, service.view(org, account, ref).suggestion().body(), 0, user);
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff)).isTrue();

        service.decideApproval(org, account, ref, "APPROVED", 1, UUID.randomUUID().toString(), user);
        assertThat(service.stillWaitingAfterHandoff(org, review, handedOff)).as("the seller approved it").isFalse();
    }

    @Test
    @DisplayName("the panel reads why the latest run handed the review to a person — and nothing once a later run saved (2026-11-09)")
    void thePrepCarriesTheLatestHandoffReason() throws InterruptedException {
        Review review = review(naver, 1, ReviewReplyState.PENDING);
        String ref = VocItemRef.forReview(review.getId());
        triageService.decide(org, account, ref, TriageDisposition.RESPONSE_NEEDED.name(), UUID.randomUUID().toString(),
                user);
        com.sellerops.agent.reply.ReplyAgentService replyAgent = mock(com.sellerops.agent.reply.ReplyAgentService.class);
        service.setAgentRuns(new com.sellerops.agent.reply.ReplyRunStarter(runTokens,
                new com.sellerops.agent.reply.AgentRuntimeClient(""), replyAgent, agentRunRepo));
        assertThat(service.view(org, account, ref).agentHandoffReason()).as("no run yet").isNull();

        com.sellerops.auth.runtoken.ReplyAgentRun handed = run(review.getId(), java.time.Instant.now().plusSeconds(300));
        handed.setFinishedAt(java.time.Instant.now());
        handed.setOutcome("HANDOFF");
        handed.setHandoffReason("CATEGORY_S");
        agentRunRepo.save(handed);
        assertThat(service.view(org, account, ref).agentHandoffReason()).isEqualTo("CATEGORY_S");
        assertThat(service.view(org, account, ref).draft()).as("handing off writes no draft").isNull();
        Thread.sleep(20); // the later run is later by its own created_at

        com.sellerops.auth.runtoken.ReplyAgentRun later = run(review.getId(), java.time.Instant.now().plusSeconds(300));
        later.setFinishedAt(java.time.Instant.now());
        later.setOutcome("SAVED");
        agentRunRepo.save(later);
        assertThat(service.view(org, account, ref).agentHandoffReason()).as("a later run saved").isNull();
    }

    /** A runtime that is configured, answers SAVED and counts its calls — no model, no network. */
    private static com.sellerops.agent.reply.AgentRuntimeClient countingRuntime(
            java.util.concurrent.atomic.AtomicInteger calls) {
        return new com.sellerops.agent.reply.AgentRuntimeClient("http://127.0.0.1:1") {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public java.util.Optional<RunReport> runReply(UUID runId, String token) {
                calls.incrementAndGet();
                return java.util.Optional.empty();
            }
        };
    }

    private com.sellerops.auth.runtoken.ReplyAgentRun run(UUID reviewId, java.time.Instant expiresAt) {
        com.sellerops.auth.runtoken.ReplyAgentRun run = new com.sellerops.auth.runtoken.ReplyAgentRun();
        run.setOrgId(org);
        run.setSubjectKind("REVIEW");
        run.setSubjectId(reviewId);
        run.setTriggerKind("SELLER");
        run.setTokenHash("test-" + UUID.randomUUID());
        run.setExpiresAt(expiresAt);
        return agentRunRepo.save(run);
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
