package com.sellerops.review.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * <b>What an unattended NAVER review read may hand back: the rows it read, and nothing about who wrote them.</b>
 *
 * <p>The Seller Center row model carries the buyer's masked id, member number and order number beside every
 * review. None of them has a field here, and unknown properties are refused rather than ignored, so a helper that
 * sent one would be told so instead of quietly succeeding — the same posture as the Coupang handoff.
 *
 * <p>{@code windowDays} is the screen's own period as the helper measured it. It is recorded as the bound this
 * read covered, and it is why a run of this recipe is {@code BOUNDED}: a read of a period is never a read of a
 * store's history.
 *
 * <p><b>The period, and the ceiling.</b> {@code windowStart} / {@code windowEnd} are that same period as two KST
 * dates, and {@code rowCapacity} is the largest reading this one read could have carried. Together they are what
 * lets a boundary move: 45 rows under a ceiling of 500 for 10-02 … 10-08 proves those seven days; the same 45 rows
 * with no dates and no ceiling prove only that a read happened. All three are optional, because a helper built
 * before them says nothing rather than something false — such a read stores its rows and moves no boundary.
 *
 * @param windowStart first day of the period the screen showed, {@code YYYY-MM-DD} in KST
 * @param windowEnd   last day of it
 * @param rowCapacity the ceiling that reading was under — the backend compares the row count against it rather
 *                    than trusting a «complete» flag, so saturation is judged where the comparison can be checked
 *
 * <p><b>And what the screen itself said.</b> {@code rowCapacity} was carrying two facts at once: our ceiling,
 * and an assumption that the list was showing 500 rows per page. {@code rows < capacity} was then read as «the
 * whole period was seen», which it never proved — a list set to 50 per page and holding 300 reviews satisfies
 * it and has shown the reader a sixth of the period. So the list's printed total ({@code labelledTotal}) and
 * the page size it was set to ({@code selectedPageSize}) arrive as numbers, {@code gridReadMode} says how the
 * rows were obtained, and {@code monthMoves} records how many calendar steps reaching the period took. All
 * four are optional and all four are evidence: the verdict is taken here, where it can be re-checked.
 *
 * @param labelledTotal    the total the list printed for the period, or null when it did not state one
 * @param selectedPageSize the page size the list was set to, read and never changed
 * @param gridReadMode     {@code MODEL} when the rows came from the grid's own row model
 * @param monthMoves       single-month calendar steps taken to reach the period
 *
 * <p><b>And when the period held nothing.</b> {@code emptyPeriod} is the grid saying so in its own words — its
 * no-rows overlay on a drawn grid — which is the only thing that tells «this period is empty» apart from «this
 * screen could not be read»; the row count alone cannot. {@code screenStoreDigest} is which store that screen
 * is, as a digest of the seller-centre chrome that is there whether or not any row is: 64 lower-case hex, never
 * the values themselves. Both optional, and a helper built before them says nothing rather than something false.
 *
 * @param emptyPeriod        true when the grid itself stated the period holds no reviews
 * @param screenStoreDigest  domain-separated digest of the screen's own store identity, or null when unread
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record NaverReviewObservationRequest(List<Review> reviews, Integer windowDays, String windowStart,
                                            String windowEnd, Integer rowCapacity, Integer labelledTotal,
                                            Integer selectedPageSize, String gridReadMode, Integer monthMoves,
                                            Boolean emptyPeriod, String screenStoreDigest) {

    /** The shape before a read could say «this period is empty» and «this screen is that store». */
    public NaverReviewObservationRequest(List<Review> reviews, Integer windowDays, String windowStart,
                                         String windowEnd, Integer rowCapacity, Integer labelledTotal,
                                         Integer selectedPageSize, String gridReadMode, Integer monthMoves) {
        this(reviews, windowDays, windowStart, windowEnd, rowCapacity, labelledTotal, selectedPageSize,
                gridReadMode, monthMoves, null, null);
    }

    /** The shape before the period and the ceiling existed. */
    public NaverReviewObservationRequest(List<Review> reviews, Integer windowDays) {
        this(reviews, windowDays, null, null, null);
    }

    /** The shape before the screen's own total and page size were read. */
    public NaverReviewObservationRequest(List<Review> reviews, Integer windowDays, String windowStart,
                                         String windowEnd, Integer rowCapacity) {
        this(reviews, windowDays, windowStart, windowEnd, rowCapacity, null, null, null, null);
    }

    /**
     * One review as the list's row model held it.
     *
     * @param reviewId    the review's own id — the value its detail link opens and the export's 리뷰글번호
     * @param createdAt   리뷰등록일 as the page carries it (ISO-8601 with offset)
     * @param answered    the page's own «has a seller comment» flag; never inferred
     * @param attachCount how many photos/videos the row model listed — a reading, so 0 means «none», not «unknown»
     * @param attachments the attachments' own addresses, when the reader projects them; null means «not read», which
     *                    is a different statement from an empty list
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Review(String reviewId, String createdAt, Integer rating, String body, String productNo,
                         String productName, Boolean answered, Integer attachCount, List<Attachment> attachments) {

        /** The eight-field shape every helper before attachments sends. */
        public Review(String reviewId, String createdAt, Integer rating, String body, String productNo,
                      String productName, Boolean answered, Integer attachCount) {
            this(reviewId, createdAt, rating, body, productNo, productName, answered, attachCount, null);
        }
    }

    /**
     * One attachment's address as the row model held it.
     *
     * @param kind {@code IMAGE}, {@code VIDEO} or {@code UNKNOWN}; the reader never guesses past what the page says
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Attachment(String url, String kind) {
    }
}
