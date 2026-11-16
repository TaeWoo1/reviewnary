package com.sellerops.knowledge.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A schedule whose every date has passed is history, not the plan</b> (2026-11-16 §7.57) — and only a dated schedule
 * table is read so: a rule written long ago, a policy with a change date, a table with one row still ahead are not.
 */
class PastScheduleTest {

    static final LocalDate RECEIVED = LocalDate.parse("2026-11-14");
    static final String OCTOBER = "[합성] 입고 일정 (작성일 2026-10-02)\n\n| 상품 | 옵션 | 상태 | 날짜 | 구분 |\n|---|---|---|---|---|\n"
            + "| 면 침구 세트(퀸) | 그레이 | 재입고 | 2026-10-20 입고 | 확정 |\n";
    static final String NOVEMBER = "[합성] 생산·입고·출고 일정 (작성일 2026-11-10)\n\n| 상품 | 옵션 | 상태 | 날짜 | 구분 |\n"
            + "|---|---|---|---|---|\n| 면 침구 세트(퀸) | 그레이 | 품절, 재입고 대기 | 2026-11-25 입고 | 예정 |\n"
            + "| 스테인리스 텀블러 500ml | 민트 | 품절, 재입고 | 2026-11-18 입고, 2026-11-19부터 출고 | 확정 |\n";
    static final String POLICY = "[합성] 배송 안내 (기준일 2026-11-01 개정)\n- 배송비는 3,000원이며, 7만원 이상 무료입니다. "
            + "(2026-11-01부터 적용. 그 전에는 5만원 이상 무료였습니다.)";

    @Test
    @DisplayName("all rows past: history · one row ahead, the plan · later, the November table is history too")
    void whenEveryDateHasPassed() {
        assertThat(ScheduleCurrency.pastOnly(OCTOBER, RECEIVED)).isTrue();
        assertThat(ScheduleCurrency.pastOnly(NOVEMBER, RECEIVED)).isFalse();
        assertThat(ScheduleCurrency.pastOnly(NOVEMBER, LocalDate.parse("2026-12-01"))).isTrue();
        String mixed = OCTOBER + "| 면 침구 세트(퀸) | 아이보리 | 재입고 | 2026-11-30 입고 | 예정 |\n";
        assertThat(ScheduleCurrency.pastOnly(mixed, RECEIVED)).as("one row still ahead").isFalse();
        assertThat(ScheduleCurrency.pastOnly(OCTOBER + "| 면 침구 세트(퀸) | 아이보리 | 재입고 | 미정 | 예정 |\n", RECEIVED))
                .as("an undated row is not history").isFalse();
        assertThat(ScheduleCurrency.pastOnly(OCTOBER, null)).as("no reference, no verdict").isFalse();
    }

    @Test
    @DisplayName("kept: a policy with an old date, an old document with a rule, a table without a date column")
    void notEveryOldDateIsHistory() {
        assertThat(ScheduleCurrency.pastOnly(POLICY, RECEIVED)).isFalse();
        assertThat(ScheduleCurrency.pastOnly("[합성] 세척 안내 (작성일 2024-01-05)\n- 식기세척기 사용은 권하지 않습니다.", RECEIVED))
                .isFalse();
        assertThat(ScheduleCurrency.pastOnly("| 구성 | 수량 |\n|---|---|\n| 이불 커버 2020-01-01판 | 1 |\n", RECEIVED))
                .as("a date outside a date column is not a schedule date").isFalse();
    }

    @Test
    @DisplayName("the plan is asked unless the question is about before")
    void whatTheQuestionAsks() {
        assertThat(ScheduleCurrency.asksCurrentSchedule("그레이 재입고 언제? 날짜 알려주세요")).isTrue();
        assertThat(ScheduleCurrency.asksCurrentSchedule("재입고 되었나요?")).as("is it back now — the plan").isTrue();
        assertThat(ScheduleCurrency.asksCurrentSchedule("지난번엔 언제 들어왔었나요?")).isFalse();
        assertThat(ScheduleCurrency.asksHistory("지난번엔 언제 들어왔었나요?")).isTrue();
        assertThat(ScheduleCurrency.asksHistory("예전에 10월 입고분은 언제 출고됐었나요?")).isTrue();
        assertThat(ScheduleCurrency.asksCurrentSchedule("식기세척기 돼요?")).isFalse();
    }
}
