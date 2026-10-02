package com.sellerops.dashboard.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.coverage.ChannelDataState;
import com.sellerops.dashboard.dto.RecentNegativeProduct;
import com.sellerops.dashboard.insights.dto.OperationsInsight;
import com.sellerops.dashboard.metrics.dto.ChannelMetricRow;
import com.sellerops.dashboard.metrics.dto.MetricPeriod;
import com.sellerops.dashboard.metrics.dto.OperationsMetricsResponse;
import com.sellerops.reviewissue.ReviewIssue;
import com.sellerops.reviewissue.ReviewIssueRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>Every insight states when it is about</b> (product-owner decision, 2026-10-01).
 *
 * <p>The defect these fix was visible on screen: 「원터치 디스펜서… 부정 리뷰 3건 / 2025-11-01 ~
 * 2026-03-06」 under a heading that says 오늘 달라진 점. The producer behind it read the LIFETIME
 * roll-up ({@code DashboardService.topProductIssues}) and the record it returned carried no window,
 * so no consumer could have filtered it out.
 *
 * <p>These are asserted on the record's own fields rather than on any rendered string: the contract
 * is that a window is stated, and a screen is free to word it however it likes.
 */
class OperationsInsightPeriodTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final LocalDate TO = LocalDate.of(2026, 10, 1);
    private static final MetricPeriod PERIOD =
            new MetricPeriod(LocalDate.of(2026, 9, 25), TO, LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 24), 7);

    private final ReviewIssueRepository issues = mock(ReviewIssueRepository.class);
    private final OperationsInsightsService service = new OperationsInsightsService(issues);

    private OperationsMetricsResponse metrics(List<ChannelMetricRow> channels) {
        return new OperationsMetricsResponse(PERIOD, "ORDER_TOTAL", "ORDER_COUNT",
                List.of(), List.of(), channels, List.of(), false);
    }

    private static ChannelMetricRow channel(String code, String nameKo, long unanswered, long revenue) {
        return new ChannelMetricRow(code, nameKo,
                ChannelDataState.OBSERVED_FRESH, revenue, revenue > 0 ? 1 : 0, true,
                ChannelDataState.OBSERVED_FRESH, unanswered, unanswered, true, true,
                ChannelDataState.OBSERVED_FRESH, 0, 0, true, true, true);
    }

    private OperationsInsight only(String key, List<OperationsInsight> found) {
        return found.stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    /**
     * <b>Only a producer that states BOTH windows is claiming a change</b> (product-owner decision,
     * 2026-10-01). This is the structural property the Home filters on, so it is asserted per
     * producer rather than on a flag anyone could set: a connection state and a standing backlog are
     * observed today on every read, and observation is not change.
     */
    @Test
    void onlyTheWindowedComparisonStatesAChange() {
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of());
        UUID product = UUID.randomUUID();
        RecentNegativeProduct recent = new RecentNegativeProduct(product, "컵 수거기",
                3, 1, PERIOD.from(), PERIOD.to(), PERIOD.previousFrom(), PERIOD.previousTo(),
                LocalDate.of(2026, 9, 30));
        List<OperationsInsight> found = service.insights(ORG,
                metrics(List.of(channel("NAVER", "네이버", 4, 1_000), channel("COUPANG", "쿠팡", 0, 100))),
                List.of(recent));

        // The negative-review finding compares 최근 7일 against 이전 7일 — all four fields.
        OperationsInsight change = only("NEGATIVE_REVIEW_PRODUCT", found);
        assertThat(change.previousPeriodStart()).isEqualTo(PERIOD.previousFrom());
        assertThat(change.previousPeriodEnd()).isEqualTo(PERIOD.previousTo());

        // Everything else states a level. A backlog and a concentration are both true and neither is
        // news; a concentration states its window because the figure depends on it, and still no
        // baseline — MetricPeriod's own rule, applied to the record rather than to the prose.
        for (OperationsInsight other : found) {
            if (other.key().equals("NEGATIVE_REVIEW_PRODUCT")) {
                continue;
            }
            assertThat(other.previousPeriodStart())
                    .as("%s must not claim a comparison it did not make", other.key())
                    .isNull();
            assertThat(other.previousPeriodEnd()).isNull();
        }
    }

    /**
     * <b>A connection state is observed on every read, and that is not a change</b> (product-owner
     * decision, 2026-10-01). Collection health — disconnection, sync failure, stale collection — is
     * owned by the Home's 채널 상태 section alone; this card stays on `/overview` and must never
     * carry the shape that would let a 「무엇이 달라졌나」 surface draw it.
     */
    @Test
    void aBrokenConnectorIsNeverShapedLikeAChange() {
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of());
        ChannelMetricRow blocked = new ChannelMetricRow("ESM", "G마켓/옥션",
                ChannelDataState.BLOCKED, 0, 0, false,
                ChannelDataState.BLOCKED, 0, 0, false, false,
                ChannelDataState.BLOCKED, 0, 0, false, false, false);

        OperationsInsight found = only("CHANNEL_NOT_REPORTING",
                service.insights(ORG, metrics(List.of(blocked)), List.of()));

        assertThat(found.periodStart()).isNull();
        assertThat(found.periodEnd()).isNull();
        assertThat(found.previousPeriodStart()).isNull();
        assertThat(found.previousPeriodEnd()).isNull();
        // It is still dated, so a reader can see how stale the finding is — never so a screen can
        // call it today's news.
        assertThat(found.observedAt()).isEqualTo(TO);
    }

    @Test
    void everyInsightCarriesAnObservationDate() {
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of());
        List<OperationsInsight> found = service.insights(ORG,
                metrics(List.of(channel("NAVER", "네이버", 4, 1_000), channel("COUPANG", "쿠팡", 0, 100))),
                List.of());
        assertThat(found).isNotEmpty();
        // Never null, for every producer that fired. An insight nobody can date cannot be placed on a
        // screen that says 오늘.
        assertThat(found).allSatisfy(insight -> assertThat(insight.observedAt()).isNotNull());
    }

    @Test
    void aPresentStateFactCarriesNoWindowAndIsObservedOnTheReadsOwnLastDay() {
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of());
        OperationsInsight backlog = only("INQUIRY_BACKLOG",
                service.insights(ORG, metrics(List.of(channel("NAVER", "네이버", 4, 0))), List.of()));
        // Both null: a backlog is true NOW and a window would be a fiction. Not "unknown".
        assertThat(backlog.periodStart()).isNull();
        assertThat(backlog.periodEnd()).isNull();
        assertThat(backlog.observedAt()).isEqualTo(TO);
    }

    @Test
    void theNegativeReviewFindingIsTheWindowedCountAndNamesBothWindows() {
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of());
        UUID product = UUID.randomUUID();
        RecentNegativeProduct recent = new RecentNegativeProduct(product, "컵 수거기",
                3, 1, PERIOD.from(), PERIOD.to(), PERIOD.previousFrom(), PERIOD.previousTo(),
                LocalDate.of(2026, 9, 30));

        OperationsInsight found = only("NEGATIVE_REVIEW_PRODUCT",
                service.insights(ORG, metrics(List.of(channel("NAVER", "네이버", 0, 0))), List.of(recent)));

        // The number is the CURRENT window's, never a lifetime total.
        assertThat(found.title()).contains("3건").doesNotContain("4건");
        // A delta with an unnamed baseline is not a measurement — MetricPeriod's own rule.
        assertThat(found.detail()).contains("최근 7일").contains("이전 7일").contains("1건");
        assertThat(found.periodStart()).isEqualTo(PERIOD.from());
        assertThat(found.periodEnd()).isEqualTo(TO);
        // Observed when the latest review in the window was received, not when we read it.
        assertThat(found.observedAt()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void aZeroBaselineIsStillStated() {
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of());
        RecentNegativeProduct firstTime = new RecentNegativeProduct(UUID.randomUUID(), "컵 수거기",
                2, 0, PERIOD.from(), PERIOD.to(), PERIOD.previousFrom(), PERIOD.previousTo(), TO);
        OperationsInsight found = only("NEGATIVE_REVIEW_PRODUCT",
                service.insights(ORG, metrics(List.of(channel("NAVER", "네이버", 0, 0))), List.of(firstTime)));
        // Nothing happened in the previous span, and that is a measured fact rather than a withheld one.
        assertThat(found.detail()).contains("이전 7일 0건");
    }

    @Test
    void aRepeatedIssueIsDatedByItsOwnNewestEvidence() {
        ReviewIssue issue = mock(ReviewIssue.class);
        when(issue.getLastEvidenceOn()).thenReturn(LocalDate.of(2026, 9, 12));
        when(issue.getTitle()).thenReturn("접착 · 탈락");
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of(issue));

        OperationsInsight found = only("REPEATED_REVIEW_ISSUE",
                service.insights(ORG, metrics(List.of(channel("NAVER", "네이버", 0, 0))), List.of()));
        // The extractor's own evidence date. An issue whose newest evidence is three weeks old was not
        // observed today, and the Home has to be able to tell.
        assertThat(found.observedAt()).isEqualTo(LocalDate.of(2026, 9, 12));
    }

    @Test
    void anIssueWithNoEvidenceDateYieldsNothingRatherThanTodaysDate() {
        ReviewIssue undated = mock(ReviewIssue.class);
        when(undated.getLastEvidenceOn()).thenReturn(null);
        when(issues.findByOrgIdAndDismissedFalse(ORG)).thenReturn(List.of(undated));

        List<OperationsInsight> found =
                service.insights(ORG, metrics(List.of(channel("NAVER", "네이버", 0, 0))), List.of());
        // Dating a record by when we happened to read it is the fiction `observedAt` exists to stop.
        assertThat(found).noneMatch(i -> i.key().equals("REPEATED_REVIEW_ISSUE"));
    }
}
