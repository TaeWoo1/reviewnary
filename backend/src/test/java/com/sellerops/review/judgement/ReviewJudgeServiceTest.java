package com.sellerops.review.judgement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.agent.reply.ReplyAgentService;
import com.sellerops.agent.reply.ReplyStandard;
import com.sellerops.review.Review;
import com.sellerops.review.draft.ReviewDraftComposer;
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
 * <b>Every review is judged once, apart from drafting</b> (third outside review, 2026-10-28, §3-4): the reading is stored
 * for the review, reused while its words and the judge are unchanged, read again when either changes, and a failed
 * reading is not kept as an answer.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReviewJudgeServiceTest {

    @Autowired ReviewJudgementRepository judgements;

    private final UUID org = UUID.randomUUID();
    private ReplyAgentService agent;
    private ReviewDraftComposer composer;
    private ReviewJudgeService service;
    private Review review;

    @BeforeEach
    void setUp() {
        agent = mock(ReplyAgentService.class);
        when(agent.standard()).thenReturn(new ReplyStandard());
        when(agent.version()).thenReturn("reply-agent/test");
        when(agent.isEnabledFor(org)).thenReturn(true);
        composer = mock(ReviewDraftComposer.class);
        review = new Review();
        review.setId(UUID.randomUUID());
        review.setOrgId(org);
        review.setRating(5);
        inputs("배송 빨라요. 근데 부품 하나가 빠져 있었어요");
        service = new ReviewJudgeService(agent, composer, judgements);
    }

    private void inputs(String body) {
        review.setBody(body);
        when(composer.agentInputs(org, review)).thenReturn(new ReviewDraftComposer.AgentInputs(body, List.of(),
                List.of("누락은 문의로 접수해 주세요."), null, null, List.of(), UUID.randomUUID(), "MATCHED", List.of()));
    }

    @Test
    @DisplayName("a five-star with a missing part is read, stored, and reused — the run that follows pays nothing more")
    void aReadingIsStoredAndReused() {
        when(agent.judge(eq(org), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("R",
                List.of("P"), List.of("배송 칭찬", "부품 누락"), false, List.of("CHECK_ORDER", "PUBLIC_REPLY"), null)));

        assertThat(service.judgeAll(org, List.of(review), "INGEST")).isEqualTo(1);
        ReviewJudgement row = judgements.findByOrgIdAndReviewId(org, review.getId()).orElseThrow();
        assertThat(row.getActions()).isEqualTo("CHECK_ORDER,PUBLIC_REPLY");
        assertThat(row.getPoints()).isEqualTo("배송 칭찬\n부품 누락");
        assertThat(service.current(org, review)).get().satisfies(d -> {
            assertThat(d.needsResponse()).isTrue();
            assertThat(d.actions()).containsExactly("CHECK_ORDER", "PUBLIC_REPLY");
        });

        service.judge(org, review, "INGEST", "run-1");
        verify(agent, times(1)).judge(eq(org), any(), any(), any());

        inputs("배송 빨라요. 근데 부품 하나가 빠져 있었어요. 손도 베였어요");
        assertThat(service.current(org, review)).as("changed words: the stored reading is not current for anyone")
                .isEmpty();
        assertThat(service.judge(org, review, "INGEST", "run-2").decision().reason())
                .as("changed words are read again, and the word net still asks for a person").isEqualTo("SAFETY_SIGNAL");
        verify(agent, times(2)).judge(eq(org), any(), any(), any());
    }

    @Test
    @DisplayName("a failed reading is recorded as failed and read again — never kept as 「nothing to do」")
    void aFailedReadingIsNotAnAnswer() {
        when(agent.judge(eq(org), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service.judgeAll(org, List.of(review), "INGEST")).isZero();
        assertThat(judgements.findByOrgIdAndReviewId(org, review.getId()).orElseThrow())
                .satisfies(r -> {
                    assertThat(r.isAnswered()).isFalse();
                    assertThat(r.getUnsureReason()).isEqualTo("MODEL_FAILED");
                });
        assertThat(service.current(org, review)).as("drafting ahead falls back, it does not read 「no」").isEmpty();
        service.judge(org, review, "INGEST", null);
        verify(agent, times(2)).judge(eq(org), any(), any(), any());
    }

    @Test
    @DisplayName("a review that needs nothing is NO_RESPONSE; the drafter's later handoff lands on the same row")
    void nothingToDoAndALaterHandoff() {
        when(agent.judge(eq(org), any(), any(), any())).thenReturn(Optional.of(new ReplyAgentService.Classified("P",
                List.of(), List.of("디자인 칭찬"), false, List.of("NO_RESPONSE"), null)));
        service.judgeAll(org, List.of(review), "INGEST");
        assertThat(service.current(org, review)).get().satisfies(d -> assertThat(d.needsResponse()).isFalse());

        service.noteHandoff(org, review.getId(), "KNOWLEDGE_MISSING");
        assertThat(service.current(org, review)).get().satisfies(d -> {
            assertThat(d.handoff()).isTrue();
            assertThat(d.reason()).isEqualTo("KNOWLEDGE_MISSING");
        });
    }
}
