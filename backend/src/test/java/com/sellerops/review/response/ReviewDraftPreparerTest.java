package com.sellerops.review.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.review.Review;
import com.sellerops.review.draft.dto.GeneratedReviewDraftView;
import com.sellerops.review.triage.pilot.AiTriagePilotService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The run's own gates on drafting a review ahead</b> (product-owner, 2026-10-28): the seller's band rule, the AI
 * judge's verdict — the same for every rating — the re-read reach, the capability, and the day's AI budget. The gates on
 * the review itself are the reply service's and are tested there ({@code ReviewDraftAheadTest}).
 */
class ReviewDraftPreparerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    /** Every band drafted ahead — what a seller who wants their thank-yous written too would set. */
    private static final ReviewResponseRules.Modes ALL_AHEAD = new ReviewResponseRules.Modes(ReviewResponseMode.AHEAD,
            ReviewResponseMode.AHEAD, ReviewResponseMode.AHEAD);

    private ReviewResponseRuleService rules;
    private ReviewReplyService replies;
    private AgentDraftService model;
    private AgentQuotaService quota;
    private AiTriagePilotService judge;
    private ReviewDraftPreparer preparer;

    @BeforeEach
    void setUp() {
        rules = mock(ReviewResponseRuleService.class);
        replies = mock(ReviewReplyService.class);
        model = mock(AgentDraftService.class);
        quota = mock(AgentQuotaService.class);
        judge = mock(AiTriagePilotService.class);
        preparer = new ReviewDraftPreparer(rules, replies, model, quota, judge);
        when(rules.modesFor(ORG)).thenReturn(ReviewResponseRules.DEFAULT);
        when(model.isEnabledFor(ORG)).thenReturn(true);
        when(quota.status(ORG)).thenReturn(status(0, 100));
        when(judge.needsResponse(eq(ORG), any())).thenReturn(Optional.empty());
        when(replies.prepareAhead(any(), any())).thenReturn(Optional.of(mock(GeneratedReviewDraftView.class)));
    }

    private static AgentQuotaService.AgentQuotaStatus status(long used, int limit) {
        return new AgentQuotaService.AgentQuotaStatus(true, true, LocalDate.of(2026, 10, 28), 0, 0, used, limit);
    }

    private static Review review(Integer rating, Instant receivedAt) {
        Review r = new Review();
        r.setId(UUID.randomUUID());
        r.setOrgId(ORG);
        r.setRating(rating);
        r.setBody("합성-리뷰-본문");
        r.setReceivedAt(receivedAt);
        return r;
    }

    /** The judge's answer for one review: true = 응대 필요, false = 필요 없음, null = could not judge. */
    private Review judged(Integer rating, Boolean needsResponse, Instant receivedAt) {
        Review r = review(rating, receivedAt);
        when(judge.needsResponse(ORG, r)).thenReturn(Optional.ofNullable(needsResponse));
        return r;
    }

    @Test
    @DisplayName("by default ★1–3 are drafted ahead, and a ★4–5 only when the AI judge says it needs a response")
    void theDefaultDraftsAFourOrFiveOnlyWhenTheJudgeSaysSo() {
        assertThat(ReviewResponseRules.DEFAULT.high()).isEqualTo(ReviewResponseMode.ISSUES_ONLY);
        for (Integer rating : new Integer[] {1, 2, 3, null}) {
            assertThat(preparer.prepare(ORG, judged(rating, false, NOW), NOW)).isNull();
        }
        // 「별점 4~5점 칭찬 리뷰는 초안 잡지 말자」 — the thank-you waits for the seller.
        assertThat(preparer.prepare(ORG, judged(5, false, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.RULE_ISSUES_ONLY);
        assertThat(preparer.prepare(ORG, judged(5, true, NOW), NOW)).isNull();
        assertThat(preparer.prepare(ORG, judged(4, true, NOW), NOW)).isNull();
    }

    @Test
    @DisplayName("a ★4–5 the judge could not read waits for the seller — never a guess from its words (owner, 2026-10-28)")
    void anUnjudgedReviewWaitsInAnIssuesOnlyBand() {
        assertThat(preparer.prepare(ORG, judged(5, null, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.UNJUDGED);
        // A band set to 「미리 쓰기」 does not need the judge to say anything.
        assertThat(preparer.prepare(ORG, judged(1, null, NOW), NOW)).isNull();
    }

    @Test
    @DisplayName("the verdict is the judge's at every rating: a ★1 it says needs no response is not ranked as a complaint")
    void oneJudgeForEveryRating() {
        assertThat(ReviewDraftPreparer.rankOf(Optional.of(true))).isEqualTo(ReviewDraftPreparer.Rank.RESPONSE_NEEDED);
        assertThat(ReviewDraftPreparer.rankOf(Optional.of(false))).isEqualTo(ReviewDraftPreparer.Rank.NO_RESPONSE);
        assertThat(ReviewDraftPreparer.rankOf(Optional.empty())).isEqualTo(ReviewDraftPreparer.Rank.UNJUDGED);

        when(rules.modesFor(ORG)).thenReturn(new ReviewResponseRules.Modes(ReviewResponseMode.ISSUES_ONLY,
                ReviewResponseMode.ISSUES_ONLY, ReviewResponseMode.ISSUES_ONLY));
        assertThat(preparer.prepare(ORG, judged(1, false, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.RULE_ISSUES_ONLY);
        assertThat(preparer.prepare(ORG, judged(5, true, NOW), NOW)).isNull();
    }

    @Test
    @DisplayName("a seller may draft their ★4–5 thank-yous ahead too, or none of a band at all")
    void theSellerChoosesPerBand() {
        when(rules.modesFor(ORG)).thenReturn(ALL_AHEAD);
        assertThat(preparer.prepare(ORG, judged(5, false, NOW), NOW)).isNull();
        when(rules.modesFor(ORG)).thenReturn(new ReviewResponseRules.Modes(ReviewResponseMode.AHEAD,
                ReviewResponseMode.ISSUES_ONLY, ReviewResponseMode.ON_REQUEST));
        assertThat(preparer.prepare(ORG, judged(3, false, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.RULE_ISSUES_ONLY);
        assertThat(preparer.prepare(ORG, judged(5, true, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.RULE_ON_REQUEST);
    }

    @Test
    @DisplayName("a band set to 「누를 때 쓰기」 is not even judged — no model call for a review that will not be drafted")
    void anOnRequestBandIsNotJudged() {
        when(rules.modesFor(ORG)).thenReturn(new ReviewResponseRules.Modes(ReviewResponseMode.AHEAD,
                ReviewResponseMode.AHEAD, ReviewResponseMode.ON_REQUEST));
        Review five = review(5, NOW);
        preparer.prepareAll(ORG, List.of(five), NOW, () -> false);
        verify(judge, never()).needsResponse(ORG, five);
    }

    @Test
    @DisplayName("a review older than the re-read reach is drafted when the seller asks, not ahead — and not judged")
    void anOldReviewIsNotDraftedAhead() {
        Review old = review(1, NOW.minus(ReviewDraftPreparer.REACH).minusSeconds(1));
        assertThat(preparer.prepare(ORG, old, NOW)).isEqualTo(ReviewDraftPreparer.Skipped.OUT_OF_REACH);
        verify(judge, never()).needsResponse(any(), any());
        verify(replies, never()).prepareAhead(any(), any());
    }

    @Test
    @DisplayName("a run drafts what the judge says needs a response first, lower ratings first within it")
    void responseNeededIsDraftedFirst() {
        when(rules.modesFor(ORG)).thenReturn(ALL_AHEAD);
        List<UUID> order = new ArrayList<>();
        when(replies.prepareAhead(any(), any())).thenAnswer(call -> {
            order.add(((Review) call.getArgument(1)).getId());
            return Optional.of(mock(GeneratedReviewDraftView.class));
        });
        Review thanks = judged(5, false, NOW.minusSeconds(60));
        Review unjudged = judged(3, null, NOW.minusSeconds(120));
        Review fiveWithAProblem = judged(5, true, NOW.minusSeconds(180));
        Review one = judged(1, true, NOW.minusSeconds(240));

        int written = preparer.prepareAll(ORG, List.of(thanks, unjudged, fiveWithAProblem, one), NOW, () -> false);

        assertThat(written).isEqualTo(4);
        assertThat(order).containsExactly(one.getId(), fiveWithAProblem.getId(), unjudged.getId(), thanks.getId());
    }

    @Test
    @DisplayName("a ★4–5 thank-you stops at a quarter of the day, so the rest of the half stays for what needs a response")
    void thankYousStopAtAQuarter() {
        when(rules.modesFor(ORG)).thenReturn(ALL_AHEAD);
        when(quota.status(ORG)).thenReturn(status(24, 100));
        assertThat(preparer.prepare(ORG, judged(5, false, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.BUDGET_HALF_SPENT);
        assertThat(preparer.prepare(ORG, judged(5, true, NOW), NOW)).isNull();
        assertThat(preparer.prepare(ORG, judged(2, false, NOW), NOW)).isNull();
    }

    @Test
    @DisplayName("when a review that needs a response no longer fits the half, nothing ranked behind it is tried")
    void aResponseThatDoesNotFitEndsTheRun() {
        when(rules.modesFor(ORG)).thenReturn(ALL_AHEAD);
        when(quota.status(ORG)).thenReturn(status(49, 100));
        Review one = judged(1, true, NOW);
        Review thanks = judged(5, false, NOW);

        assertThat(preparer.prepareAll(ORG, List.of(thanks, one), NOW, () -> false)).isZero();
        verify(replies, never()).prepareAhead(any(), any());
    }

    @Test
    @DisplayName("drafting ahead stops while half of today's calls must stay for the seller's own presses")
    void halfOfTheBudgetStaysForTheSeller() {
        when(quota.status(ORG)).thenReturn(status(48, 100));
        assertThat(preparer.prepare(ORG, judged(1, true, NOW), NOW)).isNull();
        when(quota.status(ORG)).thenReturn(status(49, 100));
        assertThat(preparer.prepare(ORG, judged(1, true, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.BUDGET_HALF_SPENT);
        assertThat(ReviewDraftPreparer.withinHalfBudget(
                new AgentQuotaService.AgentQuotaStatus(false, false, LocalDate.of(2026, 10, 28), 0, 0, 999, 100)))
                .isTrue();
        assertThat(ReviewDraftPreparer.withinHalfBudget(status(999, 0))).isTrue();
    }

    @Test
    @DisplayName("with drafting off for the org nothing is written and nothing is asked of the review")
    void capabilityOffWritesNothing() {
        when(model.isEnabledFor(ORG)).thenReturn(false);
        assertThat(preparer.prepare(ORG, judged(1, true, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.CAPABILITY_OFF);
        verify(replies, never()).prepareAhead(any(), any());
    }

    @Test
    @DisplayName("an install with no agent running drafts nothing ahead — the old drafter is not its stand-in (2026-10-29)")
    void anInstallWithoutTheAgentWritesNothing() {
        com.sellerops.agent.reply.ReplyRunStarter agent = mock(com.sellerops.agent.reply.ReplyRunStarter.class);
        when(agent.required()).thenReturn(true);
        when(agent.available(ORG)).thenReturn(false);
        preparer.setAgentRuns(agent);
        assertThat(preparer.prepare(ORG, judged(1, true, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.CAPABILITY_OFF);
        verify(replies, never()).prepareAhead(any(), any());
        verify(agent, never()).startReview(any(), any(), any(), any());

        // The runtime is there but the run did not answer: a failed run, still no single-call draft.
        when(agent.available(ORG)).thenReturn(true);
        when(replies.mayWriteAhead(any(), any())).thenReturn(true);
        when(agent.startReview(any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(preparer.prepare(ORG, judged(1, true, NOW), NOW)).isEqualTo(ReviewDraftPreparer.Skipped.FAILED);
        verify(replies, never()).prepareAhead(any(), any());
    }

    @Test
    @DisplayName("a judge that throws is a review not judged, never a failed run")
    void aThrowingJudgeIsUnjudged() {
        Review five = review(5, NOW);
        when(judge.needsResponse(ORG, five)).thenThrow(new IllegalStateException("vendor down"));
        assertThat(preparer.prepare(ORG, five, NOW)).isEqualTo(ReviewDraftPreparer.Skipped.UNJUDGED);
    }

    @Test
    @DisplayName("the band: ★1–2 low, ★3 and no rating middle, ★4–5 high")
    void bands() {
        assertThat(ReviewResponseRules.band(1)).isEqualTo(ReviewResponseRules.Band.LOW);
        assertThat(ReviewResponseRules.band(2)).isEqualTo(ReviewResponseRules.Band.LOW);
        assertThat(ReviewResponseRules.band(3)).isEqualTo(ReviewResponseRules.Band.MID);
        assertThat(ReviewResponseRules.band(null)).isEqualTo(ReviewResponseRules.Band.MID);
        assertThat(ReviewResponseRules.band(4)).isEqualTo(ReviewResponseRules.Band.HIGH);
        assertThat(ReviewResponseRules.band(5)).isEqualTo(ReviewResponseRules.Band.HIGH);
    }
}
