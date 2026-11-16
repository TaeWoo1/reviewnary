package com.sellerops.product.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.FakeAgentLlmTransport;
import com.sellerops.agent.llm.reviewaspect.ReviewAspectProperties;
import com.sellerops.agent.llm.reviewaspect.ReviewAspectReader;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.QuotaDecision;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.ApiException;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.itemanalysis.ItemAnalysisRepository;
import com.sellerops.product.ProductQueryService;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductSignalsService;
import com.sellerops.product.analysis.ProductAnalysisView.Cell;
import com.sellerops.product.dto.ProductSummaryView;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.RuleBasedIssueSignatureExtractor;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「AI로 읽기」 (2026-11-17), with the dev fake model: each distinct review text is read once, a press is bounded, the
 * analysis says how many reviews the AI read, and opportunities/threats are the written ones only while the evidence is
 * the one they were written from.
 */
class ProductAspectReadingServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    final UUID org = UUID.randomUUID();
    final UUID productId = UUID.randomUUID();
    final UUID naver = UUID.randomUUID();
    final ReviewRepository reviews = mock(ReviewRepository.class);
    final InquiryRepository inquiries = mock(InquiryRepository.class);
    final ReviewAspectReadingRepository readingRepo = mock(ReviewAspectReadingRepository.class);
    final ProductOutlookWritingRepository outlookRepo = mock(ProductOutlookWritingRepository.class);
    final AgentQuotaService quota = mock(AgentQuotaService.class);
    final List<Review> rows = new ArrayList<>();
    final List<ReviewAspectReading> stored = new ArrayList<>();
    final List<ProductOutlookWriting> outlooks = new ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    ProductAnalysisService analysis;
    ProductAspectReadingService service;

    ReviewAspectReader reader(boolean enabled) {
        ReviewAspectProperties props = new ReviewAspectProperties(enabled, org.toString(), "OPENAI", "fake-model",
                "test-key", 4000, null, 500, 3, 2);
        FakeAgentLlmTransport fake = new FakeAgentLlmTransport();
        AgentLlmTransport counting = (uri, headers, body) -> {
            calls.incrementAndGet();
            return fake.post(uri, headers, body);
        };
        return new ReviewAspectReader(props, counting, quota);
    }

    void build(boolean enabled) {
        ProductQueryService productQuery = mock(ProductQueryService.class);
        when(productQuery.byId(org, productId)).thenReturn(Optional.of(
                new ProductSummaryView(productId, "[합성] 종이컵 디스펜서", null, null, null, null)));
        ChannelRepository channels = mock(ChannelRepository.class);
        Channel c = new Channel();
        c.setId(naver);
        c.setCode("NAVER");
        when(channels.findAllById(any())).thenReturn(List.of(c));
        ProductSignalsService signals = mock(ProductSignalsService.class);
        when(signals.issuesFor(any(), any(), any())).thenReturn(List.of());
        ItemAnalysisRepository analyses = mock(ItemAnalysisRepository.class);
        when(analyses.findByOrgIdAndSourceTypeAndSourceIdIn(any(), any(), anyCollection())).thenReturn(List.of());
        analysis = new ProductAnalysisService(productQuery, mock(ProductRepository.class), reviews, inquiries, analyses,
                channels, signals, new ReviewOpinionReader(new RuleBasedIssueSignatureExtractor(false)));
        ReviewAspectReader reader = reader(enabled);
        ReviewAspectOverlay overlay = new ReviewAspectOverlay(readingRepo, outlookRepo, reader);
        analysis.setOverlay(overlay);
        service = new ProductAspectReadingService(reader, readingRepo, outlookRepo, overlay, analysis, reviews);
    }

    @BeforeEach
    void setUp() {
        when(reviews.findByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                eq(org), eq(productId), any(), any(), any())).thenReturn(rows);
        when(reviews.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                eq(org), eq(productId), any(), any())).thenAnswer(i -> (long) rows.size());
        when(reviews.findAllById(any())).thenAnswer(i -> {
            Collection<UUID> ids = i.getArgument(0);
            return rows.stream().filter(r -> ids.contains(r.getId())).toList();
        });
        when(inquiries.findByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                any(), any(), any(), any(), any())).thenReturn(List.of());
        when(readingRepo.findAllByOrgIdAndReaderVersionAndBodySha256In(eq(org), anyString(), anyCollection()))
                .thenAnswer(i -> {
                    String version = i.getArgument(1);
                    Collection<String> shas = i.getArgument(2);
                    return stored.stream().filter(r -> r.getReaderVersion().equals(version)
                            && shas.contains(r.getBodySha256())).toList();
                });
        when(readingRepo.save(any())).thenAnswer(i -> {
            stored.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(outlookRepo.findFirstByOrgIdAndProductIdAndInputFingerprintAndWriterVersion(eq(org), eq(productId),
                anyString(), anyString())).thenAnswer(i -> outlooks.stream()
                .filter(w -> w.getInputFingerprint().equals(i.getArgument(2))
                        && w.getWriterVersion().equals(i.getArgument(3))).findFirst());
        when(outlookRepo.save(any())).thenAnswer(i -> {
            outlooks.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(quota.chargeCheck(any(), any(), any(), any()))
                .thenReturn(new AgentQuotaService.Charge(QuotaDecision.passWithoutCharge(), null));
        build(true);
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
        rows.add(r);
    }

    void seven() {
        review(5, "배송이 빨라요", 5);
        review(5, "배송이 빨라요", 6); // the same words: read once
        review(5, "배송 하루 만에 왔어요", 7);
        review(4, "배송 포장 다 좋네요", 8);
        review(5, "배송 최고", 9);
        review(5, "배송 빠르고 좋아요", 10);
        review(1, "배송이 일주일 걸렸어요", 11);
        review(2, "배송 늦어서 별로", 12);
    }

    @Test
    @DisplayName("같은 글은 한 번만 읽고, 한 번 누를 때 호출 상한까지만 읽고 남은 수를 말한다")
    void boundedPressAndOneReadPerText() {
        seven();
        var first = service.readProduct(org, productId, 90, TODAY);
        assertThat(first.calls()).isEqualTo(2);
        assertThat(first.textsRead()).isEqualTo(6);
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(first.stopped()).isEqualTo("CALLS");
        assertThat(first.outlookWritten()).isFalse();
        assertThat(first.analysis().method().kind()).isEqualTo("MIXED");

        var second = service.readProduct(org, productId, 90, TODAY);
        assertThat(second.textsRead()).isEqualTo(1);
        assertThat(second.remaining()).isZero();
        assertThat(second.outlookWritten()).isTrue();
        assertThat(calls.get()).isEqualTo(4);
        ProductAnalysisView v = second.analysis();
        assertThat(v.method().kind()).isEqualTo("AI_READ");
        assertThat(v.method().aiReadReviews()).isEqualTo(8);
        assertThat(v.swot().strengths()).extracting(Cell::key).contains("S:배송");
        assertThat(v.swot().weaknesses()).extracting(Cell::key).contains("W:배송");
        assertThat(v.swot().opportunities()).singleElement().satisfies(c -> {
            assertThat(c.source()).isEqualTo("AI");
            assertThat(c.kind()).isEqualTo("HYPOTHESIS");
            assertThat(c.derivedFrom()).containsExactly("S:배송");
        });
        assertThat(v.swot().threats()).extracting(Cell::source).containsOnly("AI");

        var third = service.readProduct(org, productId, 90, TODAY);
        assertThat(third.calls()).as("nothing new to read or write").isZero();
    }

    @Test
    @DisplayName("근거가 바뀌면 쓴 기회·위협 대신 규칙 문장이 나오고, 다시 눌러야 새로 쓴다")
    void outlookOnlyForTheEvidenceItWasWrittenFrom() {
        seven();
        service.readProduct(org, productId, 90, TODAY);
        service.readProduct(org, productId, 90, TODAY);
        review(1, "배송이 너무 늦게 왔어요", 1);
        ProductAnalysisView v = analysis.analyse(org, productId, 90, TODAY);
        assertThat(v.method().kind()).as("the new review is read by rules until pressed").isEqualTo("MIXED");
        assertThat(v.swot().opportunities()).extracting(Cell::source).doesNotContain("AI");
        var again = service.readProduct(org, productId, 90, TODAY);
        assertThat(again.outlookWritten()).isTrue();
        assertThat(again.analysis().swot().opportunities()).extracting(Cell::source).containsOnly("AI");
    }

    @Test
    @DisplayName("근거 지문은 항목·건수로 정해진다 — 예시 문장 순서가 바뀌어도 같은 글이 다시 쓰이지 않는다")
    void fingerprintIgnoresWhichQuotesWereShown() {
        ReviewAspectOverlay overlay = new ReviewAspectOverlay(readingRepo, outlookRepo, reader(true));
        var a = new com.sellerops.agent.llm.reviewaspect.ReviewAspectGenerator.EvidenceLine("S:배송", "배송 칭찬", 6, 8,
                List.of("배송이 빨라요", "배송 최고"));
        var b = new com.sellerops.agent.llm.reviewaspect.ReviewAspectGenerator.EvidenceLine("S:배송", "배송 칭찬", 6, 8,
                List.of("배송 최고", "배송 하루 만에 왔어요"));
        var c = new com.sellerops.agent.llm.reviewaspect.ReviewAspectGenerator.EvidenceLine("S:배송", "배송 칭찬", 7, 9,
                List.of("배송이 빨라요", "배송 최고"));
        assertThat(overlay.fingerprint(List.of(a))).isEqualTo(overlay.fingerprint(List.of(b)));
        assertThat(overlay.fingerprint(List.of(a))).isNotEqualTo(overlay.fingerprint(List.of(c)));
    }

    @Test
    @DisplayName("한도에 막히면 그 자리에서 멈추고 말한다; 꺼진 가게는 누를 수 없다")
    void quotaStopsAndOffRefuses() {
        seven();
        when(quota.chargeCheck(any(), any(), any(), any())).thenReturn(new AgentQuotaService.Charge(
                new QuotaDecision(false, QuotaDecision.Reason.DAILY_LLM_CALLS, 1000, 1000), null));
        var r = service.readProduct(org, productId, 90, TODAY);
        assertThat(r.stopped()).isEqualTo("QUOTA");
        assertThat(calls.get()).isZero();
        assertThat(r.analysis().method().kind()).isEqualTo("RULE_BASED");

        build(false);
        assertThatThrownBy(() -> service.readProduct(org, productId, 90, TODAY)).isInstanceOf(ApiException.class)
                .hasMessageContaining("켜져 있지 않아요");
    }

    @Test
    @DisplayName("평가용으로 고른 리뷰만 읽고 리뷰마다 읽은 결과를 돌려준다; 다른 가게 리뷰는 빠진다")
    void namedReviewsForTheEvaluation() {
        seven();
        Review foreign = new Review();
        foreign.setId(UUID.randomUUID());
        foreign.setOrgId(UUID.randomUUID());
        foreign.setBody("배송 다른 가게");
        rows.add(foreign);
        var r = service.readReviews(org, List.of(rows.get(0).getId(), rows.get(6).getId(), foreign.getId()));
        assertThat(r.reviews()).hasSize(2);
        assertThat(r.reviews()).allSatisfy(x -> assertThat(x.read()).isTrue());
        assertThat(r.reviews().get(1).opinions()).singleElement().satisfies(o -> {
            assertThat(o.aspect()).isEqualTo("배송");
            assertThat(o.praise()).isFalse();
            assertThat(o.problem()).isEqualTo("지연");
        });
        assertThatThrownBy(() -> service.readReviews(org, List.of())).isInstanceOf(ApiException.class);
    }
}
