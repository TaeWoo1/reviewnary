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
    private com.sellerops.knowledge.candidate.KnowledgeCandidateService gapInbox;

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
        when(quota.consume(eq(ORG), eq(AgentUsageKind.DRAFT), any(), any())).thenReturn(new QuotaDecision(true, null, 1, 200));
        when(issueEvidence.findByOrgIdAndReviewId(ORG, REVIEW)).thenReturn(List.of());

        gapInbox = mock(com.sellerops.knowledge.candidate.KnowledgeCandidateService.class);
        composer = new ReviewDraftComposer(retriever, drafts, evidence, templates, model, quota,
                mock(DraftEvidenceSnippets.class), issueEvidence, issues, gapInbox);
    }

    private static ReviewDraftComposer.AgentInputs inputs(String... policy) {
        return new ReviewDraftComposer.AgentInputs("하루 만에 떨어졌어요", List.of(), List.of(policy), null, null, List.of(), null,
                "GROUNDED", List.of());
    }

    private List<String> findings(String draft, ReviewDraftComposer.AgentInputs in) {
        return composer.agentFindings(ORG, draft, in, 200).stream().map(ReviewDraftComposer.AgentCheck::id).toList();
    }

    @Test
    @DisplayName("the agent's draft is checked by code for what a model must not be trusted alone with (review 2026-10-28)")
    void theAgentsDraftIsCheckedByCode() {
        assertThat(findings("불편을 드려 죄송합니다. 문의로 알려 주시면 확인하겠습니다.", inputs())).isEmpty();
        assertThat(findings("010-1234-5678로 연락 주세요.", inputs())).contains("N2");
        assertThat(findings("자세한 내용은 https://example.com 에서 확인해 주세요.", inputs())).contains("N2");
        assertThat(findings("리뷰를 수정해 주시면 적립금을 드리겠습니다.", inputs())).contains("N3");
        assertThat(findings("허위 리뷰로 보여 신고하겠습니다.", inputs())).contains("N7");
        assertThat(findings("가".repeat(201), inputs())).as("the channel's limit, not the style rule F1")
                .contains("CHANNEL_LENGTH_EXCEEDED").doesNotContain("F1");
        assertThat(findings(" ", inputs())).containsExactly("BLANK");
    }

    @Test
    @DisplayName("an N9 note names what was refused, so the revision knows what to take out (2026-10-31)")
    void anN9NoteNamesTheRefusedWords() {
        List<ReviewDraftComposer.AgentCheck> checks = composer.agentFindings(ORG,
                "불편을 드려 죄송합니다. 원하시면 반품 도와드리겠습니다.", inputs(), 200);
        assertThat(checks).filteredOn(c -> "N9".equals(c.id())).singleElement()
                .satisfies(c -> assertThat(c.note()).startsWith("「반품」은 등록된 정책 근거가 없어"));
        assertThat(composer.agentFindings(ORG, "죄송합니다. 확인 후 연락드리겠습니다.", inputs(), 200))
                .filteredOn(c -> "N9".equals(c.id())).singleElement()
                .satisfies(c -> assertThat(c.note()).startsWith("「확인후연락」 같은 나중 약속은")
                        .doesNotContain("(문의 · 교환 신청)"));
    }

    @Test
    @DisplayName("a follow-up the seller never set is a promise (N9); the seller's own wording authorizes it for this check")
    void anUnagreedFollowUpIsAPromise() {
        assertThat(findings("불편을 드려 죄송합니다. 확인 후 연락드리겠습니다.", inputs())).contains("N9");
        assertThat(findings("죄송합니다. 확인해서 안내드리겠습니다.", inputs())).contains("N9");
        // The seller's own wording authorizes the follow-up for this check (the older promise check may still refuse the
        // sentence on its own grounds — that stays the stricter of the two).
        assertThat(com.sellerops.inquiry.draft.InquiryClaimGuard.unauthorizedFollowUps("확인 후 연락드리겠습니다.",
                List.of("파손 문의는 사진을 확인 후 연락드리겠습니다."))).isEmpty();
        assertThat(com.sellerops.inquiry.draft.InquiryClaimGuard.unauthorizedFollowUps("확인 후 연락드리겠습니다.",
                List.of("교환은 문의 게시판으로 접수합니다."))).isNotEmpty();
    }

    @Test
    @DisplayName("「opened ⇒ no return」 fails even when the seller's own policy says it — the law's minimum is not wording")
    void theReturnLawIsNotLoweredByPolicyText() {
        ReviewDraftComposer.AgentInputs policy = inputs("개봉 시 반품 불가합니다. 3일 이내 교환만 가능합니다.");
        assertThat(findings("죄송합니다. 개봉 시 반품이 불가합니다.", policy)).contains("N8");
        assertThat(findings("3일 이내에 교환 신청해 주세요.", policy)).contains("N8");
        assertThat(findings("수령 후 7일 이내에 문의로 교환을 신청하실 수 있습니다.", policy)).doesNotContain("N8");
    }

    @Test
    @DisplayName("a review that no longer asks closes the ask an earlier draft of it filed (2026-10-28)")
    void aReviewThatNoLongerAsksWithdrawsItsOwnAsk() {
        // An ask an older rule raised for a compliment stayed open and was counted beside every review of the product.
        retrieval(DraftKnowledgeState.NO_LIBRARY);
        composer.compose(ORG, review(), BODY, "SELLER:u1");

        String question = ReviewDraftComposer.productQuestion(ReviewDraftComposer.excerpt(BODY));
        org.mockito.Mockito.verify(gapInbox).withdrawGap(ORG, "PRODUCT", PRODUCT, question);
        org.mockito.Mockito.verify(gapInbox, org.mockito.Mockito.never())
                .noteGap(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a ★3 whose words only praise is a warmer thank-you and asks the seller for nothing (owner, 2026-10-28)")
    void aThreeStarPraiseIsAWarmerThankYouAndAsksNothing() {
        retrieval(DraftKnowledgeState.NO_LIBRARY);
        Review review = review();
        review.setRating(3);
        review.setBody("디자인이 이뻐서 좋아요.");
        when(model.draftReviewReply(eq(ORG), eq("디자인이 이뻐서 좋아요."), any(), eq(TEMPLATE), eq(List.of()),
                eq(AgentDraftGenerator.ReviewMood.MILD_PRAISE), any()))
                .thenReturn(Optional.of("안녕하세요, 고객님. 디자인을 마음에 들어 해 주셔서 정말 감사합니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review, "디자인이 이뻐서 좋아요.", "SELLER:u1");

        assertThat(view.knowledgeGaps()).isEmpty();
        assertThat(view.draft().body()).contains("정말 감사합니다");
        org.mockito.Mockito.verify(gapInbox, org.mockito.Mockito.never())
                .noteGap(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a ★3 with a reservation still asks — the exception is praise, not the number")
    void aThreeStarWithAReservationStillAsks() {
        retrieval(DraftKnowledgeState.NO_LIBRARY);
        Review review = review();
        review.setRating(3);
        review.setBody("재구매 좋은데 접착력이 .. 따로 접착제 사용");

        GeneratedReviewDraftView view = composer.compose(ORG, review, "재구매 좋은데 접착력이 .. 따로 접착제 사용", "SELLER:u1");

        assertThat(view.knowledgeGaps()).extracting(com.sellerops.review.draft.dto.ReviewKnowledgeGapView::scope).containsExactly("PRODUCT");
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
    @DisplayName("a promise the evidence answers both ways, with no case named, is left to the seller")
    void anUndecidedPromiseFallsBackToTheTemplate() {
        retrieval(DraftKnowledgeState.GROUNDED, productPassage("7일 이내 교환 가능. 14일 이후 교환 불가."));
        when(model.draftReviewReply(eq(ORG), eq(BODY), any(), eq(TEMPLATE), any(), any(), any()))
                .thenReturn(Optional.of("불편을 드려 죄송합니다. 교환해 드리겠습니다."));

        GeneratedReviewDraftView view = composer.compose(ORG, review(), BODY, "SELLER:u1");

        assertThat(view.draft().body()).doesNotContain("교환해 드리겠습니다");
        assertThat(view.unavailableMessage()).isEqualTo(ReviewDraftComposer.UNDECIDED_CLAIM);
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
        when(quota.consume(eq(ORG), eq(AgentUsageKind.DRAFT), any(), any()))
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
        assertThat(ReviewDraftComposer.moodOf(3, "디자인이 이뻐서 좋아요.")).isEqualTo(AgentDraftGenerator.ReviewMood.MILD_PRAISE);
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

    @Test
    @DisplayName("a guidance the seller refused for this review is dropped from the scorer's passages too")
    void aRefusedSourceLeavesTheScorersPassages() {
        UUID refused = UUID.randomUUID();
        ScopedPassage scored = new ScopedPassage(KnowledgeScope.PRODUCT, "실크벽지 부착", "폼 양면테이프를 덧대 주세요.",
                refused, UUID.randomUUID(), "product-knowledge/USAGE:판매자", 0.4);
        ScopedPassage past = new ScopedPassage(KnowledgeScope.PAST_ANSWER, "지난 답변", "감사합니다.", null,
                UUID.randomUUID(), "answer-memory", 0.3);
        InquiryEvidence found = new InquiryEvidence(null, DraftKnowledgeState.GROUNDED, List.of(scored, past), null, 0);

        InquiryEvidence kept = ReviewDraftComposer.withoutSources(found, java.util.Set.of(refused));

        assertThat(kept.passages()).containsExactly(past);
        assertThat(kept.state()).as("no current passage is left").isEqualTo(DraftKnowledgeState.NO_MATCH);
        assertThat(ReviewDraftComposer.withoutSources(found, java.util.Set.of())).isSameAs(found);
    }

    @Test
    @DisplayName("a passage only the customer's words attached reaches the model marked as a candidate")
    void anUnconfirmedPhraseHitIsMarkedACandidate() {
        UUID hit = UUID.randomUUID();
        ScopedPassage phrase = new ScopedPassage(KnowledgeScope.PRODUCT, "실크벽지 부착", "폼 양면테이프를 덧대 주세요.",
                hit, null, "product-knowledge/USAGE:판매자", 1.0);
        ScopedPassage chosen = new ScopedPassage(KnowledgeScope.PRODUCT, "자르기", "여러 번 그어 자르세요.",
                UUID.randomUUID(), null, "product-knowledge/USAGE:판매자", 1.0);

        List<AgentDraftGenerator.Passage> shown = ReviewDraftComposer.passagesFor(List.of(phrase, chosen),
                java.util.Set.of(hit));

        assertThat(shown.get(0).scopeLabel()).isEqualTo(KnowledgeScope.PRODUCT.labelKo() + " · 후보");
        assertThat(shown.get(1).scopeLabel()).isEqualTo(KnowledgeScope.PRODUCT.labelKo());
    }

    @Test
    @DisplayName("the reply agent's bundle: the product's knowledge in use and the policies in use, this org's only")
    void theBundleCarriesTheProductAndPolicies() {
        var products = mock(com.sellerops.product.library.ProductKnowledgeSourceRepository.class);
        var productChunks = mock(com.sellerops.product.library.ProductKnowledgeChunkRepository.class);
        var policies = mock(com.sellerops.knowledge.org.OrgKnowledgeSourceRepository.class);
        var policyChunks = mock(com.sellerops.knowledge.org.OrgKnowledgeChunkRepository.class);
        composer.setBundleSources(products, productChunks, policies, policyChunks);
        var guide = new com.sellerops.product.library.ProductKnowledgeSource();
        guide.setId(UUID.randomUUID());
        guide.setTitle("부착이 잘 떨어질 때 안내");
        guide.setActive(true);
        var retired = new com.sellerops.product.library.ProductKnowledgeSource();
        retired.setId(UUID.randomUUID());
        retired.setActive(false);
        when(products.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(ORG, PRODUCT)).thenReturn(List.of(guide, retired));
        var chunk = new com.sellerops.product.library.ProductKnowledgeChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setOrgId(ORG);
        chunk.setSourceId(guide.getId());
        chunk.setContent("부착면을 마른 천으로 닦고 30초 이상 눌러 고정해 주세요.");
        when(productChunks.findAllBySourceId(guide.getId())).thenReturn(List.of(chunk));
        var otherOrgs = new com.sellerops.product.library.ProductKnowledgeChunk();
        otherOrgs.setId(UUID.randomUUID());
        otherOrgs.setOrgId(UUID.randomUUID());
        otherOrgs.setSourceId(guide.getId());
        otherOrgs.setContent("다른 가게의 안내입니다.");
        when(productChunks.findAllBySourceId(retired.getId())).thenReturn(List.of(otherOrgs));
        var rule = new com.sellerops.knowledge.org.OrgKnowledgeSource();
        rule.setId(UUID.randomUUID());
        rule.setTitle("교환 안내");
        rule.setActive(true);
        when(policies.findAllByOrgIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(rule));
        var ruleChunk = new com.sellerops.knowledge.org.OrgKnowledgeChunk();
        ruleChunk.setId(UUID.randomUUID());
        ruleChunk.setOrgId(ORG);
        ruleChunk.setSourceId(rule.getId());
        ruleChunk.setContent("수령 후 7일 이내, 개봉하지 않은 상품에 한해 교환 가능합니다.");
        when(policyChunks.findAllBySourceId(rule.getId())).thenReturn(List.of(ruleChunk));
        when(retriever.retrieveFor(eq(ORG), eq(PRODUCT), any(), any(), any())).thenReturn(nothingFound());
        when(retriever.retrieveFor(eq(ORG), eq(PRODUCT), any(), any())).thenReturn(nothingFound());

        Review complaint = review(2, BODY);
        assertThat(composer.agentInputs(ORG, complaint).bundle()).extracting(i -> i.passage().text())
                .containsExactly(chunk.getContent(), ruleChunk.getContent());
        assertThat(composer.agentInputs(ORG, complaint).passages()).extracting(
                com.sellerops.agent.llm.AgentDraftGenerator.Passage::scopeLabel).containsExactly("근거 1 · 상품 정보",
                "근거 2 · 운영 정책");

        // Whether praise needs the facts is the judgement's, read in the run (ReplyRunService#offered), not a word list's.
        Review praise = review(5, "잘 붙어요 좋아요");
        assertThat(composer.agentInputs(ORG, praise).bundle()).isNotEmpty();
    }

    private static Review review(int rating, String body) {
        Review r = new Review();
        r.setId(REVIEW);
        r.setOrgId(ORG);
        r.setProductId(PRODUCT);
        r.setRating(rating);
        r.setBody(body);
        return r;
    }

    private static InquiryEvidenceRetriever.InquiryEvidence nothingFound() {
        return new InquiryEvidenceRetriever.InquiryEvidence(PRODUCT, com.sellerops.inquiry.draft.DraftKnowledgeState.NO_MATCH,
                List.of(), com.sellerops.order.fact.OrderFact.unavailable(
                        com.sellerops.order.fact.OrderFactState.NO_ORDER_REFERENCE, null, null), 0);
    }
}
