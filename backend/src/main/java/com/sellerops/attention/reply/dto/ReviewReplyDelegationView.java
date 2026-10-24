package com.sellerops.attention.reply.dto;

import java.time.Instant;
import java.util.List;

/**
 * The vendor delegate lane as the review panel shows it ({@code docs/vendor_server_contract_v1.md} §4, owner
 * decisions D7 · D9 · D10). Null on the prep view when the review cannot use the lane.
 *
 * @param taskState      the latest task's server state ({@code AWAITING_APPROVAL} … {@code INVALIDATED}), or null
 *                       when no task was ever opened for the review
 * @param editedOnMobile the seller rewrote the text on the phone
 * @param canSend        「답글 등록 요청」 and 「휴대폰으로 승인 요청」 may be pressed: the saved draft is
 *                       ready and no task is past its approval
 * @param canCancel      「요청 취소」 may be pressed: the task is not yet settled
 * @param note           why the task's current version was stopped — or kept — after what replies are written from
 *                       changed (owner decision 2026-10-23); null when there is nothing to say
 */
public record ReviewReplyDelegationView(String taskState, boolean editedOnMobile, boolean canSend, boolean canCancel,
                                        Note note) {

    public ReviewReplyDelegationView(String taskState, boolean editedOnMobile, boolean canSend, boolean canCancel) {
        this(taskState, editedOnMobile, canSend, canCancel, null);
    }

    /**
     * @param codes     {@code SOURCE_CHANGED} · {@code UNSUPPORTED_PROMISE}
     * @param sources   the cited documents that changed
     * @param promises  the commitment words that lost their ground
     * @param sentences the reply's own sentences that carry them
     * @param phrases   the company's forbidden phrases the reply carries (2026-10-23)
     */
    public record Note(List<String> codes, List<Source> sources, List<String> promises, List<String> sentences,
                       List<String> phrases) {}

    /** {@code kind}: {@code PRODUCT_KNOWLEDGE} · {@code ORG_POLICY}. */
    public record Source(String kind, String title, Instant changedAt, boolean removed) {}
}
