package com.sellerops.report;

import com.sellerops.coverage.ChannelDataState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class ReportFixtures {

    static final UUID ISSUE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID PRODUCT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final ReportPeriod PERIOD = ReportPeriod.startingAt(ReportKind.WEEKLY, LocalDate.of(2026, 8, 24));

    private ReportFixtures() {
    }

    static ReportFacts.Period period() {
        return new ReportFacts.Period("WEEKLY", "주간", PERIOD.start(), PERIOD.end(), PERIOD.labelKo(),
                PERIOD.previousStart(), PERIOD.previousEnd());
    }

    /** A measured figure: both windows read, so a delta exists. */
    static ReportFacts.Counter counter(String id, String label, Long now, Long prev) {
        return new ReportFacts.Counter(id, label, true, now, prev,
                now == null || prev == null ? null : now - prev, null, "건", "REVIEW", 0, false);
    }

    /** A figure whose window no channel could report on — stored as absent, never as a zero. */
    static ReportFacts.Counter unread(String id, String label) {
        return new ReportFacts.Counter(id, label, true, null, null, null, null, "건", "REVIEW", 3, false);
    }

    static ReportFacts.IssueFact issue(long current, long previous, boolean measured) {
        return new ReportFacts.IssueFact("i-" + ISSUE, ISSUE, "접착 부족", "NORMAL", "보통", current, previous,
                current - previous, List.of("증가 중"), PRODUCT, "선바로 몰딩", "/memory/" + ISSUE, measured);
    }

    /** 한 종류의 읽기를 그 시점 그대로 얼린 것. */
    static ReportFacts.Read read(String dataType, String labelKo, boolean measured, String... excludedCodes) {
        List<ReportFacts.ReadChannel> excluded = java.util.Arrays.stream(excludedCodes)
                .map(code -> new ReportFacts.ReadChannel(code, code, ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN,
                        Instant.parse("2026-08-30T03:00:00Z"),
                        "최근 자동 수집이 성공하지 못해 이 기간을 확인하지 못했습니다"))
                .toList();
        List<ReportFacts.ReadChannel> included = measured
                ? List.of(new ReportFacts.ReadChannel("NAVER", "네이버 스마트스토어", ChannelDataState.OBSERVED_FRESH,
                        Instant.parse("2026-08-31T03:00:00Z"), null))
                : List.of();
        return new ReportFacts.Read(dataType, labelKo, Instant.parse("2026-08-31T03:00:00Z"), measured,
                included, excluded);
    }

    static List<ReportFacts.Read> reads(boolean measured, String... excludedCodes) {
        return List.of(read("REVIEW", "리뷰", measured, excludedCodes),
                read("INQUIRY", "문의", measured, excludedCodes),
                read("ORDER_SUMMARY", "주문", measured, excludedCodes));
    }

    static ReportFacts facts(List<ReportFacts.Counter> counters, List<ReportFacts.IssueFact> issues,
                             List<ReportFacts.OpportunityFact> opportunities,
                             List<ReportFacts.Read> reads) {
        return new ReportFacts(period(), counters, List.of(), reads, issues, opportunities,
                List.of(new ReportFacts.NextStep("n-1", "답변이 필요한 문의 보기", "/inquiries?status=UNANSWERED",
                        List.of("c-unanswered-now"))),
                Instant.parse("2026-09-04T03:00:00Z"));
    }

    /** A week with movement: reviews up, one issue rising, one opportunity, five unanswered. */
    static ReportFacts busy() {
        return facts(
                List.of(counter("c-reviews", "받은 리뷰", 81L, 65L),
                        counter("c-negative-reviews", "부정 리뷰", 3L, 1L),
                        counter("c-inquiries", "받은 문의", 5L, 1L),
                        counter("c-orders", "주문", 120L, 120L),
                        new ReportFacts.Counter("c-revenue", "매출", true, 3_884_590L, 5_721_102L,
                                3_884_590L - 5_721_102L, null, "원", "ORDER_SUMMARY", 0, false),
                        new ReportFacts.Counter("c-unanswered-now", "현재 답변이 필요한 문의", false, 5L, null, null,
                                "/inquiries?status=UNANSWERED", "건", "INQUIRY", 0, false)),
                List.of(issue(4, 1, true)),
                List.of(new ReportFacts.OpportunityFact("o-" + ISSUE + "-PRODUCT_GUIDE_SUPPLEMENT", ISSUE,
                        "PRODUCT_GUIDE_SUPPLEMENT", "상품 상세·안내 보완", "OPEN", "검토 전", "접착 부족", PRODUCT,
                        "선바로 몰딩", "'접착' 안내를 상세 페이지에 보완하는 것을 검토하세요", "상세페이지 안내문 초안 준비",
                        "/memory/" + ISSUE)),
                reads(true));
    }

    /** A week that was READ and held nothing — the only kind of week that may be called quiet. */
    static ReportFacts quiet() {
        return facts(
                List.of(counter("c-reviews", "받은 리뷰", 0L, 0L),
                        counter("c-negative-reviews", "부정 리뷰", 0L, 0L),
                        counter("c-inquiries", "받은 문의", 0L, 0L),
                        counter("c-orders", "주문", 0L, 0L),
                        new ReportFacts.Counter("c-unanswered-now", "현재 답변이 필요한 문의", false, 0L, null, null,
                                null, "건", "INQUIRY", 0, false)),
                List.of(), List.of(), reads(true));
    }

    /** A week nobody read — the shape the 2026-10-06 audit found stored as a quiet one. */
    static ReportFacts unreadWeek() {
        return facts(
                List.of(unread("c-reviews", "받은 리뷰"),
                        unread("c-negative-reviews", "부정 리뷰"),
                        unread("c-inquiries", "받은 문의"),
                        unread("c-orders", "주문"),
                        new ReportFacts.Counter("c-unanswered-now", "현재 답변이 필요한 문의", false, 27L, null, null,
                                "/inquiries?status=UNANSWERED", "건", "INQUIRY", 0, false)),
                List.of(issue(0, 1, false)), List.of(), reads(false, "NAVER", "CAFE24"));
    }
}
