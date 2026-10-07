package com.sellerops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.OrgChannelVisibility;
import com.sellerops.coverage.ChannelCoverageService;
import com.sellerops.coverage.ChannelDataState;
import com.sellerops.coverage.dto.ChannelCoverageRow;
import com.sellerops.dashboard.metrics.OperationsMetricsRepository;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.opportunity.OpportunityService;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import com.sellerops.reviewissue.ReviewIssueQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>리포트의 0이 무엇을 뜻하는가</b> (2026-10-06, product-owner decision).
 *
 * <p>측정에서 나왔다. 데모 org의 주간 리포트(9/28–10/4)는 {@code 받은 리뷰 0}, {@code 받은 문의 0 ·
 * previous 4 · delta −4}를 <b>저장</b>하고 있었고, 같은 시각 coverage는 세 채널 아홉 줄 전부가
 * {@code OBSERVED_FRESHNESS_UNPROVEN}이었으며 마지막 성공 수집은 문의·리뷰 9월 26일, 주문 9월 5일이었다.
 * 그 창은 한 번도 읽히지 않았다. 같은 데이터 위에서 Overview는 아무것도 세지 않고 열두 개의 제외를 이름으로
 * 적고 있었다 — 리포트에만 관문이 없었다.
 *
 * <p>여기서 검사하는 규칙은 새로 만든 것이 아니라 Overview의 {@code counted()}를 그대로 가져온 것이고,
 * 저장되는 사실 하나를 위해 한 걸음만 더 간다: 자격을 갖춘 채널이 하나도 없으면 합계가 0이 아니라 없다.
 */
class ReportFactsBuilderCoverageTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID NAVER_ID = UUID.randomUUID();
    private static final UUID CAFE24_ID = UUID.randomUUID();
    private static final ReportPeriod PERIOD =
            ReportPeriod.startingAt(ReportKind.WEEKLY, LocalDate.of(2026, 9, 28));

    private final OperationsMetricsRepository metrics = mock(OperationsMetricsRepository.class);
    private final InquiryRepository inquiries = mock(InquiryRepository.class);
    private final ReviewIssueEvidenceRepository evidence = mock(ReviewIssueEvidenceRepository.class);
    private final ReviewIssueQueryService issues = mock(ReviewIssueQueryService.class);
    private final OpportunityService opportunities = mock(OpportunityService.class);
    private final ChannelRepository channels = mock(ChannelRepository.class);
    private final ChannelCoverageService coverage = mock(ChannelCoverageService.class);
    private final OrgChannelVisibility visible = mock(OrgChannelVisibility.class);

    private final ReportFactsBuilder builder = new ReportFactsBuilder(metrics, inquiries, evidence, issues,
            opportunities, channels, coverage, visible);

    private final List<ChannelCoverageRow> coverageRows = new ArrayList<>();

    @BeforeEach
    void wiring() {
        when(visible.codesFor(ORG)).thenReturn(List.of("NAVER", "CAFE24"));
        when(channels.findAll()).thenReturn(List.of(channel(NAVER_ID, "NAVER", "네이버 스마트스토어"),
                channel(CAFE24_ID, "CAFE24", "카페24 자사몰")));
        when(coverage.coverage(eq(ORG), any())).thenReturn(coverageRows);
        when(metrics.reviewSeries(any(), any(), any(), anyBoolean())).thenReturn(List.of());
        when(metrics.inquirySeries(any(), any(), any(), anyBoolean())).thenReturn(List.of());
        when(metrics.orderSeries(any(), any(), any(), anyBoolean())).thenReturn(List.of());
        when(evidence.issueCountsInWindow(any(), any(), any())).thenReturn(List.of());
        when(issues.list(any(), any())).thenReturn(List.of());
        when(opportunities.list(any(), any(), any(), any(), anyBoolean())).thenReturn(List.of());
        when(inquiries.countUnansweredOperational(ORG)).thenReturn(27L);
    }

    private static Channel channel(UUID id, String code, String nameKo) {
        Channel channel = new Channel();
        channel.setId(id);
        channel.setCode(code);
        channel.setNameKo(nameKo);
        return channel;
    }

    private void state(String code, String dataType, ChannelDataState state) {
        coverageRows.add(new ChannelCoverageRow(code, code.equals("NAVER") ? "네이버 스마트스토어" : "카페24 자사몰",
                dataType, state, true, null, true, "CONNECTED", true, null,
                java.time.Instant.parse("2026-09-26T18:31:17Z"), null, null, 0, null, null));
    }

    private void allChannels(ChannelDataState state) {
        for (String code : List.of("NAVER", "CAFE24")) {
            for (String type : List.of("REVIEW", "INQUIRY", "ORDER_SUMMARY")) {
                state(code, type, state);
            }
        }
    }

    /** {@code [channelId, day, primary, secondary]} — the shape all three series share. */
    private static Object[] row(UUID channelId, String day, long primary, long secondary) {
        return new Object[] {channelId, LocalDate.parse(day), primary, secondary};
    }

    /** {@code List.of(row(…))} would infer {@code List<Object>}; the repository answers {@code List<Object[]>}. */
    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    private ReportFacts build() {
        return builder.build(ORG, PERIOD, Instant.parse("2026-10-06T11:25:49Z"));
    }

    private static ReportFacts.Counter counter(ReportFacts facts, String id) {
        return facts.counters().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the measured defect: an unread window stores no count and no decrease")
    void unreadWindowStoresNeitherZeroNorDelta() {
        allChannels(ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN);
        // 이전 창에는 문의 4건이 들어와 있다 — 그래서 전에는 「0건, 4건 줄음」이 만들어졌다.
        when(metrics.inquirySeries(eq(ORG), eq(PERIOD.previousStart()), eq(PERIOD.previousEnd()), anyBoolean()))
                .thenReturn(rows(row(NAVER_ID, "2026-09-22", 4, 1)));

        ReportFacts facts = build();

        ReportFacts.Counter inquiries = counter(facts, "c-inquiries");
        assertThat(inquiries.current()).isNull();
        assertThat(inquiries.delta()).isNull();
        assertThat(counter(facts, "c-reviews").current()).isNull();
        assertThat(counter(facts, "c-orders").current()).isNull();
        assertThat(counter(facts, "c-revenue").current()).isNull();
        // 빠진 이유는 채널 이름과 함께 남는다 — 종류마다 두 채널씩, 생성 시점 그대로.
        assertThat(facts.reads()).extracting(ReportFacts.Read::dataType)
                .containsExactly("REVIEW", "INQUIRY", "ORDER_SUMMARY");
        assertThat(facts.reads()).allSatisfy(r -> {
            assertThat(r.measured()).isFalse();
            assertThat(r.included()).isEmpty();
            assertThat(r.excluded()).hasSize(2);
            assertThat(r.excluded()).allSatisfy(c ->
                    assertThat(c.reasonKo()).isEqualTo("최근 자동 수집이 성공하지 못해 이 기간을 확인하지 못했습니다"));
        });
        assertThat(inquiries.excludedChannels()).isEqualTo(2);
    }

    @Test
    @DisplayName("a measured zero is still a zero — ZERO is the one state that may say 없습니다")
    void measuredZeroSurvives() {
        allChannels(ChannelDataState.ZERO);

        ReportFacts facts = build();

        assertThat(counter(facts, "c-reviews").current()).isZero();
        assertThat(counter(facts, "c-inquiries").current()).isZero();
        assertThat(counter(facts, "c-revenue").current()).isZero();
        // 둘 다 측정된 0이므로 비교도 성립한다 — 변화 없음(0)이라는 사실.
        assertThat(counter(facts, "c-reviews").delta()).isZero();
        assertThat(facts.reads()).allSatisfy(r -> {
            assertThat(r.excluded()).isEmpty();
            assertThat(r.included()).hasSize(2);
            assertThat(r.measured()).isTrue();
        });
        assertThat(counter(facts, "c-reviews").unproven()).isFalse();
    }

    @Test
    @DisplayName("current measured, previous not — the number stands, the comparison does not")
    void deltaNeedsBothWindows() {
        allChannels(ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN);
        // 이번 창에는 행이 있으므로 그 채널은 세어진다(counted). 이전 창에는 아무 채널도 자격이 없다.
        when(metrics.reviewSeries(eq(ORG), eq(PERIOD.start()), eq(PERIOD.end()), anyBoolean()))
                .thenReturn(rows(row(NAVER_ID, "2026-09-29", 25, 3)));

        ReportFacts facts = build();

        ReportFacts.Counter reviews = counter(facts, "c-reviews");
        assertThat(reviews.current()).isEqualTo(25L);
        assertThat(reviews.previous()).isNull();
        assertThat(reviews.delta()).isNull();
        // 세어진 채널의 수집이 최신임이 증명되지 않았다는 사실은 숫자 옆에 남는다.
        assertThat(reviews.unproven()).isTrue();
        assertThat(reviews.excludedChannels()).isEqualTo(1);
        assertThat(counter(facts, "c-negative-reviews").current()).isEqualTo(3L);
    }

    @Test
    @DisplayName("both windows measured — the delta exists and is the difference")
    void deltaStandsWhenBothWindowsAreMeasured() {
        allChannels(ChannelDataState.OBSERVED_FRESH);
        when(metrics.reviewSeries(eq(ORG), eq(PERIOD.start()), eq(PERIOD.end()), anyBoolean()))
                .thenReturn(rows(row(NAVER_ID, "2026-09-29", 25, 0)));
        when(metrics.reviewSeries(eq(ORG), eq(PERIOD.previousStart()), eq(PERIOD.previousEnd()), anyBoolean()))
                .thenReturn(rows(row(NAVER_ID, "2026-09-22", 17, 0)));

        ReportFacts.Counter reviews = counter(build(), "c-reviews");
        assertThat(reviews.current()).isEqualTo(25L);
        assertThat(reviews.previous()).isEqualTo(17L);
        assertThat(reviews.delta()).isEqualTo(8L);
        assertThat(reviews.unproven()).isFalse();
    }

    @Test
    @DisplayName("매출 is stored as a fact of the period, per channel, under the orders gate")
    void revenueIsASnapshotFact() {
        allChannels(ChannelDataState.OBSERVED_FRESH);
        when(metrics.orderSeries(eq(ORG), eq(PERIOD.start()), eq(PERIOD.end()), anyBoolean()))
                .thenReturn(rows(row(NAVER_ID, "2026-09-29", 200, 2_731_000),
                        row(CAFE24_ID, "2026-09-30", 72, 1_153_590)));
        when(metrics.orderSeries(eq(ORG), eq(PERIOD.previousStart()), eq(PERIOD.previousEnd()), anyBoolean()))
                .thenReturn(rows(row(NAVER_ID, "2026-09-22", 317, 5_721_102)));

        ReportFacts facts = build();

        ReportFacts.Counter revenue = counter(facts, "c-revenue");
        assertThat(revenue.current()).isEqualTo(3_884_590L);
        assertThat(revenue.previous()).isEqualTo(5_721_102L);
        assertThat(revenue.delta()).isEqualTo(3_884_590L - 5_721_102L);
        assertThat(revenue.unit()).isEqualTo("원");
        assertThat(counter(facts, "c-orders").current()).isEqualTo(272L);

        // 채널별도 같은 창의 사실이고, 큰 것부터 선다.
        assertThat(facts.salesByChannel()).extracting(ReportFacts.ChannelSales::channelCode)
                .containsExactly("NAVER", "CAFE24");
        assertThat(facts.salesByChannel().get(0).amount()).isEqualTo(2_731_000L);
        // 그리고 인용할 수 있는 id를 가진다 — 사실이면 id가 있다.
        assertThat(facts.factIds()).contains("c-revenue", "s-NAVER", "s-CAFE24");
    }

    @Test
    @DisplayName("a channel that gave us rows is counted even when its freshness is unproven")
    void rowsInTheWindowQualifyAChannel() {
        allChannels(ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN);
        when(metrics.orderSeries(eq(ORG), eq(PERIOD.start()), eq(PERIOD.end()), anyBoolean()))
                .thenReturn(rows(row(CAFE24_ID, "2026-09-30", 0, 65_750)));

        ReportFacts facts = build();

        // 건수가 0이어도 금액이 있으면 그 채널은 말한 것이다 — Overview가 쓰는 바로 그 피연산자.
        assertThat(counter(facts, "c-revenue").current()).isEqualTo(65_750L);
        assertThat(counter(facts, "c-orders").current()).isZero();
        assertThat(facts.salesByChannel()).singleElement()
                .satisfies(s -> assertThat(s.channelNameKo()).isEqualTo("카페24 자사몰"));
        // 말하지 않은 채널은 합계에서 빠지고 이름으로 남는다.
        ReportFacts.Read orders = facts.reads().stream()
                .filter(r -> r.dataType().equals("ORDER_SUMMARY")).findFirst().orElseThrow();
        assertThat(orders.excluded()).singleElement()
                .satisfies(c -> assertThat(c.channelCode()).isEqualTo("NAVER"));
        assertThat(orders.included()).singleElement()
                .satisfies(c -> assertThat(c.channelCode()).isEqualTo("CAFE24"));
    }

    /**
     * <b>읽은 범위는 숫자와 같은 시각에 얼어야 한다</b> (2026-10-06, product-owner decision). 전에는 화면이
     * 라이브 coverage를 다시 읽었고, 그러면 9월 27일까지 읽고 만든 판이 내일은 10월 8일까지 읽은 것처럼
     * 보인다 — 사실은 멈춰 있는데 그 사실의 자격만 움직인다.
     */
    @Test
    @DisplayName("the read range is frozen with the figures: kind, last read, included, excluded, measured")
    void theReadRangeIsStoredWithTheSnapshot() {
        allChannels(ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN);
        when(metrics.reviewSeries(eq(ORG), eq(PERIOD.start()), eq(PERIOD.end()), anyBoolean()))
                .thenReturn(rows(row(NAVER_ID, "2026-09-29", 25, 3)));

        ReportFacts facts = build();

        ReportFacts.Read review = facts.reads().stream()
                .filter(r -> r.dataType().equals("REVIEW")).findFirst().orElseThrow();
        assertThat(review.labelKo()).isEqualTo("리뷰");
        assertThat(review.measured()).isTrue();
        // 생성 시점의 마지막 확인 — coverage가 그때 말한 값 그대로.
        assertThat(review.lastReadAt()).isEqualTo(Instant.parse("2026-09-26T18:31:17Z"));
        assertThat(review.included()).singleElement().satisfies(c -> {
            assertThat(c.channelCode()).isEqualTo("NAVER");
            assertThat(c.reasonKo()).isNull();
            assertThat(c.lastReadAt()).isEqualTo(Instant.parse("2026-09-26T18:31:17Z"));
        });
        assertThat(review.excluded()).singleElement().satisfies(c -> {
            assertThat(c.channelCode()).isEqualTo("CAFE24");
            assertThat(c.reasonKo()).isEqualTo("최근 자동 수집이 성공하지 못해 이 기간을 확인하지 못했습니다");
        });
        // 읽은 적 없는 종류는 시각이 없다 — 0이 아니라 없음이다.
        assertThat(facts.reads()).extracting(ReportFacts.Read::dataType)
                .containsExactly("REVIEW", "INQUIRY", "ORDER_SUMMARY");
    }

    @Test
    @DisplayName("미답변 has no window, so the period's coverage says nothing about it")
    void theStandingBacklogIsNotWindowed() {
        allChannels(ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN);

        ReportFacts.Counter unanswered = counter(build(), "c-unanswered-now");
        assertThat(unanswered.current()).isEqualTo(27L);
        assertThat(unanswered.periodic()).isFalse();
        assertThat(unanswered.to()).isEqualTo("/inquiries?status=UNANSWERED");
    }
}
