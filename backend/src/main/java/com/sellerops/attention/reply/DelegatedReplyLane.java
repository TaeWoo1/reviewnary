package com.sellerops.attention.reply;

import com.sellerops.attention.reply.dto.ReviewReplyDelegationView;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Is a reply to this review already on its way through someone else's hands?</b> — the vendor's delegate lane
 * ({@code docs/vendor_server_contract_v1.md} §4): the seller approved here or on the phone, and a delegate posts the
 * approved text in Seller Center.
 *
 * <p>While the lane holds the review, this PC's own road to a public reply — approving here and the guided run — stays
 * shut, so one review never gets two replies from two lanes. Implemented by {@code vendorlink}; a context without it
 * has no such lane, and nothing is shut.
 */
public interface DelegatedReplyLane {

    /**
     * True while a task on the review is open, and after a delegate reported it posted: a reply that is out (as far
     * as anyone has said) must not be followed by a second one from this PC.
     */
    boolean holds(UUID orgId, UUID reviewId);

    /**
     * What the review panel shows of the lane, or null when this review cannot travel through it (not received from
     * the vendor server, or this PC is not linked). {@code draftReady} is the reply lane's own verdict that a saved,
     * unapproved draft may move forward — the lane adds only its own state to it.
     */
    ReviewReplyDelegationView describe(UUID orgId, UUID reviewId, boolean draftReady);

    /**
     * The state {@link #describe} would show, for many reviews in one read: the newest task's state per review that
     * has one, and nothing for the rest. Empty when this PC is not linked, by the same gate as {@link #describe} — a
     * list must not say what the review's own panel does not.
     */
    Map<UUID, String> statesOf(UUID orgId, Collection<UUID> reviewIds);
}
