package com.sellerops.dashboard;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ProductChannels;
import com.sellerops.dashboard.dto.DashboardCards;
import com.sellerops.dashboard.dto.DashboardSummaryResponse;
import com.sellerops.dashboard.dto.RecentNegativeProduct;
import com.sellerops.dashboard.dto.TopProductIssue;
import com.sellerops.dashboard.metrics.dto.MetricPeriod;
import com.sellerops.inbox.InboxService;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.OrderDailySummaryRepository;
import com.sellerops.order.OrderService;
import com.sellerops.order.dto.OrderSummaryResponse;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final InquiryRepository inquiries;
    private final ReviewRepository reviews;
    private final OrderDailySummaryRepository orders;
    private final ProductRepository products;
    private final OrderService orderService;
    private final InboxService inboxService;
    /**
     * Only to answer 「does the product support this channel」 for {@link #recentNegativeProducts} —
     * a review carries a channel id, and {@link ProductChannels} is declared in codes.
     */
    private final ChannelRepository channels;

    public DashboardService(InquiryRepository inquiries, ReviewRepository reviews,
                            OrderDailySummaryRepository orders, ProductRepository products,
                            OrderService orderService, InboxService inboxService,
                            ChannelRepository channels) {
        this.inquiries = inquiries;
        this.reviews = reviews;
        this.orders = orders;
        this.products = products;
        this.orderService = orderService;
        this.inboxService = inboxService;
        this.channels = channels;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(UUID orgId) {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        LocalDate today = LocalDate.now();

        // 미답변 문의 counts the seller's WORKLOAD, so a 비밀글 counts: it is an inquiry someone has
        // to answer, and being secret says who may READ it, not whether it is work. This card used to
        // subtract them and published the result under the same name the overview KPI uses for the
        // whole corpus — two numbers, one label, and no way for a seller to tell which was wrong.
        // Inquiries the seller dismissed and rows excluded as source thread replies are still out:
        // the repository's ACTIVE predicate carries that, so 홈, Today Inbox, the report and the
        // Operator all count the same corpus.
        long unanswered = inquiries.countByOrgIdAndStatus(orgId, "UNANSWERED");
        long negative = reviews.countByOrgIdAndNegativeTrue(orgId);

        int todayOrders = 0;
        long todaySales = 0;
        for (var row : orders.findAllByOrgIdAndSummaryDate(orgId, today)) {
            todayOrders += row.getOrderCount();
            todaySales += row.getSalesAmount();
        }

        DashboardCards cards = new DashboardCards(
                todayOrders,
                todaySales,
                // Same reason as 미답변 above: a 비밀글 that arrived today is work that arrived today.
                inquiries.countByOrgIdAndReceivedAtAfter(orgId, since),
                unanswered,
                reviews.countByOrgIdAndReceivedAtAfter(orgId, since),
                negative);

        OrderSummaryResponse orderSummary = orderService.summary(orgId);

        return new DashboardSummaryResponse(
                cards,
                buildTodoItems(unanswered, negative),
                buildTopProductIssues(orgId),
                inboxService.recentFeed(orgId, 8, false),
                orderSummary.trend(),
                orderSummary.channelShare());
    }

    private List<String> buildTodoItems(long unanswered, long negative) {
        List<String> items = new ArrayList<>();
        if (unanswered > 0) {
            items.add("미답변 문의 " + unanswered + "건을 확인하세요.");
        }
        if (negative > 0) {
            items.add("부정 리뷰 " + negative + "건을 확인하세요.");
        }
        if (items.isEmpty()) {
            items.add("오늘 급히 확인할 일이 없습니다.");
        }
        return items;
    }

    /**
     * The negative-review roll-up, unchanged in what it counts.
     *
     * <p>Same corpus (every review this org holds that the read filters admit), same predicate
     * ({@link Review#isNegative()}), same grouping key (the canonical {@code product_id}), same
     * order and same top-5 cap as before. What is added is what the rows already knew and the DTO
     * dropped: the canonical id, and the span of the very rows being counted.
     *
     * <p>Reviews with no product link are excluded rather than grouped under a null key — a gap in
     * product mapping is not a product, the same rule {@code ProductEvidenceCount} states for issue
     * evidence.
     */
    /**
     * Public so the Overview's insights can rest on the SAME roll-up the summary card shows.
     *
     * <p>A second implementation of "which product has the most negative reviews" is a second answer,
     * and the two would diverge the first time either grouping key changed.
     */
    @Transactional(readOnly = true)
    public List<TopProductIssue> topProductIssues(UUID orgId) {
        return buildTopProductIssues(orgId);
    }

    /**
     * The same population, <b>windowed and compared</b> — 최근 N일 vs 이전 N일.
     *
     * <p>{@link #topProductIssues} is a lifetime roll-up and stays one: the legacy summary card is
     * about the shop's whole history. What a 「오늘 달라진 점」 heading needs is a CHANGE, and a change
     * needs two windows — which this product already declares, in {@code MetricPeriod}, whose own
     * docblock says 「최근 7일 vs 이전 7일」. No window is invented here and no cutoff is chosen: the
     * period is handed in by the caller that already computed it for every other metric on the same
     * screen (product-owner decision, 2026-10-01).
     *
     * <p>REAL only, negative only, product-bearing only — the same three filters
     * {@link #buildTopProductIssues} applies, for the reasons stated there. A manufactured row may
     * appear in a chart of what the shop did, never in a number that tells a seller they have a
     * problem.
     *
     * <p>Ordered by the current window's count, descending. A product with nothing in the current
     * window is left out: a row for it would be a claim about a period in which it did nothing.
     */
    @Transactional(readOnly = true)
    public List<RecentNegativeProduct> recentNegativeProducts(UUID orgId, MetricPeriod period) {
        Map<UUID, String> productNames = products.findAllByOrgId(orgId).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName, (a, b) -> a));
        /*
         * <b>Supported channels only</b> (product-owner decision, 2026-10-01).
         *
         * <p>The metrics population this period comes from is {@code OrgChannelVisibility}, which is
         * deliberately WIDER than the connectable set: it adds any channel the org holds rows on, so
         * the numbers screen can account for an uploaded GMARKET corpus. That is right for
         * `/overview` and wrong for a Home finding, which is a thing the product asks the seller to
         * act on — and there is nothing to act on for a channel this product does not connect
         * ({@code ProductChannels}: NAVER / Coupang / Cafe24, `docs/product_assembly_ia_v1.md` §2).
         *
         * <p>Nothing is hidden by this: the reviews are still on the review screens, still in the
         * org's totals, and still in `/overview`'s own lifetime roll-up.
         */
        Set<UUID> supported = channels.findAll().stream()
                .filter(c -> ProductChannels.isVisible(c.getCode()))
                .map(Channel::getId)
                .collect(Collectors.toSet());
        Map<UUID, List<Review>> negativeByProduct = reviews.findAllByOrgId(orgId).stream()
                .filter(r -> r.getDataOrigin() == com.sellerops.common.DataOrigin.REAL)
                .filter(Review::isNegative)
                .filter(r -> r.getProductId() != null)
                .filter(r -> r.getReceivedAt() != null)
                .filter(r -> r.getChannelId() != null && supported.contains(r.getChannelId()))
                .collect(Collectors.groupingBy(Review::getProductId));

        List<RecentNegativeProduct> rows = new ArrayList<>();
        for (Map.Entry<UUID, List<Review>> entry : negativeByProduct.entrySet()) {
            List<LocalDate> dates = entry.getValue().stream().map(DashboardService::receivedOn).toList();
            List<LocalDate> inCurrent = dates.stream()
                    .filter(d -> inWindow(d, period.from(), period.to())).toList();
            if (inCurrent.isEmpty()) {
                continue;
            }
            long previous = dates.stream()
                    .filter(d -> inWindow(d, period.previousFrom(), period.previousTo()))
                    .count();
            rows.add(new RecentNegativeProduct(entry.getKey(), productNames.get(entry.getKey()),
                    inCurrent.size(), previous, period.from(), period.to(),
                    period.previousFrom(), period.previousTo(),
                    inCurrent.stream().max(Comparator.naturalOrder()).orElseThrow()));
        }
        rows.sort(Comparator.comparingLong(RecentNegativeProduct::current).reversed());
        return rows;
    }

    /** Inclusive on both ends, which is what {@code MetricPeriod}'s own field names mean. */
    private static boolean inWindow(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    private List<TopProductIssue> buildTopProductIssues(UUID orgId) {
        Map<UUID, String> productNames = products.findAllByOrgId(orgId).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName, (a, b) -> a));
        /*
         * REAL only (Chat-first Agent Shell Completion v1 §3-C).
         *
         * This list becomes 「{상품} 부정 리뷰 N건」 — a sentence the home briefing states as a fact
         * about the seller's shop, with a date range attached. N is a count of review ROWS, so a
         * seeded row lands inside it, and on this deployment eleven of them do. The rule the previous
         * package wrote for the unanswered count is the same rule here: a manufactured row may appear
         * in a chart of what the shop did, never in a number that tells the seller they have a
         * problem. The dashboard's other reads are untouched — this narrows one insight's corpus, not
         * the review screens or the issue extractor's evidence.
         */
        Map<UUID, List<Review>> negativeByProduct = reviews.findAllByOrgId(orgId).stream()
                .filter(r -> r.getDataOrigin() == com.sellerops.common.DataOrigin.REAL)
                .filter(Review::isNegative)
                .filter(r -> r.getProductId() != null)
                .collect(Collectors.groupingBy(Review::getProductId));

        return negativeByProduct.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, List<Review>>>comparingInt(
                        e -> e.getValue().size()).reversed())
                .limit(5)
                .map(e -> new TopProductIssue(
                        e.getKey(),
                        // Null, not "-": the catalogue either holds a name for this id or it does not.
                        productNames.get(e.getKey()),
                        "부정 리뷰",
                        e.getValue().size(),
                        e.getValue().stream().map(DashboardService::receivedOn)
                                .min(Comparator.naturalOrder()).orElse(null),
                        e.getValue().stream().map(DashboardService::receivedOn)
                                .max(Comparator.naturalOrder()).orElse(null)))
                .toList();
    }

    /**
     * The calendar date a review was received.
     *
     * <p>UTC, because that is the zone the ingest wrote the value in — {@code DateParse
     * .instantAtStartOfDay} pins the channel's calendar date to UTC midnight, so reading it back in
     * UTC recovers that exact date and reading it in another zone would shift some rows by a day.
     * Same rule as {@code ReviewIssueExtractionService.occurredOn}, so the negative roll-up and the
     * issue evidence date the same review identically.
     */
    private static LocalDate receivedOn(Review review) {
        return review.getReceivedAt().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
