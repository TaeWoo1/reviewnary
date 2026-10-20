package com.sellerops.attention.reply;

import java.util.UUID;

/**
 * <b>Is a reply to this review already on its way through someone else's hands?</b> — the vendor's delegate lane
 * ({@code docs/vendor_server_contract_v1.md} §4): the seller asked for approval on the phone, and a delegate posts the
 * approved text in Seller Center.
 *
 * <p>While that is open, this PC's own road to a public reply — approving here and the guided run — stays shut, so one
 * review never gets two replies from two lanes. Implemented by {@code vendorlink}; a context without it has no such
 * lane, and nothing is shut.
 */
public interface DelegatedReplyLane {

    boolean openOn(UUID orgId, UUID reviewId);
}
