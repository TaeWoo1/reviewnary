package com.sellerops.replyhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A reply stating as fact a value the material does not settle is fixed, never confirmed past (2026-11-15) — and a
 * finding from before the rule keeps the key it had, so no recorded decision is voided by the change.
 */
class ReplyHoldUnsettledTest {

    private static ReplyHoldService.Finding finding(List<String> codes, List<String> unsettled) {
        return new ReplyHoldService.Finding(codes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), null, List.of(), List.of(), unsettled);
    }

    @Test
    @DisplayName("UNSETTLED_VALUE needs a fix, says which values disagree, and is no confirmation's to pass")
    void itIsFixedNotConfirmed() {
        String why = "교환 신청 기한이(가) 자료마다 달라요 — 7일(「교환·반품 안내」) · 14일(「옛 공지」). 답변은 「14일」로 적고 있어요.";
        ReplyHoldService.Finding f = finding(List.of(ReviewDraftRecheck.UNSETTLED_VALUE), List.of(why));
        assertThat(ReplyHoldService.mustFix(f)).isTrue();
        assertThat(ReplyHoldService.toConfirm(f)).isEmpty();
        assertThat(ReplyHoldService.headline(f)).contains("정해지지 않은 내용");
        assertThat(ReplyHoldService.reasonsOf(f)).contains(why).anySatisfy(r -> assertThat(r).contains("확인만으로 넘길 수 없어요"));
    }

    @Test
    @DisplayName("the key moves with the unsettled values, and a finding without any keeps its earlier key")
    void theKey() {
        ReplyHoldService.Finding before = new ReplyHoldService.Finding(List.of(ReviewDraftRecheck.SOURCE_CHANGED),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of(),
                List.of());
        assertThat(ReplyHoldService.keyOf(finding(List.of(ReviewDraftRecheck.SOURCE_CHANGED), List.of())))
                .isEqualTo(ReplyHoldService.keyOf(before));
        assertThat(ReplyHoldService.keyOf(finding(List.of(ReviewDraftRecheck.UNSETTLED_VALUE), List.of("a"))))
                .isNotEqualTo(ReplyHoldService.keyOf(finding(List.of(ReviewDraftRecheck.UNSETTLED_VALUE), List.of("b"))));
    }

    @Test
    @DisplayName("the sweep re-checks every waiting reply once under the new rule without moving the basis")
    void theRulesMarker() {
        assertThat(ReplyHoldService.SWEEP_RULES).contains("unsettled");
        assertThat(ReplyHoldService.sweptUnder("b")).isEqualTo("b|" + ReplyHoldService.SWEEP_RULES);
    }

    @Test
    @DisplayName("a planned date said as fixed is named as that, and is fixed in the reply, not on the knowledge screen")
    void aPlannedDate() {
        ReplyHoldService.Finding f = finding(List.of(ReviewDraftRecheck.UNSETTLED_VALUE),
                List.of("「면 침구 세트(퀸) · 그레이」 입고일 11월 25일은 아직 예정인데, 답변이 확정된 날짜처럼 적고 있어요."));
        assertThat(ReplyHoldService.headline(f)).isEqualTo("예정인 일정을 확정된 날짜처럼 안내하고 있어 고쳐야 해요.");
        assertThat(ReplyHoldService.reasonsOf(f)).noneSatisfy(r -> assertThat(r).contains("자료끼리 다른 값"));
    }
}
