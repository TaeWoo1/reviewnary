package com.sellerops.agent.reply;

import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.attention.reply.dto.ReviewReplyDraftView;
import com.sellerops.auth.runtoken.ReplyAgentRunRepository;
import com.sellerops.auth.runtoken.ReplyRunTokenService;
import com.sellerops.common.ApiException;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewDraftComposer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>One reply-agent run, step by step, on its own review</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md} §1):
 * judge → draft → critique → one revision → critique → save. The model is a stub; what is under test is the run's
 * state between steps, the one-revision limit, the gates before a save, and that a finished run does nothing more.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReplyRunServiceTest {

    @Autowired ReplyAgentRunRepository runs;
    @Autowired OrganizationRepository organizations;

    private ReplyRunTokenService tokens;
    private ReviewRepository reviews;
    private ReviewDraftComposer composer;
    private ReviewReplyService replies;
    private ReplyAgentService agent;
    private ReplyRunService service;
    private UUID org;
    private Review review;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("합성 판매자");
        org = organizations.save(o).getId();
        review = new Review();
        review.setId(UUID.randomUUID());
        review.setOrgId(org);
        review.setRating(2);
        reviews = mock(ReviewRepository.class);
        when(reviews.findByIdAndOrgId(review.getId(), org)).thenReturn(Optional.of(review));
        composer = mock(ReviewDraftComposer.class);
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("하루 만에 떨어졌어요",
                List.of(), List.of(), "안녕하세요", null, List.of(), null, "NO_MATCH", List.of()));
        when(composer.agentFindings(eq(org), anyString(), any(), anyInt())).thenReturn(List.of());
        when(composer.saveAgentDraft(eq(org), eq(review), anyString(), any(), anyString(), any()))
                .thenReturn(new ReviewReplyDraftView(1, "x", "f".repeat(64), "review-reply-v1", Instant.now()));
        replies = mock(ReviewReplyService.class);
        when(replies.mayWriteAhead(org, review)).thenReturn(true);
        agent = mock(ReplyAgentService.class);
        when(agent.standard()).thenReturn(new ReplyStandard());
        when(agent.version()).thenReturn("reply-agent/test");
        tokens = new ReplyRunTokenService(runs);
        service = new ReplyRunService(runs, tokens, reviews, composer, replies, agent, null);
    }

    private static ReplyAgentService.DraftAnswer answer(String comments) {
        return new ReplyAgentService.DraftAnswer(comments, List.of(), false);
    }

    private static ReplyAgentService.Classified judged(String category) {
        return new ReplyAgentService.Classified(category, List.of(), List.of(), false);
    }

    private UUID run(String kind, String trigger) {
        return tokens.mint(org, kind, review.getId(), trigger).runId();
    }

    @Test
    @DisplayName("judge, draft, a failed critique, one revision with the failed rules, a passing critique, save")
    void aRunGoesThroughItsSteps() {
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(eq(org), eq(run.toString()), eq("INGEST"), anyString())).thenReturn(Optional.of(judged("R")));
        when(agent.write(eq(org), eq(run.toString()), eq("INGEST"), any(), anyString(), any(), any(), any(), any(), any(),
                isNull(), any(), isNull())).thenReturn(Optional.of(answer("불편을 드려 죄송합니다. 테이프를 써 보세요.")));
        when(agent.write(eq(org), eq(run.toString()), eq("INGEST"), any(), anyString(), any(), any(), any(), any(), any(),
                notNull(), eq(List.of("G4")), anyString())).thenReturn(Optional.of(answer("하루 만에 떨어졌다니 죄송합니다. 문의로 교환을 도와드리겠습니다.")));
        when(agent.critique(eq(org), eq(run.toString()), eq("INGEST"), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(Optional.of(List.of(new ReplyAgentService.Failed("G4", "이미 쓴 테이프를 다시 권하지 마세요."))))
                .thenReturn(Optional.of(List.of()));

        ReplyRunService.Judged judged = service.classify(org, run);
        assertThat(judged).isEqualTo(new ReplyRunService.Judged("R", true, false));
        assertThat(service.draft(org, run, false).written()).isTrue();
        assertThat(service.critique(org, run)).isEqualTo(new ReplyRunService.Critiqued(false, List.of("G4")));
        assertThat(service.draft(org, run, true)).isEqualTo(new ReplyRunService.Drafted(true, 1));
        // One revision only: a draft that still fails after it is a person's.
        assertThat(service.draft(org, run, true).written()).isFalse();
        assertThat(service.critique(org, run).passed()).isTrue();
        assertThat(service.save(org, run)).isEqualTo(new ReplyRunService.Saved(true, 1, null));
        verify(composer).saveAgentDraft(eq(org), eq(review), eq("하루 만에 떨어졌다니 죄송합니다. 문의로 교환을 도와드리겠습니다."),
                any(), eq(ReviewReplyService.PREPARED_AHEAD_ACTOR), eq("reply-agent/test"));

        service.finish(org, run, "SAVED", null);
        assertThat(tokens.authenticate("rvr_x")).isEmpty();
        assertThatThrownBy(() -> service.classify(org, run)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("what the writer cites from the bundle, verified, is the draft's whole ground for the critique and the save")
    void theCitedPassagesAreTheGroundFromTheWriterOn() {
        var exchange = new com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage(
                com.sellerops.knowledge.KnowledgeScope.ORG_OPERATIONS, "교환 안내",
                "수령 후 7일 이내, 개봉하지 않은 상품에 한해 교환 가능합니다.", UUID.randomUUID(), UUID.randomUUID(), null, 0);
        var care = new com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage(
                com.sellerops.knowledge.KnowledgeScope.PRODUCT, "관리 방법", "중성세제로 손세탁해 주세요.", UUID.randomUUID(),
                UUID.randomUUID(), null, 0);
        List<com.sellerops.review.draft.ReviewEvidenceBundle.Item> bundle =
                com.sellerops.review.draft.ReviewEvidenceBundle.build(List.of(), List.of(care), List.of(exchange));
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("사이즈가 안 맞아요",
                bundle.stream().map(i -> new com.sellerops.agent.llm.AgentDraftGenerator.Passage(i.id(), "", i.passage().text()))
                        .toList(), List.of(), "안녕하세요", null, List.of(), null, "GROUNDED", List.of(), bundle));
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(eq(org), eq(run.toString()), eq("INGEST"), anyString())).thenReturn(Optional.of(judged("R")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.DraftAnswer(
                        "개봉하지 않은 상품이라면 수령 후 7일 이내 교환을 신청하실 수 있어요.", List.of(), false, List.of(
                                new com.sellerops.review.draft.ReviewEvidenceBundle.Used("근거 2",
                                        "수령 후 7일 이내, 개봉하지 않은 상품에 한해 교환 가능합니다."),
                                new com.sellerops.review.draft.ReviewEvidenceBundle.Used("근거 1", "세탁기 사용 가능")))));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));

        service.classify(org, run);
        service.draft(org, run, false);
        ReplyRunService.Critiqued critiqued = service.critique(org, run);
        assertThat(critiqued.failed()).as("a citation not in the bundle as quoted").contains("CITATION_UNVERIFIED");

        ArgumentCaptor<ReviewDraftComposer.AgentInputs> checked =
                ArgumentCaptor.forClass(ReviewDraftComposer.AgentInputs.class);
        verify(composer).agentFindings(eq(org), anyString(), checked.capture(), anyInt());
        assertThat(checked.getValue().currentTexts()).containsExactly(exchange.text());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.sellerops.agent.llm.AgentDraftGenerator.Passage>> shown =
                ArgumentCaptor.forClass(List.class);
        verify(agent).critique(any(), any(), any(), any(), any(), any(), shown.capture(), any(), any());
        assertThat(shown.getValue()).extracting(com.sellerops.agent.llm.AgentDraftGenerator.Passage::text)
                .containsExactly(exchange.text());
        assertThat(ReplyRunService.groundOf(composer.agentInputs(org, review, List.of()), runs.findById(run).orElseThrow()).cited())
                .as("what a save cites").containsExactly(exchange);
    }

    @Test
    @DisplayName("a critique that could not run is not a pass, and a gate closed in the meantime saves nothing")
    void noCritiqueIsNoPassAndAClosedGateSavesNothing() {
        UUID run = run("REVIEW", "SCHEDULED");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("P")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("디자인을 마음에 들어 해 주셔서 감사합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        service.classify(org, run);
        service.draft(org, run, false);
        assertThat(service.critique(org, run).failed()).containsExactly("CRITIQUE_UNAVAILABLE");
        assertThat(service.save(org, run)).as("no critique passed this draft")
                .isEqualTo(new ReplyRunService.Saved(false, null, "NOT_REVIEWED"));

        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        assertThat(service.critique(org, run).passed()).isTrue();
        when(replies.mayWriteAhead(org, review)).thenReturn(false);
        assertThat(service.save(org, run)).isEqualTo(new ReplyRunService.Saved(false, null, "GATE_CLOSED"));
        verify(composer, never()).saveAgentDraft(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a seller's run is saved as that seller; an inquiry run is refused until its steps exist")
    void aSellersRunIsSavedAsTheSellerAndInquiriesWait() {
        UUID seller = UUID.randomUUID();
        UUID run = tokens.mint(org, "REVIEW", review.getId(), "SELLER", seller).runId();
        when(replies.maySellerWrite(org, review)).thenReturn(true);
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("D")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("네, 물에 닿아도 쓰실 수 있습니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        service.classify(org, run);
        service.draft(org, run, false);
        service.critique(org, run);
        service.save(org, run);
        verify(composer).saveAgentDraft(eq(org), eq(review), anyString(), any(), eq("SELLER:" + seller), any());

        UUID inquiryRun = run("INQUIRY", "INGEST");
        assertThatThrownBy(() -> service.classify(org, inquiryRun)).isInstanceOf(ApiException.class)
                .hasMessageContaining("문의");
    }

    @Test
    @DisplayName("only the draft a critique passed, over the inputs it checked, is saved (review 2026-10-28)")
    void onlyTheReviewedDraftOverTheReviewedInputsIsSaved() {
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("R")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("하루 만에 떨어졌다니 죄송합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        ReviewDraftComposer.AgentInputs before = new ReviewDraftComposer.AgentInputs("하루 만에 떨어졌어요",
                List.of(new AgentDraftGenerator.Passage("운영 정책", "교환", "수령 후 7일 이내 교환")), List.of(),
                "안녕하세요", null, List.of(), null, "MATCHED", List.of());
        ReviewDraftComposer.AgentInputs after = new ReviewDraftComposer.AgentInputs("하루 만에 떨어졌어요",
                List.of(new AgentDraftGenerator.Passage("운영 정책", "교환", "수령 후 3일 이내 교환")), List.of(),
                "안녕하세요", null, List.of(), null, "MATCHED", List.of());
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(before);
        service.classify(org, run);
        service.draft(org, run, false);

        // The policy changed between the draft and its critique: the critique fails it, so a revision rewrites it.
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(after);
        assertThat(service.critique(org, run).failed()).containsExactly("EVIDENCE_CHANGED");

        // Passed over the new policy, then the policy changed back before the save: nothing is saved.
        service.draft(org, run, true);
        assertThat(service.critique(org, run).passed()).isTrue();
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(before);
        assertThat(service.save(org, run)).isEqualTo(new ReplyRunService.Saved(false, null, "EVIDENCE_CHANGED"));
        verify(composer, never()).saveAgentDraft(any(), any(), any(), any(), any(), any());

        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(after);
        assertThat(service.save(org, run).saved()).as("over the inputs the critique checked, it saves").isTrue();
    }

    @Test
    @DisplayName("can't tell, a person's category anywhere in the review, or a question with no fact — a person's")
    void theJudgeHandsOverWhatItCannotSettle() {
        UUID unsure = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.Classified(null, List.of(), List.of(), true)));
        assertThat(service.classify(org, unsure)).as("판단 불가 is not 문제없음")
                .isEqualTo(new ReplyRunService.Judged(null, true, true, "UNSURE_AMBIGUOUS"));

        UUID mixed = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("R",
                List.of("P", "S"), List.of("배송이 빠름", "부품 누락", "손을 베임"), false)));
        assertThat(service.classify(org, mixed)).as("S carried second still goes to a person")
                .isEqualTo(new ReplyRunService.Judged("R", true, true, "CATEGORY_S"));
        assertThatThrownBy(() -> service.draft(org, mixed, false)).isInstanceOf(ApiException.class);
        assertThat(service.save(org, mixed).reason()).isEqualTo("HANDOFF_CATEGORY");

        UUID untied = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("D",
                List.of("Q"), List.of("색이 다름", "세탁 방법 질문"), false)));
        assertThat(service.classify(org, untied)).as("a question about a product the review is not tied to")
                .isEqualTo(new ReplyRunService.Judged("D", true, true, "UNSURE_PRODUCT"));

        UUID question = run("REVIEW", "INGEST");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("하루 만에 떨어졌어요",
                List.of(), List.of(), "안녕하세요", null, List.of(), UUID.randomUUID(), "NO_MATCH", List.of()));
        assertThat(service.classify(org, question)).as("no registered fact for the question (G2)")
                .isEqualTo(new ReplyRunService.Judged("D", true, true, "QUESTION_NO_FACTS"));

        // The word search found nothing, but the bundle carries the product's material: the drafter decides (fifth round).
        UUID bundled = run("REVIEW", "INGEST");
        var care = new com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage(
                com.sellerops.knowledge.KnowledgeScope.PRODUCT, "세탁", "손세탁해 주세요.", UUID.randomUUID(),
                UUID.randomUUID(), null, 0);
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("하루 만에 떨어졌어요",
                List.of(), List.of(), "안녕하세요", null, List.of(), UUID.randomUUID(), "GROUNDED", List.of(),
                com.sellerops.review.draft.ReviewEvidenceBundle.build(List.of(), List.of(care), List.of())));
        assertThat(service.classify(org, bundled).handoff()).as("material offered: not 「no facts」").isFalse();

        UUID many = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("R",
                List.of(), java.util.stream.IntStream.rangeClosed(1, 13).mapToObj(i -> "점 " + i).toList(), false)));
        assertThat(service.classify(org, many).reason()).as("more points than a reply covers: a person, none dropped")
                .isEqualTo("TOO_MANY_POINTS");
    }

    @Test
    @DisplayName("after the revision, form findings alone save the draft to polish; any other finding still hands it over")
    void formAloneIsPolishedNotHandedOver() {
        var rows = mock(com.sellerops.attention.reply.ReviewReplyDraftRepository.class);
        service.setDraftRows(rows);
        var saved = new com.sellerops.attention.reply.ReviewReplyDraft();
        when(rows.findByReviewIdAndVersion(review.getId(), 1)).thenReturn(Optional.of(saved));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("M")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("디자인을 좋게 봐 주셔서 감사합니다. 처음에 잘 나오지 않아 불편하셨던 점 죄송합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(List.of(new ReplyAgentService.Failed("F1", "같은 감사 문장을 되풀이하지 마세요."))));

        UUID run = run("REVIEW", "INGEST");
        service.classify(org, run);
        service.draft(org, run, false);
        assertThat(service.critique(org, run).passed()).as("first critique: revise").isFalse();
        service.draft(org, run, true);
        assertThat(service.critique(org, run)).as("still only F1 after the revision: polish")
                .isEqualTo(new ReplyRunService.Critiqued(true, List.of()));
        assertThat(service.save(org, run).saved()).isTrue();
        assertThat(saved.getPolishNote()).isEqualTo("같은 감사 문장을 되풀이하지 마세요.");
        assertThat(runs.findById(run).orElseThrow().getPolishRules()).isEqualTo("F1");

        // A judgement left after the revision is still a person's.
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(List.of(new ReplyAgentService.Failed("F1", "줄이세요."),
                        new ReplyAgentService.Failed("N9", "근거 없는 교환 약속을 빼세요."))));
        UUID other = run("REVIEW", "INGEST");
        service.classify(org, other);
        service.draft(org, other, false);
        service.critique(org, other);
        service.draft(org, other, true);
        assertThat(service.critique(org, other).failed()).containsExactly("F1", "N9");
    }

    @Test
    @DisplayName("over the channel's limit after the revision: kept to fix with the limit's sentence, never a style pass")
    void theChannelLimitIsKeptToFixWithItsOwnNote() {
        var rows = mock(com.sellerops.attention.reply.ReviewReplyDraftRepository.class);
        service.setDraftRows(rows);
        var saved = new com.sellerops.attention.reply.ReviewReplyDraft();
        when(rows.findByReviewIdAndVersion(review.getId(), 1)).thenReturn(Optional.of(saved));
        when(composer.agentFindings(eq(org), anyString(), any(), anyInt())).thenReturn(List.of(
                new ReviewDraftComposer.AgentCheck(com.sellerops.attention.reply.ChannelReplyLimit.CHANNEL_LENGTH_EXCEEDED,
                        "200자 안으로 줄이세요.")));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("M")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("가".repeat(201))));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        UUID run = run("REVIEW", "INGEST");
        service.classify(org, run);
        service.draft(org, run, false);
        service.critique(org, run);
        service.draft(org, run, true);
        service.critique(org, run);
        assertThat(service.save(org, run).saved()).isTrue();
        assertThat(runs.findById(run).orElseThrow().getPolishRules()).isEqualTo("CHANNEL_LENGTH_EXCEEDED");
        // No channel stated in this test, so the note is the critique's; the approval gate is the channel's own test.
        assertThat(saved.getPolishNote()).isNotBlank();
    }

    @Test
    @DisplayName("a review judged praise only is written without the store's facts; one judged mixed gets them")
    void praiseOnlyByTheJudgementGetsNoFacts() {
        var care = new com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage(
                com.sellerops.knowledge.KnowledgeScope.PRODUCT, "부착", "부착면을 닦고 30초 이상 눌러 주세요.", UUID.randomUUID(),
                UUID.randomUUID(), null, 0);
        var bundle = com.sellerops.review.draft.ReviewEvidenceBundle.build(List.of(), List.of(care), List.of());
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("잘 붙어요",
                bundle.stream().map(ReviewDraftComposer::shownAs).toList(), List.of(), "안녕하세요", null, List.of(),
                UUID.randomUUID(), "GROUNDED", List.of(), bundle));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("잘 붙는다니 기쁩니다.")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.sellerops.agent.llm.AgentDraftGenerator.Passage>> shown =
                ArgumentCaptor.forClass(List.class);

        UUID praise = run("REVIEW", "SELLER");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("P")));
        service.classify(org, praise);
        service.draft(org, praise, false);
        UUID mixed = run("REVIEW", "SELLER");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("M",
                List.of(), List.of("디자인 좋음", "겨울에 잘 안 붙음"), false)));
        service.classify(org, mixed);
        service.draft(org, mixed, false);
        verify(agent, times(2)).write(any(), any(), any(), any(), any(), any(), shown.capture(), any(),
                any(), any(), any(), any(), any());
        assertThat(shown.getAllValues().get(0)).as("praise only").isEmpty();
        assertThat(shown.getAllValues().get(1)).as("mixed").hasSize(1);
    }

    @Test
    @DisplayName("no answer in a bundle that left passages out is not 「the store has none」 (2026-11-01 fifth review)")
    void noAnswerFromAPartBundleIsNotNoKnowledge() {
        UUID run = run("REVIEW", "INGEST");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("세탁은 어떻게 하나요",
                List.of(), List.of("색상은 세 가지입니다."), "안녕하세요", null, List.of(), UUID.randomUUID(), "MATCHED",
                List.of(), List.of(), 2, 5));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("Q",
                List.of(), List.of("세탁 방법 질문"), false)));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.DraftAnswer("", List.of("세탁 방법 질문"), false)));
        service.classify(org, run);
        assertThat(service.draft(org, run, false)).isEqualTo(new ReplyRunService.Drafted(false, 0, "KNOWLEDGE_NOT_SHOWN"));
        // The counts are kept on the handed-over run too (sixth review).
        assertThat(runs.findById(run).orElseThrow()).satisfies(r -> {
            assertThat(r.getBundlePoliciesLeft()).isEqualTo(2);
            assertThat(r.getBundleProductLeft()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("the drafter that finds no fact for a point hands the review over, and nothing is saved after")
    void theDrafterHandsOverAPointWithNoFact() {
        UUID run = run("REVIEW", "INGEST");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("세탁은 어떻게 하나요",
                List.of(), List.of("색상은 세 가지입니다."), "안녕하세요", null, List.of(), UUID.randomUUID(), "MATCHED",
                List.of()));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("Q",
                List.of(), List.of("세탁 방법 질문"), false)));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.DraftAnswer("", List.of("세탁 방법 질문"), false)));
        assertThat(service.classify(org, run).handoff()).as("one passage exists, so the judge lets it through").isFalse();
        assertThat(service.draft(org, run, false)).isEqualTo(new ReplyRunService.Drafted(false, 0, "KNOWLEDGE_MISSING"));
        assertThat(service.save(org, run).reason()).isEqualTo("HANDOFF_CATEGORY");
    }

    @Test
    @DisplayName("a question with no fact beside points that stand alone: the reply is saved, the question goes to the seller "
            + "beside it, and the critique is told so (2026-11-01 eighth review)")
    void aPartialAnswerKeepsTheReplyAndHandsTheQuestionOver() {
        UUID run = run("REVIEW", "INGEST");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("잘 붙어요 세탁은요",
                List.of(), List.of("벽면을 닦고 붙이세요."), "안녕하세요", null, List.of(), UUID.randomUUID(), "MATCHED",
                List.of()));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("Q",
                List.of("P"), List.of("부착 칭찬", "세탁 방법 질문"), false)));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.DraftAnswer("잘 붙어 다행입니다. 감사합니다.",
                        List.of("세탁 방법  질문"), false)));
        ArgumentCaptor<List<String>> handed = ArgumentCaptor.forClass(List.class);
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), handed.capture()))
                .thenReturn(Optional.of(List.of()));
        service.classify(org, run);
        assertThat(service.draft(org, run, false)).isEqualTo(new ReplyRunService.Drafted(true, 0));
        assertThat(runs.findById(run).orElseThrow().getOpenQuestions()).isEqualTo("세탁 방법 질문");
        assertThat(service.critique(org, run).passed()).isTrue();
        assertThat(handed.getValue()).containsExactly("세탁 방법 질문");
        com.sellerops.openquestion.OpenQuestionService open = mock(com.sellerops.openquestion.OpenQuestionService.class);
        service.setOpenQuestions(open);
        assertThat(service.save(org, run).saved()).isTrue();
        assertThat(runs.findById(run).orElseThrow().getHandoffReason()).isNull();
        // The seller's one-by-one list (tenth review): a review reply is not held for it.
        verify(open).replace(eq(org), eq("REVIEW"), eq(review.getId()), eq(List.of("세탁 방법 질문")), eq(false),
                eq("reply-run:" + run), anyString());
    }

    @Test
    @DisplayName("a run that answers everything clears the questions the version before left to the seller (ninth review)")
    void aRunThatAnswersEverythingClearsTheQuestionsLeftBefore() {
        com.sellerops.attention.reply.ReviewReplyDraftRepository rows =
                mock(com.sellerops.attention.reply.ReviewReplyDraftRepository.class);
        com.sellerops.attention.reply.ReviewReplyDraft carried = new com.sellerops.attention.reply.ReviewReplyDraft();
        carried.setOpenQuestions("세탁 방법 질문");
        when(rows.findByReviewIdAndVersion(review.getId(), 1)).thenReturn(Optional.of(carried));
        service.setDraftRows(rows);
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("R")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("하루 만에 떨어졌다니 죄송합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        service.classify(org, run);
        service.draft(org, run, false);
        service.critique(org, run);
        assertThat(service.save(org, run).saved()).isTrue();
        assertThat(carried.getOpenQuestions()).isNull();
        verify(rows).save(carried);
    }

    @Test
    @DisplayName("a question tangled with the rest (no reply written), a conflict, or a bundle that left material out still "
            + "hands the whole review over")
    void aTangledQuestionStillHandsTheWholeReviewOver() {
        UUID run = run("REVIEW", "INGEST");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("세탁은 어떻게 하나요",
                List.of(), List.of("색상은 세 가지입니다."), "안녕하세요", null, List.of(), UUID.randomUUID(), "MATCHED",
                List.of(), List.of(), 1, 0));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("Q",
                List.of(), List.of("부착 칭찬", "세탁 방법 질문"), false)));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.DraftAnswer("잘 붙어 다행입니다.", List.of("세탁 방법 질문"),
                        false)));
        service.classify(org, run);
        assertThat(service.draft(org, run, false)).as("material left out: not established that the store has none")
                .isEqualTo(new ReplyRunService.Drafted(false, 0, "KNOWLEDGE_NOT_SHOWN"));
        assertThat(runs.findById(run).orElseThrow().getOpenQuestions()).isNull();

        UUID conflict = run("REVIEW", "INGEST");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("세탁은 어떻게 하나요",
                List.of(), List.of("색상은 세 가지입니다."), "안녕하세요", null, List.of(), UUID.randomUUID(), "MATCHED",
                List.of()));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.DraftAnswer("잘 붙어 다행입니다.", List.of("세탁 방법 질문"),
                        true)));
        service.classify(org, conflict);
        assertThat(service.draft(org, conflict, false)).isEqualTo(new ReplyRunService.Drafted(false, 0, "POLICY_CONFLICT"));
    }

    @Test
    @DisplayName("every point the judge found reaches the draft and the critique — none is dropped (G8)")
    void thePointsReachTheDraftAndTheCritique() {
        UUID run = run("REVIEW", "INGEST");
        List<String> points = List.of("배송 칭찬", "부품 누락", "교환 방법 질문");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("하루 만에 떨어졌어요",
                List.of(), List.of("교환은 문의 게시판으로 접수합니다."), "안녕하세요", null, List.of(), UUID.randomUUID(),
                "MATCHED", List.of()));
        when(agent.judge(any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReplyAgentService.Classified("R", List.of("Q", "P"), points, false)));
        when(agent.write(any(), any(), any(), any(), any(), eq(points), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("누락된 부품은 문의 게시판으로 교환 접수해 주세요.")));
        when(agent.critique(any(), any(), any(), any(), any(), eq(points), any(), any(), any()))
                .thenReturn(Optional.of(List.of()));
        assertThat(service.classify(org, run)).isEqualTo(new ReplyRunService.Judged("R", true, false, null));
        assertThat(service.draft(org, run, false).written()).isTrue();
        assertThat(service.critique(org, run).passed()).isTrue();
        assertThat(runs.findById(run).orElseThrow().getAlsoCategories()).isEqualTo("Q,P");
    }

    @Test
    @DisplayName("a critique the day's ceiling stopped holds the draft (검토 대기); the next run checks it, not rewrites it")
    void aCritiqueTheCeilingStoppedHoldsTheDraft() {
        com.sellerops.agent.quota.AgentQuotaService quota = mock(com.sellerops.agent.quota.AgentQuotaService.class);
        service.setQuota(quota);
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("R")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("하루 만에 떨어졌다니 죄송합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));

        UUID first = run("REVIEW", "INGEST");
        when(quota.hasRoom(eq(org), any())).thenReturn(true);
        service.classify(org, first);
        service.draft(org, first, false);
        when(quota.hasRoom(eq(org), any())).thenReturn(false);
        assertThat(service.critique(org, first)).isEqualTo(new ReplyRunService.Critiqued(false,
                List.of("REVIEW_PENDING"), true));
        assertThat(service.draft(org, first, true).reason()).as("nothing to revise while it waits")
                .isEqualTo("REVIEW_PENDING");
        service.finish(org, first, "PENDING", "REVIEW_PENDING");
        verify(agent, never()).critique(any(), any(), any(), any(), any(), any(), any(), any(), any());

        when(quota.hasRoom(eq(org), any())).thenReturn(true);
        UUID second = run("REVIEW", "INGEST");
        service.classify(org, second);
        assertThat(service.draft(org, second, false).written()).isTrue();
        verify(agent, times(1)).write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any());
        assertThat(service.critique(org, second).passed()).isTrue();
        assertThat(service.save(org, second).saved()).isTrue();
        verify(composer).saveAgentDraft(eq(org), eq(review), eq("하루 만에 떨어졌다니 죄송합니다."), any(), any(), any());
    }

    @Test
    @DisplayName("S · L · X are refused by the backend whatever order the steps are called in (review 2026-10-28)")
    void handoffCategoriesAreRefusedByTheBackend() {
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("S")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("불편을 드려 죄송합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        service.classify(org, run);
        assertThatThrownBy(() -> service.draft(org, run, false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.critique(org, run)).isInstanceOf(ApiException.class);
        assertThat(service.save(org, run)).isEqualTo(new ReplyRunService.Saved(false, null, "HANDOFF_CATEGORY"));
        verify(agent, never()).write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a new judgement voids the pass of the old one — passed as R, judged again as S or D, nothing is saved")
    void aNewJudgementVoidsTheOldPass() {
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("R")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("하루 만에 떨어졌다니 죄송합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        service.classify(org, run);
        service.draft(org, run, false);
        assertThat(service.critique(org, run).passed()).isTrue();

        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("S")));
        service.classify(org, run);
        assertThat(service.save(org, run)).isEqualTo(new ReplyRunService.Saved(false, null, "HANDOFF_CATEGORY"));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("D")));
        service.classify(org, run);
        assertThat(service.save(org, run)).isEqualTo(new ReplyRunService.Saved(false, null, "NOT_REVIEWED"));
        verify(composer, never()).saveAgentDraft(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a review that changed after it was judged is judged again before anything is written over it")
    void aChangedReviewIsJudgedAgain() {
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("P")));
        service.classify(org, run);
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("쓰다가 손을 데었어요",
                List.of(), List.of(), "안녕하세요", null, List.of(), null, "NO_MATCH", List.of()));
        assertThatThrownBy(() -> service.draft(org, run, false)).isInstanceOf(ApiException.class)
                .hasMessageContaining("다시 판단");
    }

    @Test
    @DisplayName("every review is read by the model; a harm or legal word only adds a person's look (second review)")
    void theWordNetIsAnAuxiliarySignal() {
        UUID burned = run("REVIEW", "INGEST");
        when(composer.agentInputs(eq(org), eq(review), any())).thenReturn(new ReviewDraftComposer.AgentInputs("쓰다가 손에 화상을 입었어요",
                List.of(), List.of(), null, null, List.of(), null, "NO_MATCH", List.of()));
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("S")));
        assertThat(service.classify(org, burned)).isEqualTo(new ReplyRunService.Judged("S", true, true));

        UUID disagrees = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("D")));
        assertThat(service.classify(org, disagrees)).as("the words say harm, the reading says otherwise: a person")
                .isEqualTo(new ReplyRunService.Judged("D", true, true, "SAFETY_SIGNAL"));

        UUID silent = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service.classify(org, silent)).as("no reading, and the words flag it: still a person, not a wait")
                .isEqualTo(new ReplyRunService.Judged(null, true, true, "SAFETY_SIGNAL"));
        verify(agent, times(3)).judge(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a critique that never ran is not revised — the draft goes to a person without a call")
    void anUnavailableCritiqueIsNotRevised() {
        UUID run = run("REVIEW", "INGEST");
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.of(judged("R")));
        when(agent.write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(answer("하루 만에 떨어졌다니 죄송합니다.")));
        when(agent.critique(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        service.classify(org, run);
        service.draft(org, run, false);
        service.critique(org, run);
        assertThat(service.draft(org, run, true).written()).isFalse();
        verify(agent, times(1)).write(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("a run of another org is not found, and a category outside the standard leaves the run unjudged")
    void anotherOrgsRunIsNotFoundAndAnOffStandardCategoryIsNoCategory() {
        UUID run = run("REVIEW", "INGEST");
        assertThatThrownBy(() -> service.classify(UUID.randomUUID(), run)).isInstanceOf(ApiException.class);
        when(agent.judge(any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service.classify(org, run)).isEqualTo(new ReplyRunService.Judged(null, false, false));
        assertThatThrownBy(() -> service.draft(org, run, false)).isInstanceOf(ApiException.class);
    }
}
