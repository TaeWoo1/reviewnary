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
 * @param canSend        「답글 등록 요청」 may be pressed: the saved draft is ready and no task is past its
 *                       approval
 * @param canCancel      「요청 취소」 may be pressed: the seller requested it and it is not yet settled
 * @param note           why the task's current version was stopped — or kept — after what replies are written from
 *                       changed (owner decision 2026-10-23); null when there is nothing to say
 * @param since          when this PC last saw the task move (the request's time, for 「10:12 요청」); null without one
 * @param draftFromPhone the draft head is the phone's edit, taken in here (2026-10-25: one draft, either device)
 * @param setAside       this PC's version the phone's edit replaced while both were changing it, for the seller to
 *                       choose between; null when there is nothing to choose
 */
public record ReviewReplyDelegationView(String taskState, boolean editedOnMobile, boolean canSend, boolean canCancel,
                                        Note note, Instant since, boolean draftFromPhone, SetAside setAside) {

    public ReviewReplyDelegationView(String taskState, boolean editedOnMobile, boolean canSend, boolean canCancel) {
        this(taskState, editedOnMobile, canSend, canCancel, null, null, false, null);
    }

    public ReviewReplyDelegationView(String taskState, boolean editedOnMobile, boolean canSend, boolean canCancel,
                                     Note note) {
        this(taskState, editedOnMobile, canSend, canCancel, note, null, false, null);
    }

    /** The set-aside version: its number, its text, and when it was written here. */
    public record SetAside(int version, String body, Instant writtenAt) {}

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
