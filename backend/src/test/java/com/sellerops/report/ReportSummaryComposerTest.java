package com.sellerops.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportSummaryComposerTest {

    @Test
    @DisplayName("a rising issue yields a fact, an interpretation, and the limit — never a cause")
    void factInterpretationLimit() {
        ReportSummary summary = ReportSummaryComposer.compose(ReportFixtures.busy());
        List<String> texts = summary.lines().stream().map(ReportSummary.Line::text).toList();

        assertThat(texts).contains("「접착 부족」 관련 리뷰가 4건 있었습니다 (이전 기간 1건).");
        assertThat(summary.lines()).anySatisfy(l -> {
            assertThat(l.kind()).isEqualTo(ReportSummary.Kind.INTERPRETATION);
            assertThat(l.text()).isEqualTo("「접착 부족」 관련 리뷰 증가를 확인할 필요가 있습니다.");
            assertThat(l.factIds()).containsExactly("i-" + ReportFixtures.ISSUE);
        });
        assertThat(summary.lines()).anySatisfy(l -> {
            assertThat(l.kind()).isEqualTo(ReportSummary.Kind.LIMIT);
            assertThat(l.text()).contains("원인은 리뷰가 말해주지 않습니다");
        });
        // Every line cites something.
        assertThat(summary.lines()).allSatisfy(l -> assertThat(l.factIds()).isNotEmpty());
        // And no line asserts what the guard would refuse, except the LIMIT line that denies a cause.
        assertThat(summary.lines()).filteredOn(l -> l.kind() != ReportSummary.Kind.LIMIT)
                .allSatisfy(l -> assertThat(NarrativeClaimGuard.unsupportedClaim(l.text())).isFalse());
    }

    @Test
    void movementInCountersIsStatedWithBothNumbers() {
        List<String> texts = ReportSummaryComposer.compose(ReportFixtures.busy()).lines().stream()
                .map(ReportSummary.Line::text).toList();
        assertThat(texts).contains("부정 리뷰은(는) 이전 기간보다 2건 늘었습니다 (1건 → 3건).");
        assertThat(texts).noneMatch(t -> t.startsWith("주문은(는)"));
        assertThat(texts).contains("개선 기회 1건이 제안되어 있습니다 (검토 전 1건).");
        // 기간이 없을 뿐 시점이 없는 것은 아니다 — 저장되는 문장은 「지금」을 쓸 수 없다.
        assertThat(texts).contains("이 판을 만든 시점에 답변이 필요한 문의가 5건이었습니다.");
        assertThat(texts).noneMatch(t -> t.startsWith("지금 "));
    }

    @Test
    @DisplayName("a previous window with no reading is left out of the comparison, never compared as a zero")
    void absentPreviousReadingIsNotAZero() {
        ReportFacts facts = ReportFixtures.busy();
        ReportFacts withAbsent = ReportFixtures.facts(List.of(
                ReportFixtures.counter("c-reviews", "받은 리뷰", 81L, null),
                ReportFixtures.counter("c-negative-reviews", "부정 리뷰", 3L, null),
                ReportFixtures.counter("c-inquiries", "받은 문의", 5L, 1L),
                ReportFixtures.counter("c-orders", "주문", 317L, null),
                facts.counters().get(5)), facts.issues(), facts.opportunities(), List.of());
        List<String> texts = ReportSummaryComposer.compose(withAbsent).lines().stream()
                .map(ReportSummary.Line::text).toList();
        assertThat(texts.get(0)).contains("리뷰 81건, 문의 5건").contains("(이전 기간 문의 1건)");
        assertThat(texts).noneMatch(t -> t.startsWith("주문은(는)"));
        assertThat(texts).noneMatch(t -> t.contains("0건 → 317건"));
    }

    /**
     * <b>이 문장들은 저장된다</b> (2026-10-06). 화면은 다시 그릴 수 있지만 저장된 요약은 그 기간에 대해
     * 영구히 그렇게 말한다 — 실제로 「2026년 9월 28일 ~ 10월 4일에 리뷰 0건, 문의 0건이 들어왔습니다」가
     * 아무도 읽지 않은 창에 대해 저장돼 있었다.
     */
    @Test
    @DisplayName("an unread window produces no count, no comparison, and never 「달라진 것이 없습니다」")
    void unreadWindowStatesOnlyThatItIsUnread() {
        ReportSummary summary = ReportSummaryComposer.compose(ReportFixtures.unreadWeek());
        List<String> texts = summary.lines().stream().map(ReportSummary.Line::text).toList();

        assertThat(texts.get(0)).isEqualTo("2026년 8월 24일 ~ 30일의 수집 결과를 확인하지 못해, 이 기간에 무엇이 들어왔는지 알 수 없습니다.");
        assertThat(summary.lines().get(0).kind()).isEqualTo(ReportSummary.Kind.LIMIT);
        assertThat(texts).noneMatch(t -> t.contains("0건"));
        assertThat(texts).noneMatch(t -> t.contains("달라진 것이 없습니다"));
        assertThat(texts).noneMatch(t -> t.contains("줄었습니다") || t.contains("늘었습니다"));
        // 근거 리뷰를 읽지 못했으므로 반복 문제에 대해서도 「있었습니다」를 쓰지 않는다.
        assertThat(texts).noneMatch(t -> t.contains("관련 리뷰가"));
        // 기간이 없는 수치는 그대로 서되, 그것이 어느 시점의 값인지 함께 말한다.
        assertThat(texts).contains("이 판을 만든 시점에 답변이 필요한 문의가 27건이었습니다.");
    }

    @Test
    @DisplayName("매출 moves in 원, not in 건 — the unit rides on the fact")
    void revenueIsStatedInItsOwnUnit() {
        List<String> texts = ReportSummaryComposer.compose(ReportFixtures.busy()).lines().stream()
                .map(ReportSummary.Line::text).toList();
        assertThat(texts).contains("매출은(는) 이전 기간보다 1,836,512원 줄었습니다 (5,721,102원 → 3,884,590원).");
    }

    @Test
    @DisplayName("a quiet period says so in one sentence and invents nothing")
    void quietPeriod() {
        ReportSummary summary = ReportSummaryComposer.compose(ReportFixtures.quiet());
        assertThat(summary.lines().get(0).text()).startsWith("2026년 8월 24일 ~ 30일에는 달라진 것이 없습니다");
        assertThat(summary.lines()).hasSize(1);
        assertThat(summary.lines()).noneMatch(l -> l.kind() == ReportSummary.Kind.INTERPRETATION);
    }
}
