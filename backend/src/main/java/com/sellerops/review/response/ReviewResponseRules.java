package com.sellerops.review.response;

/**
 * <b>Which band a rating falls in, and what the seller's rule says for it</b> — pure, so the rule is testable without a
 * database.
 *
 * <p>A review with no rating sits in the middle band: it is neither a complaint the number proves nor praise it proves,
 * and the middle band is the one a seller who wants drafts for 「애매한 것」 already set.
 */
public final class ReviewResponseRules {

    /**
     * The defaults a seller who never opened the setting gets (owner, 2026-10-28): ★1–2 and ★3 drafted ahead, and a ★4–5
     * only when the AI judge says it needs a response — 「별점 4~5점 칭찬 리뷰는 초안 잡지 말자」.
     */
    public static final Modes DEFAULT = new Modes(ReviewResponseMode.AHEAD, ReviewResponseMode.AHEAD,
            ReviewResponseMode.ISSUES_ONLY);

    private ReviewResponseRules() {
    }

    public enum Band { LOW, MID, HIGH }

    /** The three modes, in band order. */
    public record Modes(ReviewResponseMode low, ReviewResponseMode mid, ReviewResponseMode high) {

        public ReviewResponseMode forRating(Integer rating) {
            return switch (band(rating)) {
                case LOW -> low;
                case MID -> mid;
                case HIGH -> high;
            };
        }

        static Modes of(ReviewResponseRule row) {
            return row == null ? DEFAULT : new Modes(row.getLowMode(), row.getMidMode(), row.getHighMode());
        }
    }

    public static Band band(Integer rating) {
        if (rating == null) {
            return Band.MID;
        }
        if (rating <= 2) {
            return Band.LOW;
        }
        return rating == 3 ? Band.MID : Band.HIGH;
    }
}
