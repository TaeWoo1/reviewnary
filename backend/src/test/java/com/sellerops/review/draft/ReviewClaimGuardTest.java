package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grounded Review Drafting v1 — the check behind the prompt.
 *
 * <p>The sentence that does real damage in a public reply is not a wrong specification; it is a
 * remedy the seller never agreed to. These tests pin the two halves of the rule: a promise the
 * evidence supports is fine, and a promise nothing supports is a refusal.
 */
class ReviewClaimGuardTest {

    @Test
    void a_plain_thank_you_promises_nothing() {
        assertThat(ReviewClaimGuard.unsupportedClaims(
                "소중한 후기 감사합니다. 남겨주신 의견 잘 읽었습니다.", List.of())).isEmpty();
    }

    @Test
    void an_exchange_offered_with_no_evidence_at_all_is_refused() {
        assertThat(ReviewClaimGuard.unsupportedClaims(
                "불편을 드려 죄송합니다. 교환 도와드리겠습니다.", List.of())).containsExactly("교환");
    }

    @Test
    void the_same_offer_is_allowed_when_the_seller_own_policy_is_the_ground() {
        assertThat(ReviewClaimGuard.unsupportedClaims(
                "불편을 드려 죄송합니다. 교환 도와드리겠습니다.",
                List.of("수령 후 7일 이내 교환 및 반품이 가능합니다."))).isEmpty();
    }

    @Test
    void a_particle_or_a_space_cannot_hide_the_promise() {
        assertThat(ReviewClaimGuard.unsupportedClaims("환불 을 도와드리겠습니다.", List.of()))
                .containsExactly("환불");
        assertThat(ReviewClaimGuard.unsupportedClaims("전액 환불해 드리겠습니다.", List.of()))
                .containsExactly("환불");
    }

    @Test
    void every_unsupported_remedy_is_reported_in_declaration_order() {
        assertThat(ReviewClaimGuard.unsupportedClaims(
                "환불 또는 교환, 재발송까지 모두 도와드리겠습니다.", List.of()))
                .containsExactly("환불", "교환", "재발송");
    }

    @Test
    void evidence_about_a_different_remedy_does_not_authorize_this_one() {
        // The company's shipping note mentions 재발송; it says nothing about money coming back.
        assertThat(ReviewClaimGuard.unsupportedClaims(
                "환불 도와드리겠습니다.", List.of("파손 시 재발송해 드립니다.")))
                .containsExactly("환불");
    }

    /* ── a promise is its word and its terms (2026-10-23 external review) ── */

