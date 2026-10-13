package com.sellerops.review.media;

import java.util.List;

/**
 * <b>The photos and videos a customer attached to one review, and whether Reviewnary looked at each.</b>
 *
 * <p>Same semantics as the case screen's {@code CaseDetailView.Media}: {@code depicts}, {@code problemVisible} and
 * {@code problemDescription} are present only when a vision model actually inspected the photo; {@code imagePath}
 * is the same-origin route that serves it to this seller, and null for a video. No source address, ever.
 *
 * @param items one per attachment, in the order the channel showed them; empty when there are none
 */
public record ReviewMediaView(List<Item> items) {

    /**
     * @param kind           {@code IMAGE} | {@code VIDEO} | {@code UNKNOWN}
     * @param problemVisible {@code YES} | {@code NO} | {@code UNCLEAR}, or null when not inspected
     */
    public record Item(int ordinal, String kind, boolean inspected, String statusKo, String depicts,
                       String problemVisible, String problemDescription, String imagePath) {
    }
}
