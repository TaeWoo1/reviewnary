package com.sellerops.product.analysis;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.itemanalysis.ItemAnalysis;
import com.sellerops.itemanalysis.ItemAnalysisCategories;
import com.sellerops.itemanalysis.ItemAnalysisRepository;
import com.sellerops.product.ProductQueryService;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductSignalsService;
import com.sellerops.product.analysis.ProductAnalysisView.AspectRow;
import com.sellerops.product.analysis.ProductAnalysisView.Cell;
import com.sellerops.product.analysis.ProductAnalysisView.ChannelCount;
import com.sellerops.product.analysis.ProductAnalysisView.ChannelGap;
import com.sellerops.product.analysis.ProductAnalysisView.Comparison;
import com.sellerops.product.analysis.ProductAnalysisView.InquiryRef;
import com.sellerops.product.analysis.ProductAnalysisView.InquiryTopicRow;
import com.sellerops.product.analysis.ProductAnalysisView.Method;
import com.sellerops.product.analysis.ProductAnalysisView.ProductPoint;
import com.sellerops.product.analysis.ProductAnalysisView.Quote;
import com.sellerops.product.analysis.ProductAnalysisView.RatingSpread;
import com.sellerops.product.analysis.ProductAnalysisView.Scope;
import com.sellerops.product.analysis.ProductAnalysisView.Swot;
import com.sellerops.product.dto.ProductSummaryView;
import com.sellerops.review.Review;
import com.sellerops.reviewissue.IssueSeverity;
import com.sellerops.reviewissue.IssueVocabulary;
import com.sellerops.reviewissue.dto.ReviewIssueView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품·브랜드 분석 — strengths, weaknesses and repeated problems of one product, and the store's products
 * compared on the same axes (night handoff unit ②, 2026-11-16).
 *
 * <p><b>Observed vs hypothesis.</b> A strength or weakness is a count of reviews (or inquiries) with the
 * sentences behind it. An opportunity or threat is a rule-made <i>hypothesis</i> that names the observation it
 * came from and how to check it. Nothing here is about competitors or the market: there is no such data in
 * the product, so none is drawn.
 *
 * <p><b>Rule-based, no model call.</b> Complaints are the issue extractor's reading, praise is
 * {@link PraiseVocabulary}, inquiry topics are the stored {@code item_analyses} category. The rating is shown
 * as context and decides nothing.
 */
