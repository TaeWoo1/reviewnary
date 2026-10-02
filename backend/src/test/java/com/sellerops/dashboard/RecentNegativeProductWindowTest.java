package com.sellerops.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.DataOrigin;
import com.sellerops.dashboard.dto.RecentNegativeProduct;
import com.sellerops.dashboard.metrics.dto.MetricPeriod;
import com.sellerops.inbox.InboxService;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.OrderDailySummaryRepository;
import com.sellerops.order.OrderService;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The Home's negative-review finding is a WINDOWED comparison over SUPPORTED channels</b>
 * (product-owner decisions, 2026-10-01).
 *
 * <p>Two defects are pinned here, both measured on the live demo org.
 *
 * <ul>
 *   <li>The producer read the lifetime roll-up ({@code topProductIssues}), so 「부정 리뷰 3건」 arrived
 *       with the range {@code 2025-11-01 ~ 2026-03-06} attached — under a heading asking what changed
 *       this morning. Lifetime aggregates stay on `/overview`; this one is 최근 N일 vs 이전 N일, and
 *       the windows are {@code MetricPeriod}'s rather than anything chosen here.</li>
 *   <li>The corpus was every channel the org holds rows on, which {@code OrgChannelVisibility}
 *       deliberately widens past the connectable set so `/overview` can account for an uploaded
 *       GMARKET corpus. A Home finding asks the seller to act, and there is nothing to act on for a
 *       channel this product does not connect.</li>
 * </ul>
 */
class RecentNegativeProductWindowTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID NAVER = UUID.randomUUID();
    private static final UUID ESM = UUID.randomUUID();

    /** 최근 7일 = 9/25–10/1, 이전 7일 = 9/18–9/24 — the shape `OperationsMetricsService` builds. */
    private static final MetricPeriod PERIOD = new MetricPeriod(
            LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 24), 7);

    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ChannelRepository channels = mock(ChannelRepository.class);

    private final DashboardService service = new DashboardService(
            mock(InquiryRepository.class), reviews, mock(OrderDailySummaryRepository.class), products,
            mock(OrderService.class), mock(InboxService.class), channels);

    private static Channel channel(UUID id, String code) {
        Channel c = new Channel();
        c.setId(id);
        c.setCode(code);
        return c;
    }

    private static Review negative(UUID channelId, LocalDate on) {
        Review r = new Review();
        r.setOrgId(ORG);
        r.setChannelId(channelId);
        r.setProductId(PRODUCT);
        r.setNegative(true);
        r.setDataOrigin(DataOrigin.REAL);
        // UTC, because that is the zone the ingest wrote the value in — the same rule `receivedOn` reads it back by.
        r.setReceivedAt(on.atStartOfDay(ZoneOffset.UTC).toInstant());
        return r;
    }

    private void given(List<Review> rows) {
        Product p = new Product();
        p.setId(PRODUCT);
        p.setName("컵 수거기");
        when(products.findAllByOrgId(ORG)).thenReturn(List.of(p));
        when(channels.findAll()).thenReturn(List.of(channel(NAVER, "NAVER"), channel(ESM, "ESM")));
        when(reviews.findAllByOrgId(ORG)).thenReturn(rows);
    }

    @Test
    void countsTheCurrentWindowAndTheOneBeforeIt_andNothingOutsideEither() {
        given(List.of(
                negative(NAVER, LocalDate.of(2026, 9, 30)),   // current
                negative(NAVER, LocalDate.of(2026, 9, 25)),   // current, first day — inclusive
                negative(NAVER, LocalDate.of(2026, 9, 24)),   // previous, last day — inclusive
                negative(NAVER, LocalDate.of(2026, 9, 18)),   // previous, first day — inclusive
                negative(NAVER, LocalDate.of(2025, 11, 1)))); // the lifetime tail this producer used to count

        RecentNegativeProduct row = service.recentNegativeProducts(ORG, PERIOD).get(0);
        assertThat(row.current()).isEqualTo(2);
        assertThat(row.previous()).isEqualTo(2);
        assertThat(row.periodStart()).isEqualTo(PERIOD.from());
        assertThat(row.periodEnd()).isEqualTo(PERIOD.to());
        assertThat(row.previousPeriodStart()).isEqualTo(PERIOD.previousFrom());
        assertThat(row.previousPeriodEnd()).isEqualTo(PERIOD.previousTo());
        // Dated by the newest review INSIDE the current window, not by the read.
        assertThat(row.lastNegativeOn()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void leavesOutAChannelTheProductDoesNotConnect() {
        // ESM (G마켓/옥션) is in the catalogue and in `OrgChannelVisibility` once the org holds rows on
        // it — and is not one of the three a seller can connect (`ProductChannels`).
        given(List.of(
                negative(ESM, LocalDate.of(2026, 9, 30)),
                negative(ESM, LocalDate.of(2026, 9, 29))));
        assertThat(service.recentNegativeProducts(ORG, PERIOD)).isEmpty();
    }

    @Test
    void aProductWithNothingInTheCurrentWindowIsNotAROw() {
        // A row for it would be a claim about a period in which it did nothing. The previous window
        // having rows is not enough — the finding is 「최근에 몰려 있는가」.
        given(List.of(negative(NAVER, LocalDate.of(2026, 9, 20))));
        assertThat(service.recentNegativeProducts(ORG, PERIOD)).isEmpty();
    }

    @Test
    void aZeroBaselineIsMeasuredRatherThanWithheld() {
        given(List.of(negative(NAVER, LocalDate.of(2026, 9, 30))));
        RecentNegativeProduct row = service.recentNegativeProducts(ORG, PERIOD).get(0);
        assertThat(row.current()).isEqualTo(1);
        // Nothing happened in the span before, and that is a fact about the same-length window.
        assertThat(row.previous()).isZero();
    }
}
