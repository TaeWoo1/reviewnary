package com.sellerops.review.triage.corpus;

import java.util.List;

/**
 * One member of a cut correction set, in the <b>only</b> shape it may leave this process in.
 *
 * <p><b>The field list is the privacy boundary, and it is copied from the gold set on purpose.</b>
 * {@code contracts/review-eval/naver/v2/labels.json} says it carries «ONLY the review-id fingerprint and the
 * operator's judgment — tier, reasonCode, tags … Never the body, the raw 리뷰글번호, the rating, the date, the
 * body length, the stratum, the product, or any seller identity. The harness re-derives rating, length and
 * stratum from the local database at evaluation time.» A correction corpus that carried a review body would be
 * a second, looser boundary for the same content, and the looser one is the one that leaks.
 *
 * <p>So there is no body, no rating, no date, no org, no product, no user and no internal id here. What remains
 * is a one-way digest of the channel-side review id ({@code contracts/review-id-fingerprint/v1}) and closed
 * vocabulary — enough for an offline harness to join against its own local database and measure a candidate,
 * and not enough to reconstruct a customer.
 *
 * @param reviewIdFingerprint  {@code ReviewIdFingerprint.of(reviews.external_id)}. Null when the review carries
 *                             no channel-side id or a malformed one — the row is then <b>withheld</b> rather
 *                             than exported, because a member an offline harness cannot join is a member that
 *                             silently shrinks the set it was counted in
 * @param shownTier            what the seller was looking at when they corrected it
 * @param shownSource          which mechanism put that tier there (RULE or AI)
 * @param correctedTier        what the seller said instead — the testimony this whole set is made of
 * @param predictedTier        the stored tier after RUBRIC v2 §8.9's additive guard, or null when no classifier
 *                             had seen the review
 * @param modelTier            what the model said BEFORE the guard, so «did the prompt fix it or is the guard
 *                             carrying it» stays answerable offline
 */
public record TriageCorpusRow(String reviewIdFingerprint, String shownTier, String shownSource,
                              String correctedTier, String correctedReasonCode, List<String> correctedTags,
                              String predictedTier, String modelTier, String predictionStatus,
                              String classifierVersion, String modelId, String promptHash) {
}