    @Test
    void a_period_the_policy_no_longer_states_is_unsupported_though_the_word_is_still_there() {
        String reply = "불편을 드려 죄송합니다. 수령 후 7일 이내라면 교환해 드리겠습니다.";
        assertThat(ReviewClaimGuard.unsupportedConditions(reply, List.of("수령 후 7일 이내 교환이 가능합니다."), null))
                .isEmpty();
        List<String> changed = List.of("수령 후 14일 이내 교환이 가능합니다.");
        assertThat(ReviewClaimGuard.unsupportedClaims(reply, changed)).as("the word test alone passes").isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions(reply, changed, null)).containsExactly("7일");
        assertThat(ReviewClaimGuard.unsupportedConditions(reply, List.of("수령 후 17일 이내 교환"), null))
                .as("7일 is not inside 17일").containsExactly("7일");
    }

    @Test
    void an_amount_or_a_cost_the_policy_does_not_state_is_unsupported() {
        List<String> policy = List.of("교환 시 왕복 배송비는 고객님 부담입니다.");
        assertThat(ReviewClaimGuard.unsupportedConditions("무료로 교환해 드리겠습니다.", policy, null))
                .containsExactly("무료");
        assertThat(ReviewClaimGuard.unsupportedConditions("전액 환불해 드리겠습니다.",
                List.of("불량이면 환불해 드립니다."), null)).containsExactly("전액");
        assertThat(ReviewClaimGuard.unsupportedConditions("30,000원까지 보상해 드리겠습니다.",
                List.of("파손 시 최대 30000원 보상"), null)).as("a thousands comma is the same number").isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions("1,000원 적립금을 드리겠습니다.",
                List.of("리뷰 적립금 11,000원"), null)).containsExactly("1000원");
    }

    @Test
    void a_scope_the_policy_does_not_grant_is_unsupported_and_its_negated_form_never_grants_it() {
        assertThat(ReviewClaimGuard.unsupportedConditions("단순 변심도 환불해 드리겠습니다.",
                List.of("제품 불량 시 환불해 드립니다."), null)).as("one term, not two").containsExactly("단순변심");
        assertThat(ReviewClaimGuard.unsupportedConditions("개봉 후에도 교환해 드리겠습니다.",
                List.of("미개봉 상품만 교환됩니다."), null)).containsExactly("개봉");
        assertThat(ReviewClaimGuard.unsupportedConditions("미개봉이시면 교환해 드리겠습니다.",
                List.of("미개봉 상품만 교환됩니다."), null)).isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions("착용하셨어도 반품해 드리겠습니다.",
                List.of("미착용 상품에 한해 반품 가능"), null)).containsExactly("착용");
    }

    @Test
    void only_a_promise_sentence_is_read_and_the_customer_may_authorize_only_a_number() {
        assertThat(ReviewClaimGuard.unsupportedConditions("배송이 7일 걸렸네요. 죄송합니다.", List.of(), null))
                .as("no remedy in that sentence").isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions("7일 이내 교환해 드리겠습니다.", List.of(), null))
                .as("an unsupported remedy is the word test's finding, not repeated as its terms").isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions("3일 만에 고장 나셨다니 교환해 드리겠습니다.",
                List.of("불량 제품은 교환해 드립니다."), "산 지 3일 만에 고장 났어요")).isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions("단순 변심도 환불해 드리겠습니다.",
                List.of("제품 불량 시 환불해 드립니다."), "단순 변심인데 환불 되나요"))
                .as("the customer asking is not the seller granting").containsExactly("단순변심");
    }

    @Test
    void a_blank_draft_is_not_a_violation() {
        assertThat(ReviewClaimGuard.unsupportedClaims(null, List.of())).isEmpty();
        assertThat(ReviewClaimGuard.unsupportedClaims("   ", List.of())).isEmpty();
    }

    // ---- a promise is also its yes or no (2026-10-27 review) ----

    @Test
    void a_grant_the_policy_refuses_under_the_same_terms_is_contradicted() {
        String policy = "단순 변심은 무료 교환이 불가합니다.";
        String draft = "단순 변심도 무료 교환이 가능합니다.";
        // Every word and every term is in the policy — the two older checks pass the reversal.
        assertThat(ReviewClaimGuard.unsupportedClaims(draft, List.of(policy))).isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions(draft, List.of(policy), "")).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises(draft, List.of(policy))).containsExactly("교환");
    }

    @Test
    void a_refusal_the_policy_grants_under_the_same_terms_is_contradicted_too() {
        assertThat(ReviewClaimGuard.contradictedPromises("단순 변심 환불은 어렵습니다.",
                List.of("단순 변심도 수령 후 7일 이내 환불해 드립니다."))).containsExactly("환불");
    }

    @Test
    void the_same_yes_under_the_same_terms_stands() {
        assertThat(ReviewClaimGuard.contradictedPromises("불량 제품은 무료 교환해 드리겠습니다.",
                List.of("단순 변심은 무료 교환이 불가합니다. 불량은 무료 교환해 드립니다."))).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises("단순 변심 교환은 어렵습니다.",
                List.of("단순 변심 교환은 불가합니다."))).isEmpty();
    }

    @Test
    void a_no_joined_to_a_yes_in_one_sentence_is_read_clause_by_clause() {
        List<String> policy = List.of("교환은 수령 후 7일 이내 가능하며, 단순 변심은 불가합니다.");
        assertThat(ReviewClaimGuard.contradictedPromises("수령 후 7일 이내 교환 가능합니다.", policy)).isEmpty();
        // The second clause names no remedy; it is about the one before it.
        assertThat(ReviewClaimGuard.contradictedPromises("단순 변심도 교환해 드립니다.", policy))
                .containsExactly("교환");
    }

    @Test
    void a_negative_word_before_the_remedy_or_inside_the_terms_is_not_a_refusal() {
        // The condition is negative; the promise is not.
        assertThat(ReviewClaimGuard.contradictedPromises("작동이 안 되는 제품은 교환해 드립니다.",
                List.of("제품 불량 시 교환해 드립니다."))).isEmpty();
        // A policy that qualifies its grant is still a grant.
        assertThat(ReviewClaimGuard.contradictedPromises("교환해 드리겠습니다.",
                List.of("교환은 개봉하지 않은 상품에 한해 가능합니다."))).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises("교환해 드리겠습니다.",
                List.of("교환은 수령 후 7일(주말 제외) 이내 가능합니다."))).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises("추가 비용 없이 교환해 드립니다.",
                List.of("불량은 추가 비용 없이 교환해 드립니다."))).isEmpty();
    }

    @Test
    void a_policy_that_never_speaks_to_the_stated_terms_is_not_a_contradiction() {
        // 「단순 변심」 is not in the policy at all: that is unsupportedConditions' finding, not a reversal.
        assertThat(ReviewClaimGuard.contradictedPromises("단순 변심 환불은 어렵습니다.",
                List.of("수령 후 7일 이내 환불해 드립니다."))).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises("환불해 드리겠습니다.", List.of())).isEmpty();
        assertThat(ReviewClaimGuard.contradictedPromises("소중한 후기 감사합니다.",
                List.of("교환은 불가합니다."))).isEmpty();
    }

    // ---- the yes that answers a different case does not license this one (2026-10-27 second review) ----

    @Test
    void a_grant_for_another_period_does_not_license_a_refused_one() {
        List<String> policy = List.of("7일 이내 교환 가능. 14일 이후 교환 불가.");
        assertThat(ReviewClaimGuard.contradictedPromises("14일 이후에도 교환 가능합니다.", policy))
                .containsExactly("교환");
        assertThat(ReviewClaimGuard.contradictedPromises("7일 이내 교환 가능합니다.", policy)).isEmpty();
    }

    @Test
    void a_grant_for_another_reason_does_not_license_a_refused_one() {
        List<String> policy = List.of("고객 과실 파손은 무료 교환 불가. 초기 불량은 무료 교환 가능.");
        assertThat(ReviewClaimGuard.contradictedPromises("고객 과실로 파손된 상품도 무료 교환 가능합니다.", policy))
                .containsExactly("교환");
        assertThat(ReviewClaimGuard.contradictedPromises("초기 불량이라 무료 교환해 드리겠습니다.", policy)).isEmpty();
    }

    @Test
    void a_promise_the_mixed_evidence_cannot_place_is_left_for_review_not_passed() {
        List<String> policy = List.of("7일 이내 교환 가능. 14일 이후 교환 불가.");
        // Neither case is named: the draft says yes where the seller says both.
        assertThat(ReviewClaimGuard.contradictedPromises("교환해 드리겠습니다.", policy)).isEmpty();
        assertThat(ReviewClaimGuard.undecidedPromises("교환해 드리겠습니다.", policy)).containsExactly("교환");
        assertThat(ReviewClaimGuard.undecidedPromises("7일 이내 교환 가능합니다.", policy)).isEmpty();
        assertThat(ReviewClaimGuard.undecidedPromises("14일 이후에도 교환 가능합니다.", policy)).isEmpty();
    }

    @Test
    void a_refused_exchange_is_not_a_refund_once_the_spaces_are_gone() {
        // 「교환 불가」 squashed is 「교환불가」, which holds 「환불」 — a word nobody wrote, on either side.
        assertThat(ReviewClaimGuard.unsupportedClaims("환불해 드리겠습니다.", List.of("단순 변심은 교환 불가합니다.")))
                .containsExactly("환불");
        assertThat(ReviewClaimGuard.unsupportedClaims("교환은 불가합니다.", List.of("교환은 불가합니다."))).isEmpty();
        assertThat(ReviewClaimGuard.remediesIn("교환불가합니다")).containsExactly("교환");
    }
}
