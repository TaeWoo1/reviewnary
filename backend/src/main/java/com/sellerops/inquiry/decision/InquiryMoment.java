package com.sellerops.inquiry.decision;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * <b>When the inquiry arrived, as the seller's side knows it</b> (2026-11-02, case-eval-r12: 「오늘 오후 1시에
 * 결제했어요. 언제 출고되나요?」 was answered with 「오늘이 평일인지 알려 주시겠어요?」 in three rounds of three — the
 * decision and the drafter were never told the date, so the day of the week became a question for the customer).
 *
 * <p>Code writes it from the inquiry's received time, in Korean time. It names the date, the day of the week and
 * whether that day is a weekend — and says that whether a weekday is a public holiday is NOT known: this system holds
 * no holiday calendar, and a line that called a holiday a working day would be a false fact in the reply. Its first
 * wording asked for 「공휴일이 아니라면」 conditions, and a draft then explained what a business day is — a sentence no
 * evidence backs (case-eval-r13).
 */
public final class InquiryMoment {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String[] DAYS = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};

    private InquiryMoment() {
    }

    /** 「2026년 11월 1일 일요일 오후 7시 37분(한국 시간) · 주말」, or null when the time is unknown. */
    public static String of(Instant receivedAt) {
        if (receivedAt == null) {
            return null;
        }
        ZonedDateTime t = receivedAt.atZone(KST);
        int hour = t.getHour();
        String clock = (hour < 12 ? "오전 " : "오후 ") + (hour % 12 == 0 ? 12 : hour % 12) + "시"
                + (t.getMinute() == 0 ? "" : " " + t.getMinute() + "분");
        boolean weekend = t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
        return t.getYear() + "년 " + t.getMonthValue() + "월 " + t.getDayOfMonth() + "일 "
                + DAYS[t.getDayOfWeek().getValue() - 1] + " " + clock + "(한국 시간) · "
                + (weekend ? "주말" : "평일(공휴일인지는 알 수 없음)");
    }

    /**
     * The line that goes with the drafter's and the claim check's need list — the same line to both, so the check reads
     * the draft against what the drafter was told (B67, B72).
     */
    public static String scopeLine(String moment) {
        if (moment == null || moment.isBlank()) {
            return "";
        }
        return "- 문의 받은 때: " + moment + " → 이 날짜 · 요일은 판매자 쪽이 아는 사실입니다. 고객에게 오늘이 무슨 요일인지 "
                + "묻지 않습니다. 「오늘」은 이 날을 말합니다. 답에 날짜를 숫자로 다시 쓰지 말고 「말씀하신 날(일요일)」처럼 "
                + "씁니다. 공휴일인지는 알 수 없으니 어느 날이 영업일인지, 출고 · 도착이 며칠인지는 말하지 않고 근거의 기준을 "
                + "기준 그대로만 씁니다(영업일이 무엇인지 설명하지 않습니다)";
    }
}
