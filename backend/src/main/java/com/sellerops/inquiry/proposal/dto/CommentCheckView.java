package com.sellerops.inquiry.proposal.dto;

import com.sellerops.ingest.canonical.CommentAnswerCheck;
import com.sellerops.ingest.canonical.SourceThreadRole;
import com.sellerops.inquiry.Inquiry;
import java.time.Instant;

/**
 * <b>Whether SellerOps looked for an answer the shop left as a Cafe24 board comment, and what it found</b> (V184 columns,
 * read on the detail so the seller is told before approving or registering, 2026-11-08).
 *
 * <p>{@code state} is one of four words, never more:
 * <ul>
 *   <li>{@code SHOP_ANSWERED} — a shop comment was proven;</li>
 *   <li>{@code NO_SHOP_COMMENT} — the look succeeded and found none;</li>
 *   <li>{@code NOT_CHECKED} — the last look failed or could not cover this article. <b>Not</b> evidence that the shop has
 *       not answered;</li>
 *   <li>{@code NO_RECORD} — nothing was ever recorded: every inquiry stored before V184, and every one no collection has
 *       re-read since.</li>
 * </ul>
 * {@code attemptedAt} is when the last result was produced, whatever it was; {@code lastCheckedAt} is the last look that
 * succeeded, kept through a later failure — so 「과거 성공 뒤 최근 실패」 reads as both times. Neither is filled from the other,
 * nor from {@code last_seen_at}.
 *
 * <p><b>Null where the question does not arise</b>: another channel, a thread reply, or a row no connector wrote (a file
 * import names no seller account and no collection ever looks at it). A row that does carry a record always shows it.
 *
 * <p>It is shown, never enforced: nothing about approval or registration reads it.
 */
public record CommentCheckView(String state, Instant attemptedAt, Instant lastCheckedAt) {

    public static final String NO_RECORD = "NO_RECORD";
    private static final String CAFE24 = "CAFE24";

    public static CommentCheckView of(Inquiry inquiry, String channelCode) {
        if (inquiry == null) {
            return null;
        }
        CommentAnswerCheck.Result result = inquiry.getCommentCheck();
        if (result != null) {
            return new CommentCheckView(result.name(), inquiry.getCommentCheckAttemptedAt(), inquiry.getCommentCheckedAt());
        }
        boolean lookedAtByTheLane = CAFE24.equals(channelCode)
                && inquiry.getSellerAccountId() != null
                && inquiry.threadRole() != SourceThreadRole.REPLY;
        return lookedAtByTheLane ? new CommentCheckView(NO_RECORD, null, null) : null;
    }
}
