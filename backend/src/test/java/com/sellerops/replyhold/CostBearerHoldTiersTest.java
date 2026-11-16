package com.sellerops.replyhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who pays the shipping, in the hold's three kinds (owner, 2026-11-10, F5): a bearer nothing registered states is the
 * 「이번 건만 예외」 decision, and its own sentence says the question and the reply are not its ground; one a policy states
 * the other way is fixed first and never excepted past.
 */
class CostBearerHoldTiersTest {

    @Test
    void splitCostConflictMustBeFixedRatherThanExcepted() {
        String reply = "단순 변심 반품은 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 저희가 부담합니다.";
        var costs = ReviewDraftRecheck.settleCosts(List.of(), reply, List.of(), List.of(new ReviewDraftRecheck.Rule(
                "ORG_POLICY", "반품 배송비", "단순 변심 반품의 왕복 배송비 6,000원은 고객님이 부담합니다.")));
        assertThat(costs.against()).hasSize(1);
        var found = new ReplyHoldService.Finding(
                List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE, ReviewDraftRecheck.POLICY_CONFLICT), List.of(),
                costs.promises(), List.of(), List.of(), List.of(),
                costs.against().stream().map(ReviewDraftRecheck.Against::word).toList(), costs.against());
        assertThat(ReplyHoldService.mustFix(found)).isTrue();
        assertThat(ReplyHoldService.needsException(found)).isFalse();
    }

    @Test
    void theHoldTiers() {
        String word = "파손·불량 시 교환 배송비 판매자 부담";
        ReplyHoldService.Finding unbacked = new ReplyHoldService.Finding(
                List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE), List.of(), List.of(word), List.of());
        assertThat(ReplyHoldService.needsException(unbacked)).isTrue();
        assertThat(ReplyHoldService.reasonsOf(unbacked)).anySatisfy(r ->
                assertThat(r).contains("등록된 정책·자료로 「" + word + "」", "고객 질문이나 답변에 적은 것은 근거가 되지 않아요"));

        ReplyHoldService.Finding conflict = new ReplyHoldService.Finding(
                List.of(ReviewDraftRecheck.UNSUPPORTED_PROMISE, ReviewDraftRecheck.POLICY_CONFLICT), List.of(), List.of(word),
                List.of(), List.of(), List.of(), List.of(word),
                List.of(new ReviewDraftRecheck.Against(word, "[데모] 교환 배송비 기준", false)));
        assertThat(ReplyHoldService.mustFix(conflict)).as("fixed first").isTrue();
        assertThat(ReplyHoldService.needsException(conflict)).as("never excepted").isFalse();
    }
}
