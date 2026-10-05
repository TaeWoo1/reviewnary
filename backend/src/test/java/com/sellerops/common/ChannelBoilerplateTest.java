package com.sellerops.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The channel's own line comes off; the customer's does not.</b>
 *
 * <p>Cafe24's importer stamps {@code (YYYY-MM-DD HH:MM:SS 에 등록된 네이버 페이 구매평)} onto the end of a
 * review it brought over from NaverPay. While markup hid it nobody saw it; once the markup boundary
 * moved it took the front of every one of those rows' 60-character previews — a machine's sentence
 * standing where the customer's should be.
 *
 * <p>The two bodies here are the two rows that showed the defect on screen, with their markup as the
 * board actually sends it. Everything else guards the narrowness: the same words typed by a customer
 * mid-sentence stay, an ordinary review is untouched byte for byte, and a trailing parenthetical that
 * is NOT this footer stays — four inquiries in this deployment end with 「(서울경희직업전문학교)」, which a
 * person wrote.
 */
class ChannelBoilerplateTest {

    /** 2020-10-09, 조립형 당겨바 — the row the 리뷰 목록 ended on {@code <span style='color:}. */
    private static final String ROW_2020 =
            "큰 컵이 안들어가 아쉬워요, 둘레가 넓은게 따로 있나요?<br/><br/>"
                    + "<span style='color:#999999;'>(2020-10-09 12:10:47 에 등록된 네이버 페이 구매평)</span>";

    /** 2026-09-07, 세모금컵 — the other one, a 5점 참고 row. */
    private static final String ROW_2026 =
            "빠른배송 감사드려요<br/><br/>"
                    + "<span style='color:#999999;'>(2026-09-07 16:35:54 에 등록된 네이버 페이 구매평)</span>";

    @Test
    @DisplayName("the two rows that showed the defect come back as the customer's sentence alone")
    void theTwoMeasuredRowsLoseOnlyTheChannelLine() {
        assertThat(VocPreviewSanitizer.sanitize(ROW_2020).text())
                .isEqualTo("큰 컵이 안들어가 아쉬워요, 둘레가 넓은게 따로 있나요?");
        assertThat(VocPreviewSanitizer.redactFullBody(ROW_2020).text())
                .isEqualTo("큰 컵이 안들어가 아쉬워요, 둘레가 넓은게 따로 있나요?");

        assertThat(VocPreviewSanitizer.sanitize(ROW_2026).text()).isEqualTo("빠른배송 감사드려요");
        assertThat(VocPreviewSanitizer.redactFullBody(ROW_2026).text()).isEqualTo("빠른배송 감사드려요");
    }

    /**
     * The point of putting this at the sanitizer rather than in a view: the list row, the body the
     * seller reads, the retrieval query, the model payload and the draft are all downstream of these
     * two calls, so there is one sentence rather than four cleanings of it.
     */
    @Test
    @DisplayName("both entry points agree, so every reader sees the same body")
    void everyReaderSeesTheSameBody() {
        for (String raw : List.of(ROW_2020, ROW_2026)) {
            assertThat(VocPreviewSanitizer.sanitize(raw).text())
                    .isEqualTo(VocPreviewSanitizer.redactFullBody(raw).text());
            assertThat(VocPreviewSanitizer.redactFullBody(raw).text())
                    .doesNotContain("네이버 페이 구매평").doesNotContain("등록된");
        }
    }

    // ── and nothing else moves ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an ordinary review with no footer is untouched")
    void ordinaryReviewsAreUnchanged() {
        for (String body : List.of(
                "가격이 저렴해서 큰 기대없이 주문했는데 배송도 빠르고 수량도 가격대비 괜찮습니다",
                "접착력이 약해서 자꾸 떨어집니다. 보조 테이프를 따로 붙여서 쓰고 있어요",
                "버튼 누르면 컵이 두 개씩 나옵니다",
                "별점만 남기고 갑니다")) {
            assertThat(ChannelBoilerplate.strip(body)).isEqualTo(body);
            assertThat(VocPreviewSanitizer.sanitize(body).text()).isEqualTo(body);
            assertThat(VocPreviewSanitizer.redactFullBody(body).text()).isEqualTo(body);
        }
    }

    @Test
    @DisplayName("the same words inside a customer's own sentence stay")
    void theSameWordsMidSentenceStay() {
        String typed = "(2020-10-09 12:10:47 에 등록된 네이버 페이 구매평) 이라고 적힌 줄이 왜 붙나요? 지워 주세요";
        assertThat(ChannelBoilerplate.strip(typed)).isEqualTo(typed);
        // Through the sanitizer the words are all still there — the date is masked by the
        // account-number rule that has always run on VOC text, which is a different rule and not
        // this one's business. What matters here is that NOTHING was removed.
        assertThat(VocPreviewSanitizer.redactFullBody(typed).text())
                .contains("에 등록된 네이버 페이 구매평) 이라고 적힌 줄이 왜 붙나요? 지워 주세요");

        String mentioned = "네이버 페이 구매평 으로 썼는데 여기 올라오네요";
        assertThat(ChannelBoilerplate.strip(mentioned)).isEqualTo(mentioned);
    }

