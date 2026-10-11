package com.sellerops.review.publish;

import java.util.UUID;

/**
 * <b>The reply record for one review moved.</b> Published after an execution row or an operator-reported outcome
 * has been written, so a surface that DERIVES from that record can re-derive now instead of at its next scheduled
 * pass. The inquiry lane's {@code InquiryAnswerSettledEvent}, for the lane that owns the other half of the same
 * promise.
 *
 * <p>It carries two identifiers and nothing else — no lane, no status, no verification, no draft text. A listener
 * that needs to know what happened reads it from the rows, through {@link ReviewDeliveryTruthReader}; an event
 * that carried the conclusion would be a second copy of it, free to disagree with the two append-only tables the
 * moment anything else wrote there. In particular it does not say whether the seller «acted»: that word belongs to
 * {@link ReviewDeliveryTruthReader.ReviewDeliveryTruth#sellerActed()} and is decided from the rows, so a
 * {@code REFUSED} execution and a {@code COMPOSER_FILLED} one raise this too and change nothing downstream.
 *
 * <p><b>It is a notification, not a command.</b> Nothing a listener does may change what this lane decided, and no
 * listener's failure may change it either — the row is written, and for the API lane a public reply has already
 * left for the channel, before this is raised.
 *
 * @param orgId    the organisation the review belongs to; every listener stays org-scoped
 * @param reviewId the subject a case is keyed by
 */
public record ReviewReplySettledEvent(UUID orgId, UUID reviewId) {
}
