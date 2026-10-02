package com.sellerops.dashboard.insights;

import com.sellerops.common.Korean;
import com.sellerops.coverage.ChannelDataState;
import com.sellerops.dashboard.dto.RecentNegativeProduct;
import com.sellerops.dashboard.insights.dto.OperationsInsight;
import com.sellerops.dashboard.metrics.dto.ChannelMetricRow;
import com.sellerops.dashboard.metrics.dto.MetricPeriod;
import com.sellerops.dashboard.metrics.dto.OperationsMetricsResponse;
import com.sellerops.reviewissue.ReviewIssue;
import com.sellerops.reviewissue.ReviewIssueRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Overview's "지금 눈여겨볼 것" — at most five, each with a destination.
 *
 * <p><b>Every producer is allowed to return nothing.</b> The list is short because the conditions are
 * real, not because it was truncated: a demo org with no backlog and no negative concentration gets
 * fewer cards, and that is the correct output.
 *
 * <p><b>Coverage outranks arithmetic here too.</b> A channel that cannot report is a finding of its
 * own ({@link #freshness}) rather than a silent gap under some other card's number.
 */
@Service
public class OperationsInsightsService {

    /** The screen's budget. Five cards is a glance; ten is a second inbox. */
    static final int MAX_INSIGHTS = 5;

    /** One channel holding this much of a total is a concentration worth naming, not a coincidence. */
    static final double CONCENTRATION_THRESHOLD = 0.6;

    private final ReviewIssueRepository issues;

    public OperationsInsightsService(ReviewIssueRepository issues) {
        this.issues = issues;
    }

    @Transactional(readOnly = true)
    public List<OperationsInsight> insights(UUID orgId, OperationsMetricsResponse metrics,
                                            List<RecentNegativeProduct> recentNegatives) {
        List<OperationsInsight> found = new ArrayList<>();
        // <b>The read's own last day, not a second clock</b> (product-owner decision, 2026-10-01).
        // A present-state finding is observed when the window that produced it ends; calling
        // LocalDate.now() here would let two insights built in one request disagree about the date.
        LocalDate asOf = metrics.period().to();
        backlog(metrics, asOf).ifPresent(found::add);
        negativeProduct(recentNegatives).ifPresent(found::add);
        repeatedIssue(orgId).ifPresent(found::add);
        concentration(metrics, asOf).ifPresent(found::add);
        freshness(metrics, asOf).ifPresent(found::add);

        found.sort(Comparator.comparing(OperationsInsight::severity));
        return found.size() > MAX_INSIGHTS ? found.subList(0, MAX_INSIGHTS) : found;
    }

    /** 미답변 문의가 있는가. The backlog is a current state, so it needs no window — both period fields null. */
    private java.util.Optional<OperationsInsight> backlog(OperationsMetricsResponse metrics, LocalDate asOf) {
        long unanswered = metrics.channels().stream()
                .filter(ChannelMetricRow::countedInInquiries)
                .mapToLong(ChannelMetricRow::unansweredInquiries)
                .sum();
        if (unanswered <= 0) {
            return java.util.Optional.empty();
        }
        ChannelMetricRow worst = metrics.channels().stream()
                .filter(ChannelMetricRow::countedInInquiries)
                .max(Comparator.comparingLong(ChannelMetricRow::unansweredInquiries))
                .orElse(null);
        String detail = worst == null || worst.unansweredInquiries() <= 0 ? null
                : worst.channelNameKo() + " " + worst.unansweredInquiries() + "건이 가장 많습니다.";
        return java.util.Optional.of(new OperationsInsight("INQUIRY_BACKLOG",
                OperationsInsight.Severity.ATTENTION,
                "답변이 필요한 문의 " + unanswered + "건", detail, "/inquiries", "문의 열기",
                "답변이 필요한 문의를 채널별로 정리해 줘",
                // A standing backlog, measured now. No window, and nothing it is compared against —
                // it states a level, which is why a 「무엇이 달라졌나」 surface must not draw it.
                null, null, null, null, asOf));
    }

    /**
     * 최근 창에 부정 리뷰가 한 상품에 몰려 있는가 — <b>그리고 그 전 창보다 많은가.</b>
     *
     * <p>이 producer 는 전기간 누계를 읽고 있었다 ({@code DashboardService.topProductIssues}), 그래서
     * Home 의 「오늘 달라진 점」 아래에 {@code 2025-11-01 ~ 2026-03-06} 이 그대로 출력됐다. 창이 없는
     * 숫자는 변화가 아니다. 지금은 {@link RecentNegativeProduct} 가 {@code MetricPeriod} 의 두 창으로
     * 재어 온 값을 그대로 쓴다 — 이 service 는 세지도, 비교 기준을 고르지도 않는다
     * (product-owner decision, 2026-10-01).
     *
     * <p>{@code detail} 은 두 창을 **둘 다** 말한다. 기준 없는 델타는 측정이 아니라는 것이
     * {@code MetricPeriod} 자신의 규칙이고, 이전 창이 0건인 것도 측정된 사실이므로 숨기지 않는다.
     */
    private java.util.Optional<OperationsInsight> negativeProduct(List<RecentNegativeProduct> recent) {
        RecentNegativeProduct worst = recent.stream()
                .filter(r -> r.current() > 1)
                .max(Comparator.comparingLong(RecentNegativeProduct::current))
                .orElse(null);
        if (worst == null) {
            return java.util.Optional.empty();
        }
        // The name may be null — a review can point at a product the catalogue read does not return.
        // "-" would be a label nobody can act on, so the id-bearing route carries the meaning instead.
        String name = worst.productName() == null ? "이름을 확인하지 못한 상품" : worst.productName();
        int days = (int) (worst.periodEnd().toEpochDay() - worst.periodStart().toEpochDay()) + 1;
        return java.util.Optional.of(new OperationsInsight("NEGATIVE_REVIEW_PRODUCT",
                OperationsInsight.Severity.ATTENTION,
                name + " 부정 리뷰 " + worst.current() + "건",
                "최근 " + days + "일 · 이전 " + days + "일 " + worst.previous() + "건",
                "/products/" + worst.productId(), "상품 열기",
                name + " 리뷰에서 반복되는 문제를 알려 줘",
                // Both windows: this is the one producer whose claim is a CHANGE, proven by the
                // comparison `MetricPeriod` already declares (최근 N일 vs 이전 N일).
                worst.periodStart(), worst.periodEnd(),
                worst.previousPeriodStart(), worst.previousPeriodEnd(),
                worst.lastNegativeOn()));
    }

    /** 같은 문제가 반복되는가 — the extractor's own verdict, never a count of raw reviews. */
    private java.util.Optional<OperationsInsight> repeatedIssue(UUID orgId) {
        List<ReviewIssue> open = issues.findByOrgIdAndDismissedFalse(orgId);
        if (open.isEmpty()) {
            return java.util.Optional.empty();
        }
        ReviewIssue newest = open.stream()
                .filter(i -> i.getLastEvidenceOn() != null)
                .max(Comparator.comparing(ReviewIssue::getLastEvidenceOn))
                .orElse(open.get(0));
        // <b>The extractor's own evidence date, and nothing else.</b> An open issue whose newest
        // evidence is three days old was not observed today, and a Home that says 「오늘」 has to be
        // able to tell. An issue with no evidence date at all yields nothing rather than today's —
        // dating a record by when we happened to read it is the fiction this field exists to stop.
        if (newest.getLastEvidenceOn() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new OperationsInsight("REPEATED_REVIEW_ISSUE",
                OperationsInsight.Severity.WATCH,
                "반복되는 리뷰 문제 " + open.size() + "건",
                newest.getTitle(), "/memory", "반복 문제 보기",
                "반복되는 리뷰 문제를 상품별로 정리해 줘",
                // An open-issue count is a standing state. It is dated by the extractor's own newest
                // evidence so a reader can see how fresh it is — not so a screen can call it today's.
                null, null, null, null, newest.getLastEvidenceOn()));
    }

    /** 한 채널에 매출이 몰려 있는가. Only over channels actually counted in the total. */
    private java.util.Optional<OperationsInsight> concentration(OperationsMetricsResponse metrics, LocalDate asOf) {
        List<ChannelMetricRow> counted = metrics.channels().stream()
                .filter(ChannelMetricRow::countedInOrders)
                .filter(row -> row.revenue() > 0)
                .toList();
        // With one channel there is nothing to concentrate INTO; the word would be meaningless.
        if (counted.size() < 2) {
            return java.util.Optional.empty();
        }
        long total = counted.stream().mapToLong(ChannelMetricRow::revenue).sum();
        ChannelMetricRow top = counted.stream()
                .max(Comparator.comparingLong(ChannelMetricRow::revenue))
                .orElseThrow();
        double share = total == 0 ? 0 : (double) top.revenue() / total;
        if (share < CONCENTRATION_THRESHOLD) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new OperationsInsight("CHANNEL_CONCENTRATION",
                OperationsInsight.Severity.INFO,
                Korean.withSubject(top.channelNameKo()) + " 매출의 " + Math.round(share * 100) + "%",
                "최근 " + metrics.period().days() + "일 기준입니다.", "/orders", "주문 보기",
                "채널별 매출 비중을 알려 줘",
                // A share WITHIN one window. The window is stated because the figure depends on it,
                // and no baseline is — a concentration is a level, not a movement, so this never
                // qualifies as a change.
                metrics.period().from(), metrics.period().to(), null, null, asOf));
    }

    /**
     * 어떤 채널이 지금 말을 못 하는가.
     *
     * <p>Reported as one card, not one per excluded row: a disconnected channel excludes itself from
     * three totals at once, and three identical cards would push everything else off the screen.
     */
    private java.util.Optional<OperationsInsight> freshness(OperationsMetricsResponse metrics, LocalDate asOf) {
        List<ChannelMetricRow> broken = metrics.channels().stream()
                .filter(row -> row.orderState() == ChannelDataState.BLOCKED
                        || row.inquiryState() == ChannelDataState.BLOCKED
                        || row.reviewState() == ChannelDataState.BLOCKED)
                .toList();
        if (broken.isEmpty()) {
            return java.util.Optional.empty();
        }
        String names = broken.stream().map(ChannelMetricRow::channelNameKo).distinct()
                .reduce((a, b) -> a + " · " + b).orElse("");
        return java.util.Optional.of(new OperationsInsight("CHANNEL_NOT_REPORTING",
                OperationsInsight.Severity.ATTENTION,
                names + " 연결이 끊겼습니다",
                // <b>What is missing is what came AFTER the last successful read — not what we hold.</b>
                // The first version of this line said the channel's data was excluded from the numbers
                // above, and on the very first live read that was false: NAVER contributed 48 orders,
                // 45 reviews and 1 inquiry to those totals from rows collected before the credential
                // broke. A caveat that overstates its own reach teaches a seller to ignore caveats.
                "마지막 수집 이후에 생긴 것은 아직 반영되지 않았습니다.", "/connect", "채널 연결 열기",
                // <b>A connection state, not a change</b> (product-owner decision, 2026-10-01). A
                // channel disconnected three weeks ago is observed to be disconnected on every read;
                // `observedAt` dates the observation and proves nothing about today. Collection
                // health — disconnection, sync failure, stale collection — is owned by the Home's
                // 채널 상태 section alone, and this card stays on `/overview` where it was.
                null, null, null, null, null, asOf));
    }
}