    /**
     * Four inquiries in this deployment end with {@code (서울경희직업전문학교)}. A rule that stripped
     * trailing parentheses would have eaten it — which is why the pattern is the footer, not the shape.
     */
    @Test
    @DisplayName("a trailing parenthetical the customer wrote is not boilerplate")
    void aCustomersOwnTrailingParentheticalSurvives() {
        for (String body : List.of(
                "교구 납품 문의드립니다 (서울경희직업전문학교)",
                "배송 언제 되나요 (급합니다)",
                "(2020-10-09 에 등록된 네이버 페이 구매평)")) {   // no clock part — not the footer
            assertThat(ChannelBoilerplate.strip(body)).isEqualTo(body);
        }
    }

    @Test
    @DisplayName("a body that is nothing but the footer comes back whole rather than empty")
    void aBodyOfOnlyTheFooterIsKept() {
        String onlyFooter = "(2020-10-09 12:10:47 에 등록된 네이버 페이 구매평)";
        assertThat(ChannelBoilerplate.strip(onlyFooter)).isEqualTo(onlyFooter);
        assertThat(VocPreviewSanitizer.sanitize(onlyFooter).text())
                .contains("에 등록된 네이버 페이 구매평");
    }

    @Test
    @DisplayName("only the trailing one goes, and only once")
    void stripsTheTerminalOccurrenceOnly() {
        String twice = "앞에 (2020-10-09 12:10:47 에 등록된 네이버 페이 구매평) 가 있고 뒤에도 있어요"
                + "(2026-09-07 16:35:54 에 등록된 네이버 페이 구매평)";
        assertThat(ChannelBoilerplate.strip(twice))
                .isEqualTo("앞에 (2020-10-09 12:10:47 에 등록된 네이버 페이 구매평) 가 있고 뒤에도 있어요");
    }

    @Test
    @DisplayName("날짜가 가려지고 꼬리가 잘린 꼴도 간다 — 이 경계가 생기기 전에 쓰인 글이 그 꼴이다")
    void stripsTheRedactedAndTruncatedShape() {
        // 지식 후보 하나가 실제로 들고 있는 문장 (데모 org, 2026-09-03 생성).
        String stored = "'항상 만족하며 잘 사용하고있어요 ([번호] 12:36:41 에 등록된 네…'에 대해 고객에게 "
                + "안내하는 공식 기준이 있나요? 이 상품에 저장된 지식에서 찾지 못했습니다.";
        assertThat(ChannelBoilerplate.strip(stored))
                .isEqualTo("'항상 만족하며 잘 사용하고있어요'에 대해 고객에게 안내하는 공식 기준이 있나요? "
                        + "이 상품에 저장된 지식에서 찾지 못했습니다.");
        // 인용부호 없이 글 끝에서 잘린 꼴도 같다.
        assertThat(ChannelBoilerplate.strip("항상 만족하며 잘 사용하고있어요 ([번호] 12:36:41 에 등록된 네…"))
                .isEqualTo("항상 만족하며 잘 사용하고있어요");
    }

    @Test
    @DisplayName("고객이 제 손으로 쓴 괄호는 셋을 다 만족하지 못하므로 남는다")
    void aCustomersOwnParenthesisSurvives() {
        // 시각도 「에 등록된」도 없는 괄호 — 이 배포에서 네 명이 문의 끝에 직접 쓴 꼴.
        assertThat(ChannelBoilerplate.strip("교육용으로 씁니다 (서울경희직업전문학교)"))
                .isEqualTo("교육용으로 씁니다 (서울경희직업전문학교)");
        // 시각은 있지만 「에 등록된」이 없다.
        assertThat(ChannelBoilerplate.strip("12:36:41 쯤 전화 주세요 (급해요"))
                .isEqualTo("12:36:41 쯤 전화 주세요 (급해요");
        // 「에 등록된」은 있지만 시각이 없다.
        assertThat(ChannelBoilerplate.strip("주소지에 등록된 번호로 (확인 부탁"))
                .isEqualTo("주소지에 등록된 번호로 (확인 부탁");
        // 괄호가 닫혀 있으면 그것은 고객이 끝낸 말이다.
        assertThat(ChannelBoilerplate.strip("메모 (12:36:41 에 등록된 내용) 확인 부탁드립니다"))
                .isEqualTo("메모 (12:36:41 에 등록된 내용) 확인 부탁드립니다");
    }

    @Test
    @DisplayName("redaction is unaffected — a phone number before the footer is still masked")
    void redactionIsUnaffected() {
        String raw = "연락처 010-1234-5678 로 주세요<br/>"
                + "<span style='color:#999999;'>(2020-10-09 12:10:47 에 등록된 네이버 페이 구매평)</span>";
        String text = VocPreviewSanitizer.redactFullBody(raw).text();
        assertThat(text).isEqualTo("연락처 [전화번호] 로 주세요");
        assertThat(VocPreviewSanitizer.redactFullBody(raw).redacted()).isTrue();
    }
}
