package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A calendar date is not a period (2026-11-10 rehearsal, F1): the seller's record 「교환 접수 완료, 새 상품 11월 11일 출고」
 * was read as a promise of 「11일」 and held against the policy's 「7일 안에」 — 「등록된 정책과 다르게 안내하고 있어 고쳐야
 * 해요」 over a date the seller wrote down. The day of 「M월 D일」 is the date's, never a term of the promise; a period stated
 * beside a date is still read, and still held when a policy says another.
 */
class DateIsNotAPeriodTest {

    private static final String SEVEN = "[데모] 교환·반품 기준\n상품을 받은 날부터 7일 안에 교환·반품을 신청할 수 있어요. "
            + "배송 중 포장이 파손된 경우 사진을 확인한 뒤 교환해 드려요.";
    private static final String RECORD = "판매자가 판매자센터에서 직접 확인한 이 문의의 결과: 처리 완료 — 교환 접수 완료, 새 상품 11월 11일 출고";
    private static final String DRAFT = "확인한 결과를 안내드립니다. 처리 완료 — 교환 접수 완료, 새 상품 11월 11일 출고";

    @Test
    void theDayOfADateIsNotATermOfThePromise() {
        assertThat(ReviewClaimGuard.unsupportedConditions(DRAFT, List.of(SEVEN), "포장이 찢어져서 왔어요. 교환 되나요?"))
                .isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions("교환은 12월 1일에 보내 드려요.", List.of(SEVEN), "")).isEmpty();
        assertThat(ReviewClaimGuard.unsupportedConditions("교환은 12 월 1 일에 보내 드려요.", List.of(SEVEN), ""))
                .as("spaced as people type it").isEmpty();
    }

    @Test
    void theRehearsalsRecordIsNotHeldAgainstThePolicy() {
        List<ReviewDraftRecheck.Rule> rules = List.of(new ReviewDraftRecheck.Rule("ORG_POLICY", "[데모] 교환·반품 기준", SEVEN));
        assertThat(ReviewDraftRecheck.otherRulesAgainst(DRAFT, List.of(SEVEN, RECORD), rules, "교환 되나요?")).isEmpty();
        assertThat(ReviewDraftRecheck.conflictsAmong(List.of(), DRAFT, List.of(SEVEN, RECORD), "교환 되나요?")).isEmpty();
    }

    @Test
    void aPeriodIsStillReadAndStillHeld() {
        List<ReviewDraftRecheck.Rule> rules = List.of(new ReviewDraftRecheck.Rule("ORG_POLICY", "[데모] 교환·반품 기준", SEVEN));
        assertThat(ReviewClaimGuard.unsupportedConditions("받으신 날부터 14일 안에 교환해 드려요.", List.of(SEVEN), ""))
                .containsExactly("14일");
        assertThat(ReviewDraftRecheck.otherRulesAgainst("받으신 날부터 14일 안에 교환해 드려요.", List.of(), rules, ""))
                .extracting(ReviewDraftRecheck.Against::word).containsExactly("14일");
        // Beside a date, the period is still the promise's.
        assertThat(ReviewClaimGuard.unsupportedConditions("11월 11일 출고했고, 받으신 날부터 14일 안에 교환해 드려요.",
                List.of(SEVEN), "")).containsExactly("14일");
        // 「개월」 ends in 월 and is a unit, not a month of the calendar.
        assertThat(ReviewClaimGuard.unsupportedConditions("3개월 7일 안에 교환해 드려요.", List.of("교환은 30일 안에 가능해요."), ""))
                .contains("7일");
    }
}
