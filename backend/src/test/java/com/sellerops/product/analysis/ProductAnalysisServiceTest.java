package com.sellerops.product.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.itemanalysis.ItemAnalysis;
import com.sellerops.itemanalysis.ItemAnalysisRepository;
import com.sellerops.product.ProductQueryService;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductSignalsService;
import com.sellerops.product.analysis.ProductAnalysisView.Cell;
import com.sellerops.product.dto.ProductSummaryView;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.RuleBasedIssueSignatureExtractor;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상품·브랜드 분석 (night unit ②, 2026-11-16): counts carry their denominators, strengths come from what
 * customers wrote and never from the rating, opportunities and threats are hypotheses naming the observation
 * they came from, and review and inquiry counts are never mixed.
 */
class ProductAnalysisServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    final UUID org = UUID.randomUUID();
    final UUID productId = UUID.randomUUID();
    final UUID naver = UUID.randomUUID();
    final ProductQueryService productQuery = mock(ProductQueryService.class);
    final ProductRepository products = mock(ProductRepository.class);
    final ReviewRepository reviews = mock(ReviewRepository.class);
    final InquiryRepository inquiries = mock(InquiryRepository.class);
    final ItemAnalysisRepository analyses = mock(ItemAnalysisRepository.class);
    final ChannelRepository channels = mock(ChannelRepository.class);
    final ProductSignalsService signals = mock(ProductSignalsService.class);
    final List<Review> reviewRows = new ArrayList<>();
    final List<Inquiry> inquiryRows = new ArrayList<>();
    final List<ItemAnalysis> analysisRows = new ArrayList<>();
    ProductAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ProductAnalysisService(productQuery, products, reviews, inquiries, analyses, channels, signals,
                new ReviewOpinionReader(new RuleBasedIssueSignatureExtractor(false)));
        when(productQuery.byId(org, productId)).thenReturn(Optional.of(
                new ProductSummaryView(productId, "[합성] 원목 선반", null, null, null, null)));
        when(reviews.findByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), eq(productId), any(), any(), any())).thenReturn(reviewRows);
        when(reviews.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                eq(org), eq(productId), any(), any())).thenAnswer(i -> (long) reviewRows.size());
        when(inquiries.findByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), eq(productId), any(), any(), any())).thenReturn(inquiryRows);
        when(inquiries.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                eq(org), eq(productId), any(), any())).thenAnswer(i -> (long) inquiryRows.size());
        when(analyses.findByOrgIdAndSourceTypeAndSourceIdIn(eq(org), eq("INQUIRY"), anyCollection()))
                .thenReturn(analysisRows);
        Channel c = new Channel();
        c.setId(naver);
        c.setCode("NAVER");
        when(channels.findAllById(any())).thenReturn(List.of(c));
        when(signals.issuesFor(any(), any(), any())).thenReturn(List.of());
    }

    void review(int rating, String body, int daysAgo) {
        Review r = new Review();
        r.setId(UUID.randomUUID());
        r.setOrgId(org);
        r.setProductId(productId);
        r.setChannelId(naver);
        r.setRating(rating);
        r.setBody(body);
        r.setReceivedAt(TODAY.minusDays(daysAgo).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
        reviewRows.add(r);
    }

    void inquiry(String body, String category) {
        Inquiry i = new Inquiry();
        i.setId(UUID.randomUUID());
        i.setOrgId(org);
        i.setProductId(productId);
        i.setChannelId(naver);
        i.setBody(body);
        i.setReceivedAt(TODAY.minusDays(3).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        inquiryRows.add(i);
        if (category != null) {
            ItemAnalysis a = new ItemAnalysis();
            a.setSourceId(i.getId());
            a.setCategory(category);
            analysisRows.add(a);
        }
    }

    ProductAnalysisView analyse() {
        return service.analyse(org, productId, 90, TODAY);
    }

    @Test
    @DisplayName("a strength is praise customers wrote about an aspect, with count and denominator; 5★ alone is none")
    void strengthFromWordsNotStars() {
        review(5, "조립이 쉬워요. 설명서도 자세해요", 10);
        review(5, "설치가 간편해서 좋아요", 20);
        review(4, "조립 쉽고 튼튼해요", 30);
        review(5, "너무 좋아요", 40);
        review(5, "최고예요 추천합니다", 50);
        review(5, "만족합니다", 60);
        ProductAnalysisView v = analyse();

        assertThat(v.scope().enoughReviews()).isTrue();
        assertThat(v.swot().strengths()).extracting(Cell::title).containsExactly("설치 칭찬");
        Cell s = v.swot().strengths().get(0);
        assertThat(s.kind()).isEqualTo("OBSERVED");
        assertThat(s.count()).isEqualTo(3);
        assertThat(s.denominator()).isEqualTo(6);
        // three 5★ reviews praise nothing in particular: counted, said, and not a strength
        assertThat(v.scope().unattributedPraiseReviews()).isEqualTo(3);
        assertThat(v.aspects()).filteredOn(a -> a.aspect().equals("설치")).singleElement()
                .satisfies(a -> assertThat(a.praiseQuotes()).hasSize(3)
                        .allSatisfy(q -> assertThat(q.reviewId()).isNotNull()));
        assertThat(v.method().kind()).isEqualTo("RULE_BASED");
    }

    @Test
    @DisplayName("too few reviews: no strength or weakness is named, and the screen is told why")
    void insufficientSample() {
        review(5, "설치가 쉬워요", 1);
        review(5, "설치가 간편해요", 2);
        review(5, "설치 쉬웠어요", 3);
        ProductAnalysisView v = analyse();
        assertThat(v.swot().strengths()).isEmpty();
        assertThat(v.swot().weaknesses()).isEmpty();
        assertThat(v.swot().insufficient()).contains("3건").contains("5건부터");
        // the counts themselves are still shown
        assertThat(v.aspects()).extracting(ProductAnalysisView.AspectRow::praiseReviews).containsExactly(3L);
    }

    @Test
    @DisplayName("a severe repeated complaint is a weakness and yields a threat HYPOTHESIS that names it")
    void weaknessAndThreat() {
        review(1, "포장이 찢어져서 왔어요", 5);
        review(2, "박스가 찌그러져 있었어요", 10);
        review(5, "배송이 빨라요", 15);
        review(5, "배송 빠르고 좋아요", 70);
        review(4, "배송이 빨리 왔어요", 80);
        ProductAnalysisView v = analyse();
        Cell w = v.swot().weaknesses().get(0);
        assertThat(w.key()).isEqualTo("W:포장");
        assertThat(w.count()).isEqualTo(2);
        assertThat(w.denominator()).isEqualTo(5);
        assertThat(v.swot().threats()).isNotEmpty().allSatisfy(t -> {
            assertThat(t.kind()).isEqualTo("HYPOTHESIS");
            assertThat(t.derivedFrom()).containsExactly("W:포장");
            assertThat(t.check()).isNotBlank();
        });
        // both complaints fall in the later half of 90 days: the rising hypothesis says so with both halves
        assertThat(v.swot().threats()).extracting(Cell::key).contains("T:포장:rising");
        assertThat(v.swot().opportunities()).extracting(Cell::key).containsExactly("O:배송");
    }

    @Test
    @DisplayName("contrast: an aspect with praise but as many complaints is not a strength")
    void mixedIsNotAStrength() {
        review(5, "포장이 꼼꼼해요", 5);
        review(5, "포장이 튼튼해요", 6);
        review(5, "포장 깔끔해요", 7);
        review(1, "포장이 찢어져서 왔어요", 8);
        review(2, "박스가 찌그러졌어요", 9);
        ProductAnalysisView v = analyse();
        assertThat(v.swot().strengths()).isEmpty();
        assertThat(v.swot().weaknesses()).extracting(Cell::key).containsExactly("W:포장");
    }

    @Test
    @DisplayName("inquiry topics use the inquiry denominator, never the review one; 기타 is not a weakness")
    void inquiryTopicsOwnDenominator() {
        for (int i = 0; i < 5; i++) {
            review(5, "배송이 빨라요", i + 1);
        }
        inquiry("사이즈가 몇 cm인가요?", "사이즈");
        inquiry("가로 길이 알려주세요", "사이즈");
        inquiry("안녕하세요", "기타");
        inquiry("기타 문의", "기타");
        inquiry("분석 안 된 문의", null);
        ProductAnalysisView v = analyse();
        Cell w = v.swot().weaknesses().stream().filter(c -> c.source().equals("INQUIRY")).findFirst().orElseThrow();
        assertThat(w.title()).isEqualTo("사이즈 문의 반복");
        assertThat(w.count()).isEqualTo(2);
        assertThat(w.denominator()).isEqualTo(5);
        assertThat(v.swot().weaknesses()).extracting(Cell::key).doesNotContain("W:inquiry:기타");
        assertThat(v.swot().opportunities()).extracting(Cell::title).contains("상세페이지에 사이즈 정보 보강");
        assertThat(v.scope().inquiriesUnclassified()).isEqualTo(1);
        assertThat(v.scope().channels()).singleElement()
                .satisfies(c -> {
                    assertThat(c.reviews()).isEqualTo(5);
                    assertThat(c.inquiries()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("structure: no field carries competitor, market or share data")
    void noCompetitorFields() {
        for (Class<?> type : List.of(ProductAnalysisView.class, ProductAnalysisView.Comparison.class,
                ProductAnalysisView.ProductPoint.class, ProductAnalysisView.Cell.class, ProductAnalysisView.Scope.class)) {
            assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
                    .noneMatch(n -> n.matches("(?i).*(competitor|market|share|rival|benchmark).*"));
        }
    }

    @Test
    @DisplayName("store comparison: praise and complaint rates share one review denominator; inquiries are separate")
    void comparison() {
        UUID other = UUID.randomUUID();
        review(5, "설치가 쉬워요", 1);
        review(1, "포장이 찢어져서 왔어요", 2);
        Review r = new Review();
        r.setId(UUID.randomUUID());
        r.setProductId(other);
        r.setBody("배송이 늦었어요");
        r.setReceivedAt(TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        List<Review> all = new ArrayList<>(reviewRows);
        all.add(r);
        when(reviews.findByOrgIdAndProductIdIsNotNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), any(), any(), any())).thenReturn(all);
        when(reviews.countByOrgIdAndProductIdIsNotNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                eq(org), any(), any())).thenReturn(3L);
        List<Object[]> asked = new ArrayList<>();
        asked.add(new Object[] {other, 4L});
        when(inquiries.countByProductInPeriod(eq(org), any(), any())).thenReturn(asked);
        when(products.findIdNamesByOrgIdAndIdIn(eq(org), anyCollection())).thenReturn(List.of(
                new com.sellerops.product.ProductIdNameRow(productId, "A"),
                new com.sellerops.product.ProductIdNameRow(other, "B")));

        // the (미지정 상품) bucket — here "other" — is not a product: listed nowhere, counted as unlinked
        when(products.findIdsByOrgIdAndName(org, "(미지정 상품)")).thenReturn(List.of(other));
        ProductAnalysisView.Comparison bucketed = service.compare(org, 90, TODAY);
        assertThat(bucketed.products()).extracting(ProductAnalysisView.ProductPoint::name).containsExactly("A");
        assertThat(bucketed.unspecifiedReviews()).isEqualTo(1);
        assertThat(bucketed.unspecifiedInquiries()).isEqualTo(4);
        // contrast: productId=null rows are a different line and stay 0 here
        assertThat(bucketed.unlinkedReviews()).isZero();
        assertThat(bucketed.unlinkedInquiries()).isZero();
        when(products.findIdsByOrgIdAndName(org, "(미지정 상품)")).thenReturn(List.of());

        ProductAnalysisView.Comparison c = service.compare(org, 90, TODAY);
        assertThat(c.products()).extracting(ProductAnalysisView.ProductPoint::name).containsExactly("A", "B");
        ProductAnalysisView.ProductPoint pa = c.products().get(0);
        assertThat(pa.reviews()).isEqualTo(2);
        assertThat(pa.praiseReviews()).isEqualTo(1);
        assertThat(pa.complaintReviews()).isEqualTo(1);
        assertThat(pa.enough()).isFalse();
        assertThat(c.products().get(1).inquiries()).isEqualTo(4);
        assertThat(c.reviewsCapped()).isFalse();
    }

    @Test
    @DisplayName("cap: when the inquiry read is bound, the scope says so and every count is over the rows read")
    void inquiryCapIsReported() {
        for (int i = 0; i < 5; i++) {
            review(5, "배송이 빨라요", i + 1);
        }
        inquiry("사이즈가 몇 cm인가요?", "사이즈");
        inquiry("가로 길이 알려주세요", "사이즈");
        inquiry("세로 길이는요", "사이즈");
        // 3 rows came back, but the period holds 2,500 — the read cap bound
        when(inquiries.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                eq(org), eq(productId), any(), any())).thenReturn(2500L);
        ProductAnalysisView v = analyse();
        assertThat(v.scope().inquiries()).isEqualTo(2500);
        assertThat(v.scope().inquiriesRead()).isEqualTo(3);
        assertThat(v.scope().inquiriesCapped()).isTrue();
        Cell w = v.swot().weaknesses().stream().filter(c -> c.source().equals("INQUIRY")).findFirst().orElseThrow();
        assertThat(w.denominator()).isEqualTo(3);
        assertThat(w.basis()).startsWith("최근 읽은 문의 3건 중");
        // contrast: reviews were not capped, so their basis carries no such words
        assertThat(v.scope().reviewsCapped()).isFalse();
        assertThat(v.swot().strengths().get(0).basis()).startsWith("리뷰 5건 중");
    }

    @Test
    @DisplayName("store: one org read, unlinked rows included and counted, empty seller channels named")
    void storeScope() {
        review(5, "배송이 빨라요", 1);
        review(5, "배송 빠르고 좋아요", 2);
        review(4, "배송이 빨리 왔어요", 3);
        review(1, "포장이 찢어져서 왔어요", 4);
        Review unlinked = new Review();
        unlinked.setId(UUID.randomUUID());
        unlinked.setChannelId(naver);
        unlinked.setBody("박스가 찌그러져 있었어요");
        unlinked.setReceivedAt(TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        List<Review> all = new ArrayList<>(reviewRows);
        all.add(unlinked);
        when(reviews.findByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), any(), any(), any())).thenReturn(all);
        when(reviews.countByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(eq(org), any(), any()))
                .thenReturn(5L);
        when(reviews.countByOrgIdAndProductIdIsNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                eq(org), any(), any())).thenReturn(1L);
        when(inquiries.findByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), any(), any(), any())).thenReturn(List.of());
        Channel nv = new Channel();
        nv.setId(naver);
        nv.setCode("NAVER");
        Channel cp = new Channel();
        cp.setId(UUID.randomUUID());
        cp.setCode("COUPANG");
        Channel esm = new Channel();
        esm.setId(UUID.randomUUID());
        esm.setCode("ESM");
        when(channels.findAllByOrderBySortOrderAsc()).thenReturn(List.of(nv, cp, esm));
        when(reviews.findDistinctChannelIdsByOrgId(org)).thenReturn(List.of(naver, cp.getId()));
        // the whole period: NAVER 5 reviews; COUPANG none in the period (it had rows before)
        List<Object[]> byChannel = new ArrayList<>();
        byChannel.add(new Object[] {naver, 5L});
        when(reviews.countByChannelInPeriod(eq(org), any(), any())).thenReturn(byChannel);

        UUID bucket = UUID.randomUUID();
        when(products.findIdsByOrgIdAndName(org, "(미지정 상품)")).thenReturn(List.of(bucket));
        when(reviews.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                eq(org), eq(bucket), any(), any())).thenReturn(2L);
        ProductAnalysisView v = service.analyseStore(org, 90, TODAY);
        // the (미지정 상품) bucket is its own line, never summed into productId=null
        assertThat(v.scope().unspecifiedReviews()).isEqualTo(2);
        assertThat(v.productId()).isNull();
        assertThat(v.scope().range()).isEqualTo("STORE");
        assertThat(v.scope().unlinkedIncluded()).isTrue();
        assertThat(v.scope().unlinkedReviews()).isEqualTo(1);
        assertThat(v.scope().reviewsRead()).isEqualTo(5);
        // the unlinked review's 포장 complaint is part of the store's weakness
        assertThat(v.swot().weaknesses()).extracting(Cell::key).containsExactly("W:포장");
        assertThat(v.swot().weaknesses().get(0).count()).isEqualTo(2);
        // COUPANG had rows before but none in the period; ESM is not a seller-visible channel
        assertThat(v.scope().gaps()).singleElement().satisfies(g -> {
            assertThat(g.channelCode()).isEqualTo("COUPANG");
            assertThat(g.everHadRows()).isTrue();
        });
        // contrast: a product reading leaves unlinked rows out and names no channel gaps
        ProductAnalysisView p = analyse();
        assertThat(p.scope().unlinkedIncluded()).isFalse();
        assertThat(p.scope().gaps()).isEmpty();
        assertThat(p.swot().weaknesses()).isEmpty();
    }

    @Test
    @DisplayName("gap contrast: a channel the capped read missed is 'not read this time', not 'no data'")
    void cappedReadIsNotAGap() {
        Channel nv = new Channel();
        nv.setId(naver);
        nv.setCode("NAVER");
        Channel cp = new Channel();
        cp.setId(UUID.randomUUID());
        cp.setCode("COUPANG");
        when(channels.findAllByOrderBySortOrderAsc()).thenReturn(List.of(nv, cp));
        review(5, "배송이 빨라요", 1);
        when(reviews.findByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), any(), any(), any())).thenReturn(reviewRows);
        when(reviews.countByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(eq(org), any(), any()))
                .thenReturn(6000L);
        when(inquiries.findByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), any(), any(), any())).thenReturn(List.of());
        List<Object[]> byChannel = new ArrayList<>();
        byChannel.add(new Object[] {naver, 5000L});
        byChannel.add(new Object[] {cp.getId(), 1000L});
        when(reviews.countByChannelInPeriod(eq(org), any(), any())).thenReturn(byChannel);

        ProductAnalysisView v = service.analyseStore(org, 90, TODAY);
        assertThat(v.scope().reviewsCapped()).isTrue();
        assertThat(v.scope().gaps()).isEmpty();
        assertThat(v.scope().channels()).filteredOn(c -> c.channelCode().equals("COUPANG")).singleElement()
                .satisfies(c -> {
                    assertThat(c.reviews()).isEqualTo(1000);
                    assertThat(c.reviewsRead()).isZero();
                });
    }

    @Test
    @DisplayName("previews are sanitized first (phone · mail · order number), then shortened; clip alone would leak")
    void previewsAreSanitizedBeforeShortening() {
        String raw = "포장이 찢어져서 왔어요 연락은 010-1234-5678 이나 buyer@example.com 으로 주세요 주문번호 2026111600012345";
        for (int i = 0; i < 5; i++) {
            review(1, raw, i + 1);
        }
        inquiry("교환 원해요 010-9876-5432 로 전화 주세요", "교환");
        ProductAnalysisView v = analyse();
        ProductAnalysisView.Quote q = v.aspects().get(0).complaintQuotes().get(0);
        assertThat(q.text()).doesNotContain("010-1234-5678").doesNotContain("buyer@example.com")
                .doesNotContain("2026111600012345");
        assertThat(q.text().length()).isLessThanOrEqualTo(ProductAnalysisService.QUOTE_CHARS);
        assertThat(v.inquiryTopics().get(0).examples().get(0).text()).doesNotContain("010-9876-5432");
        // contrast: shortening alone keeps the number on screen — the order is the point
        assertThat(ProductAnalysisService.clip(raw)).contains("010-1234-5678");
    }

    @Test
    @DisplayName("only the offered periods are accepted")
    void periods() {
        assertThat(ProductAnalysisService.days(null)).isEqualTo(90);
        assertThat(ProductAnalysisService.days(365)).isEqualTo(365);
        assertThatThrownBy(() -> ProductAnalysisService.days(45)).isInstanceOf(IllegalArgumentException.class);
    }
}
