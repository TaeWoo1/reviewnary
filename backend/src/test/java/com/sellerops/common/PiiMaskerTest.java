package com.sellerops.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiMaskerTest {

    @Test
    void masksEmail() {
        assertThat(PiiMasker.maskText("연락처는 hong@example.com 입니다"))
                .isEqualTo("연락처는 [이메일] 입니다");
    }

    @Test
    void masksKoreanMobileForms() {
        assertThat(PiiMasker.maskText("010-1234-5678로 연락주세요")).isEqualTo("[전화번호]로 연락주세요");
        assertThat(PiiMasker.maskText("01012345678")).isEqualTo("[전화번호]");
        assertThat(PiiMasker.maskText("010 1234 5678")).isEqualTo("[전화번호]");
        assertThat(PiiMasker.maskText("011-123-4567")).isEqualTo("[전화번호]");
    }

    @Test
    void masksLandlineForms() {
        assertThat(PiiMasker.maskText("02-123-4567")).isEqualTo("[전화번호]");
        assertThat(PiiMasker.maskText("031.123.4567")).isEqualTo("[전화번호]");
    }

    @Test
    void masksMultiplePiiValuesInOneString() {
        assertThat(PiiMasker.maskText("hong@example.com / 010-1234-5678 둘 다 가립니다"))
                .isEqualTo("[이메일] / [전화번호] 둘 다 가립니다");
    }

    @Test
    void leavesPlainKoreanTextUnchanged() {
        String text = "접착력이 약해져서 금방 떨어졌어요. 재구매 의사 없습니다.";
        assertThat(PiiMasker.maskText(text)).isEqualTo(text);
    }

    @Test
    void leavesProductSpecsAndPricesUnchanged() {
        assertThat(PiiMasker.maskText("폭 30mm 제품이 5,000원이면 적당합니다"))
                .isEqualTo("폭 30mm 제품이 5,000원이면 적당합니다");
        assertThat(PiiMasker.maskText("2026-06-14 주문분"))
                .isEqualTo("2026-06-14 주문분");
    }

    @Test
    void isNullAndBlankSafe() {
        assertThat(PiiMasker.maskText(null)).isNull();
        assertThat(PiiMasker.maskText("")).isEqualTo("");
        assertThat(PiiMasker.maskText("   ")).isEqualTo("   ");
    }

    @Test
    void maskNameKeepsFirstCharOnly() {
        assertThat(PiiMasker.maskName("홍길동")).isEqualTo("홍**");
        assertThat(PiiMasker.maskName("김민")).isEqualTo("김*");
        assertThat(PiiMasker.maskName("김")).isEqualTo("김");
        assertThat(PiiMasker.maskName(null)).isNull();
        assertThat(PiiMasker.maskName("")).isEqualTo("");
    }

    /** 2026-10-28: an order number read as 「[전화번호]」 — the phone shapes matched inside a longer digit run. */
    @org.junit.jupiter.api.Test
    void aNumberThatRunsOnIsNotAPhoneNumber() {
        assertThat(PiiMasker.maskText("주문번호 2026102812345678 확인 부탁드려요")).doesNotContain("[전화번호]")
                .contains("2026102812345678");
        assertThat(PiiMasker.maskText("주문 20241015010123456789")).doesNotContain("[전화번호]");
        // A phone number is still one, with or without separators, at the start, end or beside words.
        assertThat(PiiMasker.maskText("010-1234-5678로 연락 주세요")).isEqualTo("[전화번호]로 연락 주세요");
        assertThat(PiiMasker.maskText("연락처01012345678")).isEqualTo("연락처[전화번호]");
        assertThat(PiiMasker.maskText("매장 02-123-4567")).isEqualTo("매장 [전화번호]");
    }
}