@Service
public class ProductAnalysisService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Set<Integer> PERIODS = Set.of(30, 90, 180, 365);
    static final int DEFAULT_DAYS = 90;
    /** Below this many reviews in the period no strength or weakness is named. */
    static final int MIN_REVIEWS = 5;
    /** A strength needs this many praising reviews and at least twice as many as complaining ones. */
    static final int STRENGTH_MIN = 3;
    /** A weakness is a complaint repeated in at least this many reviews. */
    static final int WEAKNESS_MIN = 2;
    /** An inquiry topic is repeated from this many inquiries. */
    static final int INQUIRY_REPEAT_MIN = 2;
    static final int PRODUCT_READ_CAP = 2000;
    static final int STORE_READ_CAP = 5000;
    static final int QUOTES_PER_SIDE = 3;
    static final int QUOTE_CHARS = 90;

    private static final String SOURCE_INQUIRY = "INQUIRY";
    private static final Set<String> INFO_TOPICS = Set.of(ItemAnalysisCategories.PRODUCT_INFO,
            ItemAnalysisCategories.SIZE, ItemAnalysisCategories.INSTALLATION, ItemAnalysisCategories.COLOR);

    private final ProductQueryService productQuery;
    private final ProductRepository products;
    private final com.sellerops.review.ReviewRepository reviews;
    private final InquiryRepository inquiries;
    private final ItemAnalysisRepository analyses;
    private final ChannelRepository channels;
    private final ProductSignalsService signals;
    private final ReviewOpinionReader reader;

    public ProductAnalysisService(ProductQueryService productQuery, ProductRepository products,
                                  com.sellerops.review.ReviewRepository reviews, InquiryRepository inquiries,
                                  ItemAnalysisRepository analyses, ChannelRepository channels,
                                  ProductSignalsService signals, ReviewOpinionReader reader) {
        this.productQuery = productQuery;
        this.products = products;
        this.reviews = reviews;
        this.inquiries = inquiries;
        this.analyses = analyses;
        this.channels = channels;
        this.signals = signals;
        this.reader = reader;
    }

    /** The period's days, defaulting and refusing anything but the offered choices. */
    public static int days(Integer requested) {
        if (requested == null) {
            return DEFAULT_DAYS;
        }
        if (!PERIODS.contains(requested)) {
            throw new IllegalArgumentException("기간은 30·90·180·365일 가운데 하나예요.");
        }
        return requested;
    }

    public Method method() {
        return new Method("RULE_BASED", reader.extractorProvenance(), PraiseVocabulary.VERSION,
                "item-analysis:category");
    }

    @Transactional(readOnly = true)
    public ProductAnalysisView analyse(UUID orgId, UUID productId, int days, LocalDate referenceDate) {
        ProductSummaryView product = productQuery.byId(orgId, productId)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
        Period period = Period.of(days, referenceDate);
        long reviewTotal = reviews.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, productId, period.fromAt(), period.toAt());
        List<Review> read = reviews
                .findByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                        orgId, productId, period.fromAt(), period.toAt(), PageRequest.of(0, PRODUCT_READ_CAP));
        long inquiryTotal = inquiries.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, productId, period.fromAt(), period.toAt());
        List<Inquiry> asked = inquiries
                .findByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                        orgId, productId, period.fromAt(), period.toAt(), PageRequest.of(0, PRODUCT_READ_CAP));
        long unlinkedReviews = reviews.countByOrgIdAndProductIdIsNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, period.fromAt(), period.toAt());
        long unlinkedInquiries = inquiries.countByOrgIdAndProductIdIsNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, period.fromAt(), period.toAt());
        long[] unspecified = unspecified(orgId, productId, period);
        return build(orgId, product.id(), product.name(), RANGE_PRODUCT, period, PRODUCT_READ_CAP,
                reviewTotal, read, inquiryTotal, asked, unlinkedReviews, unlinkedInquiries, unspecified, false,
                issueByAspect(orgId, productId, period.to()));
    }

    /**
     * The whole store (brand) over the same period: ONE org-scoped read of reviews and inquiries — product-linked
     * and unlinked alike, said so — rather than a loop over products. Distinct from {@link #compare}, which puts
     * products side by side; this is the store as one subject.
     */
    @Transactional(readOnly = true)
    public ProductAnalysisView analyseStore(UUID orgId, int days, LocalDate referenceDate) {
        Period period = Period.of(days, referenceDate);
        long reviewTotal = reviews.countByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, period.fromAt(), period.toAt());
        List<Review> read = reviews.findByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                orgId, period.fromAt(), period.toAt(), PageRequest.of(0, STORE_READ_CAP));
        long inquiryTotal = inquiries.countByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, period.fromAt(), period.toAt());
        List<Inquiry> asked = inquiries.findByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                orgId, period.fromAt(), period.toAt(), PageRequest.of(0, STORE_READ_CAP));
        long unlinkedReviews = reviews.countByOrgIdAndProductIdIsNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, period.fromAt(), period.toAt());
        long unlinkedInquiries = inquiries.countByOrgIdAndProductIdIsNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, period.fromAt(), period.toAt());
        // Rows ingest filed under the (미지정 상품) bucket carry a product id and no real product. They are part of the
        // store reading and counted on their own line — not merged with productId=null rows, which are a different gap.
        long[] unspecified = unspecified(orgId, null, period);
        return build(orgId, null, null, RANGE_STORE, period, STORE_READ_CAP,
                reviewTotal, read, inquiryTotal, asked, unlinkedReviews, unlinkedInquiries, unspecified, true, Map.of());
    }

    private ProductAnalysisView build(UUID orgId, UUID productId, String name, String range, Period period, int cap,
                                      long reviewTotal, List<Review> read, long inquiryTotal, List<Inquiry> asked,
                                      long unlinkedReviews, long unlinkedInquiries, long[] unspecified,
                                      boolean unlinkedIncluded, Map<String, UUID> issueByAspect) {
        LocalDate half = period.from().plusDays(period.days() / 2L);
        Map<UUID, String> codes = channelCodes(read, asked);

        // ── reviews → aspects
        Map<String, AspectTally> byAspect = new LinkedHashMap<>();
        for (String a : IssueVocabulary.aspects()) {
            byAspect.put(a, new AspectTally());
        }
        long[] stars = new long[5];
        long rated = 0;
        long unattributedPraise = 0;
        Map<String, long[]> channelCounts = new LinkedHashMap<>();
        for (Review r : read) {
            String code = codes.getOrDefault(r.getChannelId(), "OTHER");
            channelCounts.computeIfAbsent(code, k -> new long[2])[0]++;
            if (r.getRating() != null && r.getRating() >= 1 && r.getRating() <= 5) {
                stars[r.getRating() - 1]++;
                rated++;
            }
            LocalDate on = r.getReceivedAt().atZone(KST).toLocalDate();
            Set<String> praised = new HashSet<>();
            Set<String> complained = new HashSet<>();
            boolean praiseWithoutAspect = false;
            for (ReviewOpinionReader.Opinion o : reader.read(r.getBody())) {
                if (o.praise() && o.aspect() == null) {
                    praiseWithoutAspect = true;
                    continue;
                }
                AspectTally t = byAspect.get(o.aspect());
                if (t == null) {
                    continue;
                }
                String safe = preview(o.text());
                Quote q = safe == null ? null : new Quote(r.getId(), safe, r.getRating(), code, on, o.problem());
                if (o.praise()) {
                    if (praised.add(o.aspect())) {
                        t.praise++;
                        if (q != null && t.praiseQuotes.size() < QUOTES_PER_SIDE) {
                            t.praiseQuotes.add(q);
                        }
                    }
                } else {
                    t.problems.add(o.problem());
                    if (complained.add(o.aspect())) {
                        t.complaint++;
                        if (on.isBefore(half)) {
                            t.earlier++;
                        } else {
                            t.recent++;
                        }
                        if (q != null && t.complaintQuotes.size() < QUOTES_PER_SIDE) {
                            t.complaintQuotes.add(q);
                        }
                    }
                }
            }
            if (praiseWithoutAspect && praised.isEmpty()) {
                unattributedPraise++;
            }
        }

        List<AspectRow> aspects = byAspect.entrySet().stream()
                .filter(e -> e.getValue().praise + e.getValue().complaint > 0)
                .map(e -> new AspectRow(e.getKey(), e.getValue().praise, e.getValue().complaint,
                        List.copyOf(e.getValue().problems), issueByAspect.get(e.getKey()),
                        e.getValue().recent, e.getValue().earlier,
                        List.copyOf(e.getValue().praiseQuotes), List.copyOf(e.getValue().complaintQuotes)))
                .sorted(Comparator.comparingLong((AspectRow a) -> a.praiseReviews() + a.complaintReviews()).reversed())
                .toList();

        // ── inquiries → topics (the stored rule-based category)
        Map<UUID, String> category = new HashMap<>();
        if (!asked.isEmpty()) {
            for (ItemAnalysis a : analyses.findByOrgIdAndSourceTypeAndSourceIdIn(orgId, SOURCE_INQUIRY,
                    asked.stream().map(Inquiry::getId).toList())) {
                category.put(a.getSourceId(), a.getCategory());
            }
        }
        Map<String, List<Inquiry>> byTopic = new LinkedHashMap<>();
        long unclassified = 0;
        for (Inquiry i : asked) {
            String code = codes.getOrDefault(i.getChannelId(), "OTHER");
            channelCounts.computeIfAbsent(code, k -> new long[2])[1]++;
            String c = category.get(i.getId());
            if (c == null || !ItemAnalysisCategories.isSupported(c)) {
                unclassified++;
                continue;
            }
            byTopic.computeIfAbsent(c, k -> new ArrayList<>()).add(i);
        }
        List<InquiryTopicRow> topics = byTopic.entrySet().stream()
                .map(e -> new InquiryTopicRow(e.getKey(), e.getValue().size(), e.getValue().stream()
                        .map(i -> {
                            String safe = preview(inquiryText(i));
                            return safe == null ? null : new InquiryRef(i.getId(), safe,
                                    codes.getOrDefault(i.getChannelId(), "OTHER"),
                                    i.getReceivedAt() == null ? null : i.getReceivedAt().atZone(KST).toLocalDate());
                        })
                        .filter(java.util.Objects::nonNull)
                        .limit(QUOTES_PER_SIDE)
                        .toList()))
                .sorted(Comparator.comparingLong(InquiryTopicRow::inquiries).reversed())
                .toList();

        // Channel rows: the WHOLE period's counts (a grouped count, not the read sample) beside what was read,
        // so a channel the capped read happened to miss is 「이번 읽기에서 빠짐」, never 「자료 없음」.
        Map<String, long[]> full = new LinkedHashMap<>();
        Map<UUID, String> allCodes = allChannelCodes();
        addCounts(full, allCodes, productId == null
                ? reviews.countByChannelInPeriod(orgId, period.fromAt(), period.toAt())
                : reviews.countByChannelInPeriodForProduct(orgId, productId, period.fromAt(), period.toAt()), 0);
        addCounts(full, allCodes, productId == null
                ? inquiries.countByChannelInPeriod(orgId, period.fromAt(), period.toAt())
                : inquiries.countByChannelInPeriodForProduct(orgId, productId, period.fromAt(), period.toAt()), 1);
        Set<String> channelKeys = new LinkedHashSet<>(full.keySet());
        channelKeys.addAll(channelCounts.keySet());
        List<ChannelCount> channelRows = channelKeys.stream()
                .map(code -> {
                    long[] f = full.getOrDefault(code, new long[2]);
                    long[] r = channelCounts.getOrDefault(code, new long[2]);
                    return new ChannelCount(code, Math.max(f[0], r[0]), Math.max(f[1], r[1]), r[0], r[1]);
                })
                .toList();
        long reviewsRead = read.size();
        long inquiriesRead = asked.size();
        boolean reviewsCapped = reviewTotal > reviewsRead;
        boolean inquiriesCapped = inquiryTotal > inquiriesRead;
        boolean enough = reviewsRead >= MIN_REVIEWS;
        List<ChannelGap> gaps = RANGE_STORE.equals(range) ? gaps(orgId, full) : List.of();
        Scope scope = new Scope(range, period.from(), period.to(), period.days(), cap,
                reviewTotal, reviewsRead, reviewsCapped, inquiryTotal, inquiriesRead, inquiriesCapped, unclassified,
                unlinkedReviews, unlinkedInquiries, unspecified[0], unspecified[1], unlinkedIncluded, channelRows, gaps,
                unattributedPraise,
                MIN_REVIEWS, enough);
        List<Long> starList = List.of(stars[0], stars[1], stars[2], stars[3], stars[4]);
        return new ProductAnalysisView(productId, name, scope, method(),
                new RatingSpread(rated, starList), aspects, topics,
                swot(aspects, topics, new Sample(reviewsRead, reviewsCapped), new Sample(inquiriesRead, inquiriesCapped),
                        enough));
    }

    /** The store's products on the same axes, period and denominator. */
    @Transactional(readOnly = true)
    public Comparison compare(UUID orgId, int days, LocalDate referenceDate) {
        Period period = Period.of(days, referenceDate);
        LocalDate from = period.from();
        LocalDate to = period.to();
        Instant fromAt = period.fromAt();
        Instant toAt = period.toAt();
        long linked = reviews.countByOrgIdAndProductIdIsNotNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                orgId, fromAt, toAt);
        List<Review> read = reviews
                .findByOrgIdAndProductIdIsNotNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                        orgId, fromAt, toAt, PageRequest.of(0, STORE_READ_CAP));
        Map<UUID, long[]> tally = new LinkedHashMap<>();
        for (Review r : read) {
            long[] t = tally.computeIfAbsent(r.getProductId(), k -> new long[3]);
            t[0]++;
            boolean praise = false;
            boolean complaint = false;
            for (ReviewOpinionReader.Opinion o : reader.read(r.getBody())) {
                if (o.praise() && o.aspect() != null) {
                    praise = true;
                } else if (!o.praise()) {
                    complaint = true;
                }
            }
            if (praise) {
                t[1]++;
            }
            if (complaint) {
                t[2]++;
            }
        }
        Map<UUID, Long> askedBy = new HashMap<>();
        for (Object[] row : inquiries.countByProductInPeriod(orgId, fromAt, toAt)) {
            askedBy.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Set<UUID> ids = new LinkedHashSet<>(tally.keySet());
        ids.addAll(askedBy.keySet());
        // The (미지정 상품) bucket is not a product: never plotted or listed, its rows reported on their own line.
        long bucketReviews = 0;
        long bucketInquiries = 0;
        for (UUID bucket : bucketIds(orgId)) {
            if (ids.remove(bucket)) {
                bucketReviews += tally.getOrDefault(bucket, new long[3])[0];
                bucketInquiries += askedBy.getOrDefault(bucket, 0L);
            }
        }
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (com.sellerops.product.ProductIdNameRow p : products.findIdNamesByOrgIdAndIdIn(orgId, ids)) {
                names.put(p.id(), p.name());
            }
        }
        List<ProductPoint> points = ids.stream()
                .filter(names::containsKey)
                .map(id -> {
                    long[] t = tally.getOrDefault(id, new long[3]);
                    return new ProductPoint(id, names.get(id), t[0], t[1], t[2], askedBy.getOrDefault(id, 0L),
                            t[0] >= MIN_REVIEWS);
                })
                .sorted(Comparator.comparingLong(ProductPoint::reviews).reversed()
                        .thenComparing(ProductPoint::name))
                .toList();
        return new Comparison(from, to, days, read.size(), linked > read.size(),
                reviews.countByOrgIdAndProductIdIsNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(orgId, fromAt, toAt),
                inquiries.countByOrgIdAndProductIdIsNullAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(orgId, fromAt, toAt),
                bucketReviews, bucketInquiries, MIN_REVIEWS, method(), points);
    }

    // ── SWOT

    static Swot swot(List<AspectRow> aspects, List<InquiryTopicRow> topics, Sample rv, Sample iq, boolean enough) {
        long reviews = rv.n();
        long inquiryCount = iq.n();
        List<Cell> s = new ArrayList<>();
        List<Cell> w = new ArrayList<>();
        List<Cell> o = new ArrayList<>();
        List<Cell> t = new ArrayList<>();
        if (enough) {
            for (AspectRow a : aspects) {
                if (a.praiseReviews() >= STRENGTH_MIN && a.praiseReviews() >= 2 * a.complaintReviews()) {
                    String key = "S:" + a.aspect();
                    s.add(new Cell(key, "OBSERVED", "REVIEW", a.aspect() + " 칭찬",
                            rv.label("리뷰") + " 중 " + a.praiseReviews() + "건이 " + obj(a.aspect()) + " 좋게 말했어요"
                                    + (a.complaintReviews() > 0 ? " (불만 " + a.complaintReviews() + "건)" : ""),
                            a.praiseReviews(), reviews, List.of(), null));
                    o.add(new Cell("O:" + a.aspect(), "HYPOTHESIS", "REVIEW",
                            "상세페이지에서 " + a.aspect() + " 강점을 고객 표현으로 앞세우기",
                            "반복된 칭찬 문장을 상세페이지·광고 문구에 쓰면 구매 결정에 도움이 될 수 있어요",
                            a.praiseReviews(), reviews, List.of(key),
                            "문구를 바꾼 뒤 같은 기간 길이로 리뷰·문의를 다시 비교해 보세요"));
                }
                if (a.complaintReviews() >= WEAKNESS_MIN) {
                    String key = "W:" + a.aspect();
                    w.add(new Cell(key, "OBSERVED", "REVIEW", a.aspect() + " 불만 (" + String.join("·", a.problems()) + ")",
                            rv.label("리뷰") + " 중 " + a.complaintReviews() + "건이 " + a.aspect() + " 문제를 말했어요",
                            a.complaintReviews(), reviews, List.of(), null));
                    boolean severe = a.problems().stream()
                            .anyMatch(p -> IssueVocabulary.severityOf(p) == IssueSeverity.HIGH);
                    if (severe) {
                        t.add(new Cell("T:" + a.aspect() + ":severe", "HYPOTHESIS", "REVIEW",
                                a.aspect() + " 문제가 반품·낮은 별점으로 이어질 위험",
                                "파손·누락·불량은 고객이 상품을 제대로 쓰지 못한 경우라 교환 비용과 별점에 영향을 줄 수 있어요",
                                a.complaintReviews(), reviews, List.of(key),
                                "같은 기간 교환·반품 문의와 1~2점 리뷰가 함께 늘었는지 확인해 보세요"));
                    }
                    if (a.recentComplaints() > a.earlierComplaints() && a.recentComplaints() >= WEAKNESS_MIN) {
                        t.add(new Cell("T:" + a.aspect() + ":rising", "HYPOTHESIS", "REVIEW",
                                a.aspect() + " 불만이 기간 뒤쪽에 늘었어요",
                                "앞쪽 절반 " + a.earlierComplaints() + "건 → 뒤쪽 절반 " + a.recentComplaints()
                                        + "건. 최근에 바뀐 것(입고분·포장·안내 등)이 있는지 볼 만해요",
                                a.recentComplaints(), a.complaintReviews(), List.of(key),
                                "다음 기간에도 같은 추세인지 다시 보세요"));
                    }
                }
            }
        }
        // Inquiry topics carry their own denominator and are not gated on the review sample.
        for (InquiryTopicRow topic : topics) {
            if (topic.inquiries() < INQUIRY_REPEAT_MIN || ItemAnalysisCategories.FALLBACK.equals(topic.topic())) {
                continue;
            }
            String key = "W:inquiry:" + topic.topic();
            w.add(new Cell(key, "OBSERVED", "INQUIRY", topic.topic() + " 문의 반복",
                    iq.label("문의") + " 중 " + topic.inquiries() + "건이 " + obj(topic.topic()) + " 물었어요",
                    topic.inquiries(), inquiryCount, List.of(), null));
            String title = ItemAnalysisCategories.PRODUCT_INFO.equals(topic.topic())
                    ? "상세페이지의 제품 정보 보강"
                    : INFO_TOPICS.contains(topic.topic())
                    ? "상세페이지에 " + topic.topic() + " 정보 보강"
                    : topic.topic() + " 안내를 상세페이지·자동 안내에 미리 적기";
            o.add(new Cell("O:inquiry:" + topic.topic(), "HYPOTHESIS", "INQUIRY", title,
                    "같은 질문이 반복되면 구매 전에 답을 찾지 못한 것일 수 있어요",
                    topic.inquiries(), inquiryCount, List.of(key),
                    "보강한 뒤 같은 기간 길이로 이 주제 문의 수를 비교해 보세요"));
        }
        String insufficient = enough ? null
                : "이 기간 리뷰가 " + reviews + "건이라 강점·약점을 말하기엔 적어요 (" + MIN_REVIEWS + "건부터). 기간을 넓혀 보세요.";
        return new Swot(List.copyOf(s), List.copyOf(w), List.copyOf(o), List.copyOf(t), insufficient);
    }

    // ── helpers

    private Map<String, UUID> issueByAspect(UUID orgId, UUID productId, LocalDate at) {
        Map<String, UUID> out = new HashMap<>();
        Map<String, Long> best = new HashMap<>();
        for (ReviewIssueView v : signals.issuesFor(orgId, productId, at)) {
            if (v.aspect() == null) {
                continue;
            }
            if (v.evidenceCount() > best.getOrDefault(v.aspect(), -1L)) {
                best.put(v.aspect(), v.evidenceCount());
                out.put(v.aspect(), v.id());
            }
        }
        return out;
    }

    private Map<UUID, String> channelCodes(List<Review> rs, List<Inquiry> is) {
        Set<UUID> ids = new HashSet<>();
        rs.forEach(r -> {
            if (r.getChannelId() != null) {
                ids.add(r.getChannelId());
            }
        });
        is.forEach(i -> {
            if (i.getChannelId() != null) {
                ids.add(i.getChannelId());
            }
        });
        Map<UUID, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Channel c : channels.findAllById(ids)) {
                out.put(c.getId(), c.getCode());
            }
        }
        return out;
    }

    private static String inquiryText(Inquiry i) {
        String title = i.getTitle();
        return title != null && !title.isBlank() ? title : i.getBody();
    }

    /** 「포장을」 · 「배송을」 · 「가격을」 · 「사이즈를」 — the object particle by the last syllable's final consonant. */
    static String obj(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }
        char last = word.charAt(word.length() - 1);
        boolean batchim = last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
        return word + (batchim ? "을" : "를");
    }

    /**
     * The preview a screen may show: {@link com.sellerops.common.VocPreviewSanitizer} FIRST (phone, mail, order and
     * account numbers, addresses, URLs become fixed tokens; markup is stripped), THEN shortened. Null when the
     * sanitizer suppresses the text — the row still counts, its sentence is just not shown.
     */
    static String preview(String text) {
        String safe = com.sellerops.common.VocPreviewSanitizer.sanitize(text).text();
        return safe == null || safe.isBlank() ? null : clip(safe);
    }

    static String clip(String text) {
        if (text == null) {
            return "";
        }
        String s = text.strip().replaceAll("\\s+", " ");
        return s.length() <= QUOTE_CHARS ? s : s.substring(0, QUOTE_CHARS - 1) + "…";
    }

    static final String RANGE_PRODUCT = "PRODUCT";
    static final String RANGE_STORE = "STORE";

    /** How many rows a count was taken over, and whether the read cap bound — said in every basis sentence. */
    record Sample(long n, boolean capped) {
        String label(String noun) {
            return (capped ? "최근 읽은 " : "") + noun + " " + n + "건";
        }
    }

    /** One half-open KST period ending on {@code to}. */
    record Period(int days, LocalDate from, LocalDate to, Instant fromAt, Instant toAt) {
        static Period of(int days, LocalDate referenceDate) {
            LocalDate to = referenceDate == null ? LocalDate.now(KST) : referenceDate;
            LocalDate from = to.minusDays(days - 1L);
            return new Period(days, from, to, from.atStartOfDay(KST).toInstant(),
                    to.plusDays(1).atStartOfDay(KST).toInstant());
        }
    }

    /**
     * Seller-visible channels with NOTHING in the whole period (grouped counts, not the read sample) — 수집 누락
     * candidates. {@code everHadRows} tells
     * 「이 기간만 비었다」 from 「한 번도 들어온 적 없다 (연결 전이거나 수집되지 않음)」.
     */
    private List<ChannelGap> gaps(UUID orgId, Map<String, long[]> counted) {
        Set<UUID> ever = new HashSet<>(reviews.findDistinctChannelIdsByOrgId(orgId));
        ever.addAll(inquiries.findDistinctChannelIdsByOrgId(orgId));
        List<ChannelGap> out = new ArrayList<>();
        for (Channel c : channels.findAllByOrderBySortOrderAsc()) {
            if (!com.sellerops.channel.ProductChannels.isVisible(c.getCode())) {
                continue;
            }
            long[] n = counted.get(c.getCode());
            if (n == null || n[0] + n[1] == 0) {
                out.add(new ChannelGap(c.getCode(), ever.contains(c.getId())));
            }
        }
        return List.copyOf(out);
    }

    /** {@code [reviews, inquiries]} in the period filed under the (미지정 상품) bucket, unless that bucket is the subject. */
    private long[] unspecified(UUID orgId, UUID subject, Period period) {
        long[] n = new long[2];
        for (UUID bucket : bucketIds(orgId)) {
            if (bucket.equals(subject)) {
                continue;
            }
            n[0] += reviews.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                    orgId, bucket, period.fromAt(), period.toAt());
            n[1] += inquiries.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                    orgId, bucket, period.fromAt(), period.toAt());
        }
        return n;
    }

    private List<UUID> bucketIds(UUID orgId) {
        List<UUID> ids = products.findIdsByOrgIdAndName(orgId, com.sellerops.product.OperatorProductName.UNSPECIFIED_PRODUCT_NAME);
        return ids == null ? List.of() : ids;
    }

    private Map<UUID, String> allChannelCodes() {
        Map<UUID, String> out = new HashMap<>();
        for (Channel c : channels.findAllByOrderBySortOrderAsc()) {
            out.put(c.getId(), c.getCode());
        }
        return out;
    }

    private static void addCounts(Map<String, long[]> into, Map<UUID, String> codes, List<Object[]> rows, int slot) {
        for (Object[] row : rows) {
            String code = row[0] == null ? "OTHER" : codes.getOrDefault((UUID) row[0], "OTHER");
            into.computeIfAbsent(code, k -> new long[2])[slot] += ((Number) row[1]).longValue();
        }
    }

    static final class AspectTally {
        long praise;
        long complaint;
        long recent;
        long earlier;
        final Set<String> problems = new LinkedHashSet<>();
        final List<Quote> praiseQuotes = new ArrayList<>();
        final List<Quote> complaintQuotes = new ArrayList<>();
    }
}
