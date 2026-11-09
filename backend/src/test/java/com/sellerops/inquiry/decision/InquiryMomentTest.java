package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When the inquiry arrived, in Korean time, written by code (2026-11-02). */
class InquiryMomentTest {

    @Test
    @DisplayName("a Sunday evening is a weekend; a weekday says a holiday is not known")
    void theDay() {
        assertThat(InquiryMoment.of(Instant.parse("2026-11-01T10:37:00Z")))
                .isEqualTo("2026년 11월 1일 일요일 오후 7시 37분(한국 시간) · 주말");
        assertThat(InquiryMoment.of(Instant.parse("2026-11-02T04:00:00Z")))
                .isEqualTo("2026년 11월 2일 월요일 오후 1시(한국 시간) · 평일(공휴일인지는 알 수 없음)");
        assertThat(InquiryMoment.of(Instant.parse("2026-11-01T15:05:00Z"))).as("midnight in Korea, the next day")
                .startsWith("2026년 11월 2일 월요일 오전 12시 5분");
        assertThat(InquiryMoment.of(null)).isNull();
    }

    @Test
    @DisplayName("the line for the need list says not to ask the day, and to keep a holiday conditional")
    void theScopeLine() {
        String line = InquiryMoment.scopeLine(InquiryMoment.of(Instant.parse("2026-11-02T04:00:00Z")));
        assertThat(line).startsWith("- 문의 받은 때: 2026년 11월 2일 월요일").contains("묻지 않습니다")
                .contains("어느 날이 영업일인지").doesNotContain("공휴일이 아니라면");
        assertThat(InquiryMoment.scopeLine(null)).isEmpty();
    }
}
