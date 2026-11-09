package com.sellerops.agent.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.runtoken.ReplyAgentRun;
import com.sellerops.auth.runtoken.ReplyAgentRunRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>What the reply agent handed to a person is on the seller's list until it is answered</b> (review 2026-10-28) — the
 * latest run per review, the redacted preview only, and gone once the channel or the seller has answered.
 */
class ReplyHandoffListTest {

    private static final UUID ORG = UUID.randomUUID();

    private static ReplyAgentRun run(UUID review, String category, String reason, Instant at) {
        ReplyAgentRun run = new ReplyAgentRun();
        run.setOrgId(ORG);
        run.setSubjectKind("REVIEW");
        run.setSubjectId(review);
        run.setCategory(category);
        run.setHandoffReason(reason);
        run.setFinishedAt(at);
        return run;
    }

    private static Review review(UUID id, String body) {
        Review r = new Review();
        r.setId(id);
        r.setOrgId(ORG);
        r.setRating(1);
        r.setBody(body);
        return r;
    }

    @Test
    @DisplayName("latest run per review, a redacted preview, and an answered review leaves the list")
    void theListIsWhatStillWaits() {
        UUID burned = UUID.randomUUID();
        UUID answered = UUID.randomUUID();
        ReplyAgentRunRepository runs = mock(ReplyAgentRunRepository.class);
        when(runs.findTop100ByOrgIdAndOutcomeOrderByFinishedAtDesc(ORG, "HANDOFF")).thenReturn(List.of(
                run(burned, "S", "CATEGORY_S", Instant.parse("2026-10-28T05:00:00Z")),
                run(burned, "R", "STILL_FAILS", Instant.parse("2026-10-27T05:00:00Z")),
                run(answered, "L", "CATEGORY_L", Instant.parse("2026-10-28T04:00:00Z"))));
        ReviewRepository reviews = mock(ReviewRepository.class);
        Review burnedReview = review(burned, "화상을 입었어요 010-1234-5678");
        Review answeredReview = review(answered, "소비자원에 신고했습니다");
        when(reviews.findByIdAndOrgId(burned, ORG)).thenReturn(Optional.of(burnedReview));
        when(reviews.findByIdAndOrgId(answered, ORG)).thenReturn(Optional.of(answeredReview));
        ReviewReplyService replies = mock(ReviewReplyService.class);
        when(replies.stillWaitingAfterHandoff(eq(ORG), eq(burnedReview), any())).thenReturn(true);
        when(replies.hasDraft(burned)).thenReturn(true);
        when(replies.stillWaitingAfterHandoff(eq(ORG), eq(answeredReview), any())).thenReturn(false);

        List<ReplyStartController.HandoffView> list = new ReplyStartController(null, reviews, replies, runs)
                .handoffs(new AuthPrincipal(UUID.randomUUID(), ORG, "seller@example.com"));

        assertThat(list).singleElement().satisfies(h -> {
            assertThat(h.reviewId()).isEqualTo(burned);
            assertThat(h.category()).isEqualTo("S");
            assertThat(h.reason()).isEqualTo("CATEGORY_S");
            assertThat(h.preview()).doesNotContain("010-1234-5678");
            assertThat(h.earlierDraft()).as("an earlier draft is shown with the handoff, not taken as its answer").isTrue();
        });
        verify(replies).stillWaitingAfterHandoff(ORG, burnedReview, Instant.parse("2026-10-28T05:00:00Z"));
    }

    @Test
    @DisplayName("a run that still failed says what it could not fix, in the seller's words (2026-10-31)")
    void aStillFailingRunSaysWhatWasBlocked() {
        UUID stuck = UUID.randomUUID();
        ReplyAgentRun failed = run(stuck, "M", "STILL_FAILS", Instant.parse("2026-10-31T05:00:00Z"));
        failed.setFailedRules("N9,G6,Z9");
        ReplyAgentRunRepository runs = mock(ReplyAgentRunRepository.class);
        when(runs.findTop100ByOrgIdAndOutcomeOrderByFinishedAtDesc(ORG, "HANDOFF")).thenReturn(List.of(failed));
        ReviewRepository reviews = mock(ReviewRepository.class);
        Review r = review(stuck, "처음엔 잘 안 나왔어요");
        when(reviews.findByIdAndOrgId(stuck, ORG)).thenReturn(Optional.of(r));
        ReviewReplyService replies = mock(ReviewReplyService.class);
        when(replies.stillWaitingAfterHandoff(eq(ORG), eq(r), any())).thenReturn(true);

        List<ReplyStartController.HandoffView> list = new ReplyStartController(null, reviews, replies, runs)
                .handoffs(new AuthPrincipal(UUID.randomUUID(), ORG, "seller@example.com"));

        assertThat(list).singleElement().satisfies(h -> assertThat(h.unmet()).containsExactly(
                "등록된 정책에 없는 약속(교환·반품·보상·날짜)으로 읽힐 표현을 확인하지 못했어요.",
                "칭찬 속 아쉬운 점을 다루지 못했어요."));
    }
}
