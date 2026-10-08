package com.sellerops.responsibility.aside;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>닫힌 단어만 기록에 들어간다 — 그리고 2026-10-09에 모자랐던 네 단어.</b>
 *
 * <p>{@link AsideJobOutcome}은 「무엇이 됐는가」를, 이 단어는 「어디서 멈췄는가」를 말한다. 그 둘이 같은
 * 질문이 아니라는 것이 이 집합이 있는 이유이고, 2026-10-09에 그 한계가 한 번 더 드러났다: 첫 historical
 * catch-up이 outcome {@code EXECUTOR_UNAVAILABLE}로 끝났는데 이 칸은 비어 있었다. helper의 CLI는 이미
 * 「Aside에 닿지 못했다 / 프로그램이 터졌다 / 천장이 지났다 / 거절됐다」를 구분하고 있었고, runner가 그것을
 * 하나로 뭉갰다. 세 가지 고칠 자리가 한 단어 뒤에 있었다.
 */
class AsideJobFailureCodeTest {

    @Test
    @DisplayName("도우미가 프로그램을 못 돌린 네 가지가 각각 남는다")
    void theFourExecutorWords() {
        for (String word : new String[] {"EXECUTOR_UNAVAILABLE", "EXECUTOR_TIMEOUT",
                                         "EXECUTOR_REFUSED", "EXECUTOR_FAULT"}) {
            assertThat(AsideJobFailureCode.of(word)).as(word).isEqualTo(word);
        }
    }

    @Test
    @DisplayName("달력 단계의 멈춤도 각각 남는다")
    void theCalendarWords() {
        for (String word : new String[] {"CALENDAR_OPENER_NOT_FOUND", "CALENDAR_OPENER_AMBIGUOUS",
                                         "PICKER_VIEW_UNREADABLE", "MONTH_NAV_NOT_FOUND",
                                         "MONTH_NAV_AMBIGUOUS", "MONTH_NAV_UNVERIFIED",
                                         "MONTH_NAV_EXHAUSTED", "DAY_CELL_NOT_FOUND",
                                         "DAY_CELL_AMBIGUOUS"}) {
            assertThat(AsideJobFailureCode.of(word)).as(word).isEqualTo(word);
        }
    }

    @Test
    @DisplayName("모르는 값은 통과하지 않고 사라진다 — 이 칸은 helper가 보낸 글이 들어오는 통로가 아니다")
    void anUnknownWordIsDroppedRatherThanStored() {
        for (String raw : new String[] {"SOMETHING_NEW", "executor_timeout", "EXECUTOR_TIMEOUT ; drop table",
                                        "", "   ", "Aside isn't running on this machine"}) {
            assertThat(AsideJobFailureCode.of(raw)).as(raw).isNull();
        }
        assertThat(AsideJobFailureCode.of(null)).isNull();
    }

    @Test
    @DisplayName("앞뒤 공백은 다듬되, 그 뒤에도 닫힌 집합이어야 한다")
    void trimmedButStillClosed() {
        assertThat(AsideJobFailureCode.of("  EXECUTOR_FAULT  ")).isEqualTo("EXECUTOR_FAULT");
        assertThat(AsideJobFailureCode.of("  EXECUTOR_FAULTY  ")).isNull();
    }
}
