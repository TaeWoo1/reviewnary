package com.sellerops.product.analysis;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One product's strengths, weaknesses and repeated problems over one period — every number with the
 * denominator it was counted over, and every observation with the sentences behind it.
 *
 * <p><b>Two denominators, never mixed.</b> Review readings are counted in reviews of this product in the
 * period ({@link Scope#reviews()}); inquiry topics in inquiries of this product in the period
 * ({@link Scope#inquiries()}). No field divides one by the other and no ranking puts the two in one order.
 */
public record ProductAnalysisView(UUID productId, String productName, Scope scope, Method method,
                                  RatingSpread rating, List<AspectRow> aspects, List<InquiryTopicRow> inquiryTopics,
                                  Swot swot) {

    /**
     * What was read. {@code range} is PRODUCT (one product's linked rows) or STORE (the org's rows, linked and
     * unlinked — {@code unlinkedIncluded}). {@code *Read} &lt; the total when {@code readCap} bound, newest first;
     * every count below is over the rows read. {@code unlinked*} are the org's rows in the period with no product id;
     * {@code unspecified*} are rows ingest filed under the (미지정 상품) bucket — a product id, but no product. Two
     * different gaps, never summed. Both are excluded from a PRODUCT reading and included in a STORE one. {@code gaps} (STORE only) are seller-visible
     * channels with nothing read in the period.
     */
    public record Scope(String range, LocalDate from, LocalDate to, int days, int readCap,
                        long reviews, long reviewsRead, boolean reviewsCapped,
                        long inquiries, long inquiriesRead, boolean inquiriesCapped, long inquiriesUnclassified,
                        long unlinkedReviews, long unlinkedInquiries, long unspecifiedReviews, long unspecifiedInquiries,
                        boolean unlinkedIncluded,
                        List<ChannelCount> channels, List<ChannelGap> gaps, long unattributedPraiseReviews,
                        int minReviews, boolean enoughReviews) {
    }

    public record ChannelGap(String channelCode, boolean everHadRows) {
    }

    /** The whole period's rows from this channel, and how many of them this reading read (they differ when capped). */
    public record ChannelCount(String channelCode, long reviews, long inquiries, long reviewsRead, long inquiriesRead) {
    }

    /** How the reading was made — rule-based, said in so many words. */
    /**
     * How the reviews were read. {@code kind}: RULE_BASED, AI_READ (every review read by the review-aspect model) or
     * MIXED ({@code aiReadReviews} of {@code reviewsRead} by the model, the rest by the word lists).
     */
    public record Method(String kind, String complaintRules, String praiseRules, String inquiryRules, String aiReader,
                         long aiReadReviews, long reviewsRead) {
    }

    /** 1★..5★ counts. Context only: no strength or weakness is derived from it. */
    public record RatingSpread(long rated, List<Long> counts) {
    }

    /**
     * One aspect: reviews praising it, reviews complaining about it (a review counts once per side), the
     * problems named, the matching issue in the issue memory when there is one, and a few sentences each way.
     */
    public record AspectRow(String aspect, long praiseReviews, long complaintReviews, List<String> problems,
                            UUID issueId, long recentComplaints, long earlierComplaints,
                            List<Quote> praiseQuotes, List<Quote> complaintQuotes) {
    }

    public record Quote(UUID reviewId, String text, Integer rating, String channelCode, LocalDate on, String problem) {
    }

    public record InquiryTopicRow(String topic, long inquiries, List<InquiryRef> examples) {
    }

    public record InquiryRef(UUID inquiryId, String text, String channelCode, LocalDate on) {
    }

    /**
     * Strengths and weaknesses are OBSERVED (counted); opportunities and threats are HYPOTHESIS (derived from
     * an observation named in {@link Cell#derivedFrom()}, with a way to check it). {@code insufficient} is the
     * sentence the screen shows instead of cells when the sample is too small.
     */
    public record Swot(List<Cell> strengths, List<Cell> weaknesses, List<Cell> opportunities, List<Cell> threats,
                       String insufficient) {
    }

    public record Cell(String key, String kind, String source, String title, String basis, long count,
                       long denominator, List<String> derivedFrom, String check) {
    }

    /** The store's products side by side on the same axes, the same period and the same denominator. */
    public record Comparison(LocalDate from, LocalDate to, int days, long reviewsRead, boolean reviewsCapped,
                             long unlinkedReviews, long unlinkedInquiries, long unspecifiedReviews,
                             long unspecifiedInquiries, int minReviews, Method method,
                             List<ProductPoint> products) {
    }

    /**
     * {@code praiseReviews} and {@code complaintReviews} are both out of {@code reviews}; {@code inquiries}
     * has its own denominator and is listed, never plotted against the review rates.
     */
    public record ProductPoint(UUID productId, String name, long reviews, long praiseReviews, long complaintReviews,
                               long inquiries, boolean enough) {
    }
}
