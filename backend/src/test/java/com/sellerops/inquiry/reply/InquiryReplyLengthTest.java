package com.sellerops.inquiry.reply;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.InquirySourceSubtype;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A send limit is a channel, an inquiry kind, a unit and a source — a stated fact or nothing; never a guessed number, and
 * never one SellerOps made up (2026-11-02).
 */
class InquiryReplyLengthTest {

    @Test
    @DisplayName("ESM's limit is official; Coupang 상품별 고객문의, NAVER and Cafe24 state none and have none")
    void theKnownLimits() {
        assertThat(InquiryReplyLength.forInquiry("GMARKET", null)).get()
                .extracting(InquiryReplyLength.Limit::max, InquiryReplyLength.Limit::unit, InquiryReplyLength.Limit::source)
                .containsExactly(1000, InquiryReplyLength.Unit.BYTES, InquiryReplyLength.Source.OFFICIAL);
        assertThat(InquiryReplyLength.forInquiry("COUPANG", null)).as("공식 제한 미확인 — not 고객센터's 1,000자").isEmpty();
        assertThat(InquiryReplyLength.forInquiry("NAVER", InquirySourceSubtype.NAVER_PRODUCT_QNA)).isEmpty();
        assertThat(InquiryReplyLength.forInquiry("NAVER", InquirySourceSubtype.NAVER_CUSTOMER_INQUIRY)).isEmpty();
        assertThat(InquiryReplyLength.forInquiry("CAFE24", null)).isEmpty();
        assertThat(InquiryReplyLength.forInquiry(null, null)).isEmpty();
    }

    @Test
    @DisplayName("counted in the limit's unit on the normalized body; no limit is never over")
    void overLimit() {
        assertThat(InquiryReplyLength.overLimit("GMARKET", null, "가".repeat(333) + "a")).isFalse(); // 1000 bytes
        assertThat(InquiryReplyLength.overLimit("GMARKET", null, "가".repeat(333) + "aa")).isTrue(); // 1001
        assertThat(InquiryReplyLength.overLimit("GMARKET", null, "  " + "가".repeat(333) + "a\r\n")).as("normalized")
                .isFalse();
        assertThat(InquiryReplyLength.overLimit("COUPANG", null, "가".repeat(5000))).isFalse();
        assertThat(InquiryReplyLength.overLimit("NAVER", null, "가".repeat(5000))).isFalse();
        InquiryReplyLength.Limit chars = new InquiryReplyLength.Limit("X", "x", InquiryReplyLength.Unit.CHARS, 1000,
                InquiryReplyLength.Source.OFFICIAL, "x");
        assertThat(InquiryReplyLength.length(chars, "가".repeat(1000))).as("characters, not bytes").isEqualTo(1000);
    }

    @Test
    @DisplayName("the seller is told the length, the limit and what to do")
    void theSentence() {
        String said = InquiryReplyLength.overLimitKo(InquiryReplyLength.forInquiry("GMARKET", null).orElseThrow(), 1020);
        assertThat(said).contains("이 채널의 길이 한도(1,000바이트)").contains("지금 1,020바이트").contains("줄여서 저장한 뒤")
                .contains("자동으로 잘라 보내지 않아요");
    }

    @Test
    @DisplayName("the drafter is told the limit without a source, and nothing when there is none")
    void theDraftLine() {
        assertThat(InquiryReplyLength.draftLine("GMARKET", null)).startsWith("- 답변 길이:").contains("1000바이트")
                .contains("한글 약 333자").doesNotContain("이 채널은");
        assertThat(InquiryReplyLength.draftLine("COUPANG", null)).isEmpty();
        assertThat(InquiryReplyLength.draftLine("NAVER", null)).isEmpty();
        assertThat(InquiryReplyLength.STORAGE_MAX_BYTES).isGreaterThan(EsmAnswerValidation.COMMENTS_MAX_BYTES);
    }
}
