package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageKind;
import com.sellerops.agent.quota.QuotaDecision;
import com.sellerops.attention.reply.ReviewReplyDraftService;
import com.sellerops.attention.reply.ReviewReplyTemplateKey;
import com.sellerops.attention.reply.ReviewReplyTemplateService;
import com.sellerops.attention.reply.dto.ReviewReplyDraftView;
import com.sellerops.inquiry.draft.DraftEvidenceSnippets;
import com.sellerops.inquiry.draft.DraftKnowledgeState;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever.InquiryEvidence;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage;
import com.sellerops.knowledge.KnowledgeScope;
import com.sellerops.review.Review;
import com.sellerops.review.draft.dto.GeneratedReviewDraftView;
import com.sellerops.reviewissue.ReviewIssue;
import com.sellerops.reviewissue.ReviewIssueEvidence;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import com.sellerops.reviewissue.ReviewIssueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Grounded Review Drafting v1 — the four decisions this composer makes, and the one it refuses to.
 *
 * <p>The model, the retrieval and the storage are all faked; what is under test is the rule that a
 * public reply states only what the seller can prove, and that the floor when it cannot is the
 * seller's own template rather than a fluent paragraph.
 */
class ReviewDraftComposerTest {

    private static final UUID ORG = UUID.fromString("7f3a1c9e-0000-4000-8000-000000000001");
    private static final UUID REVIEW = UUID.fromString("d1e2f3a4-0000-4000-8000-000000000006");
    private static final UUID PRODUCT = UUID.fromString("0811fead-0000-4000-8000-000000000007");
    private static final String BODY = "괜찮긴한데 잘떨어지네요 실리콘으로 붙였네요";
    private static final String TEMPLATE = "저희 제품을 이용해 주셔서 감사합니다. 남겨주신 후기 잘 읽었습니다.";

    private InquiryEvidenceRetriever retriever;
    private ReviewReplyDraftService drafts;
    private ReviewDraftEvidenceRepository evidence;
    private ReviewReplyTemplateService templates;
    private AgentDraftService model;
    private AgentQuotaService quota;
    private ReviewDraftComposer composer;
    private ReviewIssueEvidenceRepository issueEvidenceRepo;
    private ReviewIssueRepository issueRepo;

