package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 근거 없음 and 정책 충돌 are told apart (owner, 2026-11-01: 「근거 없음과 정책 충돌도 구분해 주세요」): a promise nothing
 * registered speaks of is unbacked — a seller may decide it for one reply; one a policy says otherwise of is a conflict —
 * the sentence or the policy changes first.
 */
class PromiseConflictTest {

    private static final List<String> SEVEN = List.of("교환 · 반품 안내\n수령 후 7일 이내, 미개봉 상품에 한해 교환 가능합니다. "
            + "단순 변심 반품은 불가합니다.");

    @Test
    void anotherPeriodThanThePolicysIsAConflict() {
        assertThat(ReviewDraftRecheck.conflictsAmong(List.of("14일"), "수령 후 14일 이내 교환해 드려요.", SEVEN, ""))
                .containsExactly("14일");
        assertThat(ReviewDraftRecheck.statesOtherwise("수령 후 7일 이내", "7일")).isFalse();
        assertThat(ReviewDraftRecheck.statesOtherwise("수령 후 7일 이내", "3개월")).as("another unit is not another period")
                .isFalse();
    }

    @Test
    void aRefusalWrittenUpAsAGrantIsAConflict() {
        String reply = "단순 변심 반품도 가능합니다.";
        List<String> promises = new java.util.ArrayList<>(ReviewClaimGuard.contradictedPromises(reply, SEVEN));
        assertThat(promises).isNotEmpty();
        assertThat(ReviewDraftRecheck.conflictsAmong(promises, reply, SEVEN, "")).containsExactlyElementsOf(promises);
    }

    @Test
    void aPromiseNoPolicySpeaksOfIsUnbackedNotAConflict() {
        assertThat(ReviewDraftRecheck.conflictsAmong(List.of("재발송"), "바로 재발송해 드리겠습니다.", SEVEN, "")).isEmpty();
        assertThat(ReviewDraftRecheck.conflictsAmong(List.of(ReviewClaimGuard.LAW_WITHDRAWAL), "개봉 상품은 반품이 안 됩니다.",
                SEVEN, "")).as("the law is its own finding").isEmpty();
    }

    // ── read on its own, whatever the reply cited (2026-11-01 fourth review) ───────────────────────────────────────

    private static ReviewDraftRecheck.Rule policy(String title, String text) {
        return new ReviewDraftRecheck.Rule("ORG_POLICY", title, text);
    }

    @Test
    void aReplyThatFollowsItsCitationIsStillReadAgainstEveryOtherPolicy() {
        // The review's synthetic case: cited 「교환 가능」, another policy in use says 「교환 불가」.
        List<String> cited = List.of("교환 안내\n교환 가능합니다.");
        List<ReviewDraftRecheck.Rule> rules = List.of(policy("교환 안내", "교환 가능합니다."),
                policy("반품 · 교환 제한", "교환 불가합니다."));
        String reply = "교환 가능합니다.";
        assertThat(ReviewDraftRecheck.conflictsAmong(List.of(), reply, cited, "")).as("the license check finds nothing")
                .isEmpty();
        assertThat(ReviewDraftRecheck.otherRulesAgainst(reply, cited, rules, "")).singleElement().satisfies(a -> {
            assertThat(a.title()).isEqualTo("반품 · 교환 제한");
            assertThat(a.clash()).as("the reply's own ground says yes: the policies disagree").isTrue();
        });
    }

    @Test
    void aProductDocumentIsNotAnExceptionToAStorePolicyByWhereItIsKept() {
        // The fifth review's case: the product note grants nothing, it only names the topic.
        List<ReviewDraftRecheck.Rule> rules = List.of(policy("교환 안내", "교환 불가합니다."),
                new ReviewDraftRecheck.Rule("PRODUCT_KNOWLEDGE", "상품 안내", "교환 관련 문의는 판매자에게 남겨주세요."));
        assertThat(ReviewDraftRecheck.otherRulesAgainst("교환 가능합니다.", List.of("교환 관련 문의는 판매자에게 남겨주세요."),
                rules, "")).extracting(ReviewDraftRecheck.Against::title).containsExactly("교환 안내");
        // And one that does say it the reply's way decides nothing either: the two documents disagree, the seller settles.
        List<ReviewDraftRecheck.Rule> both = List.of(policy("교환 안내", "교환 가능합니다."),
                new ReviewDraftRecheck.Rule("PRODUCT_KNOWLEDGE", "주문 제작 안내", "이 상품은 주문 제작이라 교환 불가합니다."));
        assertThat(ReviewDraftRecheck.otherRulesAgainst("이 상품은 주문 제작이라 교환이 불가합니다.",
                List.of("이 상품은 주문 제작이라 교환 불가합니다."), both, "")).singleElement()
                .satisfies(a -> assertThat(a.clash()).isTrue());
    }

    @Test
    void aPeriodAnotherPolicyStatesOtherwiseIsFound() {
        List<ReviewDraftRecheck.Rule> rules = List.of(policy("교환 안내", "수령 후 7일 이내 교환 가능합니다."));
        assertThat(ReviewDraftRecheck.otherRulesAgainst("수령 후 14일 이내 교환 가능합니다.", List.of(), rules, ""))
                .extracting(ReviewDraftRecheck.Against::word).contains("14일");
    }
}
