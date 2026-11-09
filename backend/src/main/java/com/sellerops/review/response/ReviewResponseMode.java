package com.sellerops.review.response;

/** When a review's reply draft is written: ahead of the seller, by the automatic check, or when they ask. */
public enum ReviewResponseMode {
    /** Every review in the band is drafted ahead. */
    AHEAD,
    /**
     * Only a review in the band the AI judge says needs a response is drafted ahead
     * ({@code AiTriagePilotService#needsResponse}); one it says needs none, or could not judge, waits for the seller
     * (owner, 2026-10-28 — the ★4–5 default).
     */
    ISSUES_ONLY,
    /** Nothing in the band is drafted ahead. */
    ON_REQUEST
}