    @BeforeEach
    void setUp() {
        retriever = mock(InquiryEvidenceRetriever.class);
        drafts = mock(ReviewReplyDraftService.class);
        evidence = mock(ReviewDraftEvidenceRepository.class);
        templates = mock(ReviewReplyTemplateService.class);
        model = mock(AgentDraftService.class);
        quota = mock(AgentQuotaService.class);
        issueEvidenceRepo = mock(ReviewIssueEvidenceRepository.class);
        ReviewIssueEvidenceRepository issueEvidence = issueEvidenceRepo;
        issueRepo = mock(ReviewIssueRepository.class);
        ReviewIssueRepository issues = issueRepo;

        when(retriever.namedProduct(ORG, PRODUCT)).thenReturn(PRODUCT);
        when(templates.resolve(eq(ORG), any(ReviewReplyTemplateKey.class)))
                .thenReturn(new ReviewReplyTemplateService.Resolved(TEMPLATE, false));
        when(drafts.currentVersion(REVIEW)).thenReturn(0);
        when(drafts.saveAs(eq(ORG), eq(REVIEW), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(call -> new ReviewReplyDraftView(1, call.getArgument(3), "f".repeat(64),
                        "review-reply-v1", Instant.parse("2026-09-03T00:00:00Z")));
        when(model.isEnabledFor(ORG)).thenReturn(true);
        when(model.versionFor(ORG)).thenReturn("test-model");
        when(quota.consume(eq(ORG), eq(AgentUsageKind.DRAFT), any())).thenReturn(new QuotaDecision(true, null, 1, 200));
        when(issueEvidence.findByOrgIdAndReviewId(ORG, REVIEW)).thenReturn(List.of());

        composer = new ReviewDraftComposer(retriever, drafts, evidence, templates, model, quota,
                mock(DraftEvidenceSnippets.class), issueEvidence, issues,
                mock(com.sellerops.knowledge.candidate.KnowledgeCandidateService.class));
    }

    private static Review review() {
        Review review = new Review();
        review.setId(REVIEW);
        review.setOrgId(ORG);
        review.setProductId(PRODUCT);
        review.setRating(4);
        review.setBody(BODY);
        return review;
    }

    private void retrieval(DraftKnowledgeState state, ScopedPassage... passages) {
        // The derived constructor maps NO_LIBRARY to productOutcome ABSENT — an empty library, which is
        // what 「이 상품에 아직 지식이 없습니다」 means and what §E treats as always worth saying once.
        when(retriever.retrieveFor(eq(ORG), eq(PRODUCT), any(), any()))
                .thenReturn(new InquiryEvidence(PRODUCT, state, List.of(passages), null, 0));
    }

    private static ScopedPassage productPassage(String text) {
        return new ScopedPassage(KnowledgeScope.PRODUCT, "부착 방법", text,
                UUID.fromString("a1b2c3d4-0000-4000-8000-000000000004"),
                UUID.fromString("e5f6a7b8-0000-4000-8000-000000000005"),
                "product-knowledge/USAGE:판매자", 0.9);
    }

    private static ScopedPassage pastAnswer(String text) {
        return new ScopedPassage(KnowledgeScope.PAST_ANSWER, "리뷰 답글 · 별점 5점", text,
                UUID.fromString("a1b2c3d4-0000-4000-8000-000000000008"), null, "answer-memory", 0.8);
    }

    private static ScopedPassage policyPassage(String text) {
        return new ScopedPassage(KnowledgeScope.ORG_OPERATIONS, "배송 안내", text,
                UUID.fromString("a1b2c3d4-0000-4000-8000-000000000006"),
                UUID.fromString("e5f6a7b8-0000-4000-8000-000000000007"),
                "org-knowledge/SHIPPING_POLICY:판매자", 0.9);
    }

    /**
     * <b>The sentence a reopen shows is the sentence the generation showed</b> — Retrieval Runtime
     * Closure v1 §1.
     *
     * <p>The stored version has carried {@code answer_basis} since Grounded Review Drafting v1 and
     * nothing read it back, so 「근거 있음 / 기본 문구」 existed only in the browser session that
     * pressed the button. It is chosen HERE, by one rule, so the two renderings of one fact cannot
     * drift: a reopen that recomputed it would have to re-run the retrieval, and two of that
     * retrieval's three stages are model calls made afresh on every search.
     */
    @Test
    @DisplayName("the basis sentence is one rule, so a reopen says what the generation said")
    void theBasisSentenceIsReadBackNotRecomputed() {
        // Exactly what the generation reports, for a grounded draft with citations.
        retrieval(DraftKnowledgeState.GROUNDED, productPassage("실리콘 표면에는 부착되지 않습니다."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(java.util.Optional.of("실리콘 표면에는 부착되지 않습니다. 확인 부탁드립니다."));
        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");
        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
        assertThat(view.answerBasisNote())
                .isEqualTo(ReviewDraftComposer.basisNoteOf("GROUNDED", true))
                .isEqualTo(ReviewDraftComposer.BASIS_GROUNDED_NOTE);

        // The same rule, applied to what a read path holds: the row's basis and whether the version
        // recorded any citations.
        assertThat(ReviewDraftComposer.basisNoteOf("NO_ANSWER_BASIS", false))
                .isEqualTo(ReviewDraftComposer.BASIS_NONE_NOTE);
        // GROUNDED with no citation rows is the template floor: a model refused after the retrieval
        // succeeded, and the sentence has to say what the seller is looking at.
        assertThat(ReviewDraftComposer.basisNoteOf("GROUNDED", false))
                .isEqualTo(ReviewDraftComposer.BASIS_NONE_NOTE);
        // Not recorded is a third statement, and the screen makes none.
        assertThat(ReviewDraftComposer.basisNoteOf(null, true)).isNull();
    }

    @Test
    @DisplayName("a draft grounded in the company's policy does not then ask for a product standard")
    void groundedIsGroundedWhicheverLaneAnsweredIt() {
        // Observed live 2026-09-03: 「배송이 너무 느려서 실망했습니다」 was answered from the shipping
        // policy and the same screen asked whether a PRODUCT standard for it existed — a grounded
        // draft asking for facts it had just used, about the wrong corpus.
        retrieval(DraftKnowledgeState.GROUNDED, policyPassage("평일 오후 2시 이전 결제 건은 당일 출고됩니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.knowledgeGaps())
                .as("nothing about the product is missing that this reply needed")
                .noneMatch(g -> "PRODUCT".equals(g.scope()));
    }

    @Test
    @DisplayName("no evidence and the model writes nothing: the org's own template is the draft, and nothing is asked")
    void withoutEvidenceNothingIsInvented() {
        retrieval(DraftKnowledgeState.NO_LIBRARY);

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        // Owner decision 2026-10-26: with no evidence the model is asked for thanks and empathy only. Here it
        // answers nothing, so the floor is what is saved — exactly as before.
        verify(model).draftReviewReply(eq(ORG), eq(BODY), eq(List.of()), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any());
        assertThat(view.draft().body()).isEqualTo(TEMPLATE);
        assertThat(view.authorKind()).isEqualTo("RULE");
        assertThat(view.answerBasis()).isEqualTo("NO_ANSWER_BASIS");
        assertThat(view.evidence()).isEmpty();
        assertThat(view.templateSource()).isEqualTo("DEFAULT");

        // <b>No ask</b> (Pilot QA, 2026-09-06). This review is ★4, the issue extractor did not record
        // it (the repository above returns nothing), and the customer asked nothing — so nothing about
        // the REVIEW says an answer was owed, and an empty library is a fact about the library. The
        // draft is still the seller's own template and the screen still says what it used.
        assertThat(view.knowledgeGaps()).isEmpty();
    }

    @Test
    @DisplayName("no evidence: the model's thanks-only reply is the draft, stamped THANKS_ONLY with no citations")
    void withoutEvidenceTheModelThanks() {
        // The real-export case (2026-10-26): a ★3 「가격대비 저렴하고 좋아요」 drew the pricing template, whose floor
        // answers praise with 「더 나은 가치를 드릴 수 있도록 계속 고민하겠습니다」.
        retrieval(DraftKnowledgeState.NO_LIBRARY);
        when(model.draftReviewReply(eq(ORG), eq(BODY), eq(List.of()), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("안녕하세요, 고객님. 만족스럽게 사용해 주셔서 감사합니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.authorKind()).isEqualTo("MODEL");
        assertThat(view.answerBasis()).isEqualTo("THANKS_ONLY");
        assertThat(view.draft().body()).isEqualTo("안녕하세요, 고객님. 만족스럽게 사용해 주셔서 감사합니다.");
        assertThat(view.evidence()).isEmpty();
        assertThat(ReviewDraftComposer.basisNoteOf("THANKS_ONLY", false)).contains("감사와 공감만");
    }

    @Test
    @DisplayName("no evidence: a remedy the model offers anyway is refused and the floor is saved")
    void withoutEvidenceAPromiseIsRefused() {
        retrieval(DraftKnowledgeState.NO_LIBRARY);
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("불편을 드려 죄송합니다. 바로 교환해 드리겠습니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).isEqualTo(TEMPLATE);
        assertThat(view.authorKind()).isEqualTo("RULE");
        assertThat(view.answerBasis()).isEqualTo("NO_ANSWER_BASIS");
    }

    @Test
    @DisplayName("no evidence + the issue extractor recorded this review: the ask is filed, naming the customer's words")
    void withoutEvidenceButRecordedAsAnIssueTheAskIsFiled() {
        // The mechanism this product actually has for «a complaint the rating cannot see»: the issue
        // extractor. When it caught the review, the ask is exactly the one it always was.
        retrieval(DraftKnowledgeState.NO_LIBRARY);
        java.util.UUID issueId = java.util.UUID.randomUUID();
        ReviewIssueEvidence row = new ReviewIssueEvidence();
        row.setIssueId(issueId);
        ReviewIssue issue = new ReviewIssue();
        issue.setTitle("접착 탈락");
        when(issueEvidenceRepo.findByOrgIdAndReviewId(ORG, REVIEW)).thenReturn(List.of(row));
        when(issueRepo.findById(issueId)).thenReturn(Optional.of(issue));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.knowledgeGaps()).hasSize(1);
        assertThat(view.knowledgeGaps().get(0).scope()).isEqualTo("PRODUCT");
        // The extractor named what keeps happening, so the ask is about THAT rather than about one
        // customer's sentence — `subjectFor` has always preferred the issue title when there is one.
        assertThat(view.knowledgeGaps().get(0).subjectKind()).isEqualTo("ISSUE");
        assertThat(view.knowledgeGaps().get(0).question()).contains("접착 탈락");
        assertThat(view.knowledgeGaps().get(0).productId()).isEqualTo(PRODUCT.toString());
    }

    @Test
    @DisplayName("with evidence: the model writes inside it, the version is stamped MODEL, and the citations are stored")
    void withEvidenceTheModelWritesInsideIt() {
        retrieval(DraftKnowledgeState.GROUNDED,
                productPassage("표면의 먼지와 기름기를 닦아낸 뒤 눌러 붙여 주세요."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("후기 감사합니다. 부착 전 표면의 먼지를 닦아 주시면 더 잘 붙습니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.authorKind()).isEqualTo("MODEL");
        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
        assertThat(view.draft().body()).contains("표면의 먼지");
        assertThat(view.evidence()).hasSize(1);
        assertThat(view.evidence().get(0).kind()).isEqualTo("PRODUCT_KNOWLEDGE");
        assertThat(view.evidence().get(0).scopeLabel()).isEqualTo("상품 정보");
        assertThat(view.evidence().get(0).snippet()).contains("먼지와 기름기");
        verify(evidence).save(org.mockito.ArgumentMatchers.argThat((ReviewDraftEvidence row) ->
                ReviewDraftEvidence.fingerprintOf("표면의 먼지와 기름기를 닦아낸 뒤 눌러 붙여 주세요.")
                        .equals(row.getPassageFingerprint())));
        // The product lane answered, so there is nothing to ask for.
        assertThat(view.knowledgeGaps()).isEmpty();
    }

    @Test
    @DisplayName("a banned phrase gets one retry with the bans quoted in; the clean retry is the draft")
    void aBannedPhraseIsRetriedOnceWithTheBansInTheRequest() {
        com.sellerops.knowledge.style.AnswerStyleService styles = mock(com.sellerops.knowledge.style.AnswerStyleService.class);
        when(styles.profileFor(ORG)).thenReturn(new com.sellerops.knowledge.style.AnswerStyleProfile(null, null, null,
                null, null, null, List.of(), List.of("고객님 탓", "환불"), null, 1));
        composer.setStyleGuard(new ReviewStyleGuard(styles));
        retrieval(DraftKnowledgeState.GROUNDED,
                productPassage("표면의 먼지와 기름기를 닦아낸 뒤 눌러 붙여 주세요."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of("고객님 탓")), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("고객님 탓이 아닙니다. 표면의 먼지를 닦아 주세요."),
                        Optional.of("불편을 드려 죄송합니다. 부착 전 표면의 먼지를 닦아 주세요."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).isEqualTo("불편을 드려 죄송합니다. 부착 전 표면의 먼지를 닦아 주세요.");
        assertThat(view.authorKind()).isEqualTo("MODEL");
        assertThat(view.unavailableMessage()).isNull();
        verify(model, org.mockito.Mockito.times(2)).draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of("고객님 탓")), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any());
    }

    @Test
    @DisplayName("a banned phrase that survives the retry keeps the draft, says which phrase, and leaves approval to the gate")
    void aBannedPhraseThatStaysKeepsTheDraftAndNamesIt() {
        com.sellerops.knowledge.style.AnswerStyleService styles = mock(com.sellerops.knowledge.style.AnswerStyleService.class);
        when(styles.profileFor(ORG)).thenReturn(new com.sellerops.knowledge.style.AnswerStyleProfile(null, null, null,
                null, null, null, List.of(), List.of("고객님 탓"), null, 1));
        composer.setStyleGuard(new ReviewStyleGuard(styles));
        retrieval(DraftKnowledgeState.GROUNDED,
                productPassage("표면의 먼지와 기름기를 닦아낸 뒤 눌러 붙여 주세요."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of("고객님 탓")), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("고객님 탓이 아닙니다. 표면의 먼지를 닦아 주세요."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).as("not dropped for the template").contains("고객님 탓이 아닙니다");
        assertThat(view.unavailableMessage()).isEqualTo(ReviewDraftComposer.forbiddenLeft(List.of("고객님 탓")));
        assertThat(view.unavailableMessage()).contains("「고객님 탓」");
    }

    @Test
    @DisplayName("the retry passes the same final check as the first answer — a retry with a 【확인】 blank is not taken")
    void aRetryWithABlankIsNotTaken() {
        com.sellerops.knowledge.style.AnswerStyleService styles = mock(com.sellerops.knowledge.style.AnswerStyleService.class);
        when(styles.profileFor(ORG)).thenReturn(new com.sellerops.knowledge.style.AnswerStyleProfile(null, null, null,
                null, null, null, List.of(), List.of("고객님 탓"), null, 1));
        composer.setStyleGuard(new ReviewStyleGuard(styles));
        retrieval(DraftKnowledgeState.GROUNDED,
                productPassage("표면의 먼지와 기름기를 닦아낸 뒤 눌러 붙여 주세요."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of("고객님 탓")), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("고객님 탓이 아닙니다. 표면의 먼지를 닦아 주세요."),
                        Optional.of("불편을 드려 죄송합니다. 【확인: 부착 시간】 동안 눌러 붙여 주세요."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).doesNotContain("【확인").contains("고객님 탓이 아닙니다");
        assertThat(view.unavailableMessage()).isEqualTo(ReviewDraftComposer.forbiddenLeft(List.of("고객님 탓")));
    }

    @Test
    @DisplayName("a promise that reverses the seller's own yes or no is refused for the template")
    void aReversedPromiseFallsBackToTheTemplate() {
        retrieval(DraftKnowledgeState.GROUNDED, productPassage("단순 변심은 무료 교환이 불가합니다."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), any(), any(), any()))
                .thenReturn(Optional.of("불편을 드려 죄송합니다. 단순 변심도 무료 교환이 가능합니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).doesNotContain("무료 교환이 가능");
        assertThat(view.unavailableMessage()).isEqualTo(ReviewDraftComposer.CONTRADICTED_CLAIM);
    }

    @Test
    @DisplayName("an unsupported promise is REFUSED, not edited — the template floor is saved instead")
    void anUnsupportedPromiseFallsBackToTheTemplate() {
        retrieval(DraftKnowledgeState.GROUNDED,
                productPassage("표면의 먼지와 기름기를 닦아낸 뒤 눌러 붙여 주세요."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("불편을 드려 죄송합니다. 교환 도와드리겠습니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).isEqualTo(TEMPLATE);
        assertThat(view.authorKind()).isEqualTo("RULE");
        assertThat(view.unavailableMessage()).isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
        // Nothing was cited, because nothing the model wrote survived.
        assertThat(view.evidence()).isEmpty();
        verify(evidence, never()).save(any(ReviewDraftEvidence.class));
        // The basis is still GROUNDED: the evidence existed. What failed was the machinery, and the
        // two are different questions — the same separation the inquiry lane makes.
        assertThat(view.answerBasis()).isEqualTo("GROUNDED");
    }

    @Test
    @DisplayName("a spent budget is an operational fact, never a statement about the seller's knowledge")
    void anExhaustedBudgetFallsBackWithoutBlamingTheLibrary() {
        retrieval(DraftKnowledgeState.GROUNDED, productPassage("표면을 닦아낸 뒤 붙여 주세요."));
        when(quota.consume(eq(ORG), eq(AgentUsageKind.DRAFT), any()))
                .thenReturn(new QuotaDecision(false, QuotaDecision.Reason.DAILY_LLM_CALLS, 200, 200));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).isEqualTo(TEMPLATE);
        assertThat(view.unavailableMessage()).isNotNull();
        assertThat(view.unavailableMessage()).doesNotContain("답변 기준");
        verify(model, never()).draftReviewReply(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the org's own wording is reported as the org's, and is what a floor draft says")
    void theOrgTemplateIsReportedAsSuch() {
        retrieval(DraftKnowledgeState.NO_MATCH);
        when(templates.resolve(eq(ORG), any(ReviewReplyTemplateKey.class)))
                .thenReturn(new ReviewReplyTemplateService.Resolved("선바로입니다. 후기 감사합니다.", true));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).isEqualTo("선바로입니다. 후기 감사합니다.");
        assertThat(view.templateSource()).isEqualTo("ORG");
        assertThat(view.templateCategory()).isEqualTo("positive_reply");
    }

    @Test
    @DisplayName("a promise whose period the evidence does not state is refused like an unsupported promise")
    void aPromiseWithTermsTheEvidenceDoesNotStateFallsBackToTheTemplate() {
        retrieval(DraftKnowledgeState.GROUNDED, productPassage("수령 후 14일 이내 교환이 가능합니다."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("불편을 드려 죄송합니다. 7일 이내 교환 도와드리겠습니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).isEqualTo(TEMPLATE);
        assertThat(view.unavailableMessage()).isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);

        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("불편을 드려 죄송합니다. 14일 이내 교환 도와드리겠습니다."));
        assertThat(composer.compose(ORG, review(), BODY, "SELLER:u1").authorKind())
                .as("the period the evidence states is fine").isEqualTo("MODEL");
    }

    @Test
    @DisplayName("only a past answer: the model writes from its wording, the basis says so, and nothing is promised")
    void aPastAnswerAloneShapesTheWordingAndIsSaidToBeThat() {
        retrieval(DraftKnowledgeState.NO_LIBRARY, pastAnswer("빠른 배송과 꼼꼼한 포장을 알아봐 주셔서 감사합니다."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("꼼꼼한 포장을 알아봐 주셔서 감사합니다. 또 찾아 주세요."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.authorKind()).isEqualTo("MODEL");
        assertThat(view.answerBasis()).isEqualTo(ReviewDraftComposer.BASIS_PAST_ANSWER);
        assertThat(view.answerBasisNote()).isEqualTo(ReviewDraftComposer.BASIS_PAST_ANSWER_NOTE);
        assertThat(view.evidence()).singleElement().satisfies(e -> assertThat(e.scopeLabel()).isEqualTo("과거 답변"));
    }

    @Test
    @DisplayName("a past answer never authorizes a promise: an exchange once granted is not a policy")
    void aPastAnswerCannotAuthorizeAPromise() {
        retrieval(DraftKnowledgeState.NO_LIBRARY, pastAnswer("불편을 드려 죄송합니다. 교환해 드렸습니다."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), eq(List.of()), eq(AgentDraftGenerator.ReviewMood.UNSTATED), any()))
                .thenReturn(Optional.of("불편을 드려 죄송합니다. 교환 도와드리겠습니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).isEqualTo(TEMPLATE);
        assertThat(view.unavailableMessage()).isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
        assertThat(view.answerBasis()).as("the template was saved, and the basis says so").isEqualTo("NO_ANSWER_BASIS");

        // The same with current evidence beside it that says nothing about exchanges.
        retrieval(DraftKnowledgeState.GROUNDED, productPassage("표면의 먼지와 기름기를 닦아낸 뒤 눌러 붙여 주세요."),
                pastAnswer("불편을 드려 죄송합니다. 교환해 드렸습니다."));
        GeneratedReviewDraftView grounded = composer.compose(ORG, review(), BODY, "SELLER:u1");
        assertThat(grounded.draft().body()).isEqualTo(TEMPLATE);
        assertThat(grounded.unavailableMessage()).isEqualTo(ReviewDraftComposer.UNSUPPORTED_CLAIM);
    }

    @Test
    @DisplayName("past answers are narrowed to replies for reviews in this review's rating band")
    void thePastAnswersAreThoseOfTheSameRatingBand() {
        ReviewReplyPrecedent precedent = mock(ReviewReplyPrecedent.class);
        java.util.function.Predicate<com.sellerops.knowledge.memory.AnswerMemory> band = m -> false;
        when(precedent.forReviewRated(ORG, 4)).thenReturn(band);
        composer.setPrecedent(precedent);
        when(retriever.retrieveFor(eq(ORG), eq(PRODUCT), any(), any(), eq(band)))
                .thenReturn(new InquiryEvidence(PRODUCT, DraftKnowledgeState.NO_LIBRARY, List.of(), null, 0));

        composer.compose(ORG, review(), BODY, "SELLER:u1");

        verify(retriever).retrieveFor(eq(ORG), eq(PRODUCT), any(), any(), eq(band));
    }

    @Test
    @DisplayName("a 1–2★ review is asked for as a complaint and a reserved 3★ as mixed — the rating itself stays home")
    void theMoodFollowsTheRatingAndTheTriageSplit() {
        assertThat(ReviewDraftComposer.moodOf(1, "버튼을 누르면 컵이 두 개씩 나와요")).isEqualTo(AgentDraftGenerator.ReviewMood.COMPLAINT);
        // A 2★ that opens with praise is still a complaint: the rating is real.
        assertThat(ReviewDraftComposer.moodOf(2, "제품은 만족해요 다만 철 아닌 면에는 안 붙는 점이 아쉬워요"))
                .isEqualTo(AgentDraftGenerator.ReviewMood.COMPLAINT);
        // The 3★ split is the triage rule's: a reservation in the words is 지켜보기 there and mixed here.
        assertThat(ReviewDraftComposer.moodOf(3, "재구매 좋은데 접착력이 .. 따로 접착제 사용"))
                .isEqualTo(AgentDraftGenerator.ReviewMood.MIXED);
        assertThat(ReviewDraftComposer.moodOf(3, "디자인이 이뻐서 좋아요.")).isEqualTo(AgentDraftGenerator.ReviewMood.UNSTATED);
        assertThat(ReviewDraftComposer.moodOf(5, "너무 많이 컵이 빠져요 ㅎㅎ")).isEqualTo(AgentDraftGenerator.ReviewMood.UNSTATED);
        assertThat(ReviewDraftComposer.moodOf(null, "괜찮아요")).isEqualTo(AgentDraftGenerator.ReviewMood.UNSTATED);

        retrieval(DraftKnowledgeState.NO_LIBRARY);
        Review oneStar = review();
        oneStar.setRating(1);
        composer.compose(ORG, oneStar, BODY, "SELLER:u1");
        verify(model).draftReviewReply(eq(ORG), eq(BODY), eq(List.of()), eq(TEMPLATE), eq(List.of()),
                eq(AgentDraftGenerator.ReviewMood.COMPLAINT), any());
    }

    @Test
    @DisplayName("guidance the review names in the customer's words goes first, once, inside the budget (2026-10-27)")
    void phraseHitsGoFirstOnceAndInsideTheBudget() {
        UUID adhesion = UUID.randomUUID();
        ScopedPassage hit = new ScopedPassage(KnowledgeScope.PRODUCT, "실크벽지 부착", "폼 양면테이프를 덧대 주세요.",
                adhesion, null, "product-knowledge/USAGE:판매자", 1.0);
        ScopedPassage sameDoc = new ScopedPassage(KnowledgeScope.PRODUCT, "실크벽지 부착", "폼 양면테이프를 덧대 주세요.",
                adhesion, UUID.randomUUID(), "product-knowledge/USAGE:판매자", 0.4);
        List<ScopedPassage> scored = new java.util.ArrayList<>(List.of(sameDoc));
        for (int i = 0; i < 4; i++) {
            scored.add(new ScopedPassage(KnowledgeScope.ORG_OPERATIONS, "정책 " + i, "본문 " + i, UUID.randomUUID(),
                    UUID.randomUUID(), "org-policy/OTHER:판매자#v1", 0.3));
        }
        InquiryEvidence nothing = new InquiryEvidence(null, DraftKnowledgeState.NO_MATCH, List.of(), null, 0);
        InquiryEvidence merged = ReviewDraftComposer.withPhraseHits(nothing, List.of(hit));
        assertThat(merged.state()).as("a hit is current evidence").isEqualTo(DraftKnowledgeState.GROUNDED);
        assertThat(merged.passages()).containsExactly(hit);

        InquiryEvidence full = new InquiryEvidence(null, DraftKnowledgeState.GROUNDED, scored, null, 0);
        InquiryEvidence both = ReviewDraftComposer.withPhraseHits(full, List.of(hit));
        assertThat(both.passages()).first().isEqualTo(hit);
        assertThat(both.passages()).hasSize(InquiryEvidenceRetriever.MAX_PASSAGES)
                .filteredOn(p -> adhesion.equals(p.sourceId())).hasSize(1);
        assertThat(ReviewDraftComposer.withPhraseHits(full, List.of())).isSameAs(full);
    }
}
