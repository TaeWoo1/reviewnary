package com.sellerops.inquiry.proposal.dto;

import com.sellerops.common.MarkupText;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.reply.dto.ReplyDraftView;
import java.time.Instant;

/**
 * <b>The answer the channel itself shows</b> (2026-11-06 sixteenth review: 「우리 초안을 실제 등록 본문으로 바꾸어 표시하면 안
 * 됩니다」). Only ever what a collection read off the channel — {@code inquiries.answer_body} is written by ingest and by
 * nothing else, so a send SellerOps verified leaves it as it was ({@code InquiryPublishService.markInquiryAnswered}). When
 * the seller answered on the seller centre, this is the text the customer actually got; the draft beside it is ours.
 *
 * <p><b>Two check times, never one</b> (2026-11-06 seventeenth review). {@code bodyCheckedAt} is when a collection last
 * carried this text ({@code answer_body_seen_at}); null when it was stored before that was recorded, which the screen says
 * it does not know. {@code statusCheckedAt} is when a collection last showed the inquiry at all ({@code last_seen_at}). A
 * collection that brings only the answered status keeps the old text — so the two part, and {@code latest} is false: the
 * screen shows it as 「마지막으로 수집한 답변」, not as what the channel shows now. Neither time is ever filled from the
 * other, nor from the channel's own answer time.
 *
 * <p>{@code sameAsDraft} compares the plain text of the two with runs of whitespace folded, nothing more — a different
 * word is a different answer. Null with no draft to compare. A body that differs is shown, not corrected: nothing is
 * overwritten and nothing is sent again.
 *
 * <p>Null when the channel gave no answer body — which is not 「unanswered」: a source may say an answer exists without
 * carrying its text (Cafe24's board comment, {@code Cafe24HistoricalCommentReconciler}).
 */
public record ChannelAnswerView(String body, Instant answeredAt, Instant bodyCheckedAt, Instant statusCheckedAt,
                                boolean latest, Boolean sameAsDraft) {

    public static ChannelAnswerView of(Inquiry inquiry, ReplyDraftView draft) {
        String body = MarkupText.toPlainText(inquiry.getAnswerBody(), Integer.MAX_VALUE);
        if (body == null || body.isBlank()) {
            return null;
        }
        Instant bodySeen = inquiry.getAnswerBodySeenAt();
        Instant seen = inquiry.getLastSeenAt();
        // The last read carried the body: ingest stamps both from one instant, so this is an equality, not a tolerance.
        boolean latest = bodySeen != null && (seen == null || !bodySeen.isBefore(seen));
        Boolean same = draft == null || draft.comments() == null ? null
                : folded(body).equals(folded(MarkupText.toPlainText(draft.comments(), Integer.MAX_VALUE)));
        return new ChannelAnswerView(body.strip(), inquiry.getAnsweredAt(), bodySeen, seen, latest, same);
    }

    static String folded(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
