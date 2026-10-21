package com.sellerops.attention.reply.dto;

/**
 * The vendor delegate lane as the review panel shows it ({@code docs/vendor_server_contract_v1.md} §4, owner
 * decisions D7 · D9 · D10). Null on the prep view when the review cannot use the lane.
 *
 * @param taskState      the latest task's server state ({@code AWAITING_APPROVAL} … {@code INVALIDATED}), or null
 *                       when no task was ever opened for the review
 * @param editedOnMobile the seller rewrote the text on the phone
 * @param canSend        「승인하고 담당자에게 맡기기」 and 「휴대폰으로 승인 요청」 may be pressed: the saved draft is
 *                       ready and no task is past its approval
 * @param canCancel      「요청 취소」 may be pressed: the task is not yet settled
 */
public record ReviewReplyDelegationView(String taskState, boolean editedOnMobile, boolean canSend, boolean canCancel) {
}
