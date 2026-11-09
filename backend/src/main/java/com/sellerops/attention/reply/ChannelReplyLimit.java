package com.sellerops.attention.reply;

import java.util.Optional;

/**
 * <b>The longest reply a channel takes — a constraint, not a style</b> (2026-11-01 seventh review: 「200자 제한은
 * {@code CHANNEL_LENGTH_EXCEEDED}처럼 별도 코드로 분리하고, 판매자가 수정한 문장까지 승인 · 등록 직전에 동일하게 검사」).
 *
 * <p>NAVER's seller reply is 5–200 characters (research 2026-10-28, {@code reply-references-platform-legal.md} R12).
 * Other channels publish no limit, and none is enforced for them here. A draft over the limit may be kept to fix; it is
 * never approved or registered, whoever wrote it.
 */
public final class ChannelReplyLimit {

    /** The finding's code, apart from the style rules (F1 · …) a seller may polish at leisure. */
    public static final String CHANNEL_LENGTH_EXCEEDED = "CHANNEL_LENGTH_EXCEEDED";

    static final int NAVER_MAX = 200;

    private ChannelReplyLimit() {
    }

    /** The channel's limit in characters, or empty when it states none. */
    public static Optional<Integer> maxFor(String channelCode) {
        return "NAVER".equals(channelCode) ? Optional.of(NAVER_MAX) : Optional.empty();
    }

    /** Characters as a reader counts them (code points), of the text as it would be sent. */
    public static int lengthOf(String body) {
        String text = ReviewReplyValidation.normalize(body);
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /** Why {@code body} cannot be sent to {@code channelCode}, or empty: the seller's sentence, with both numbers. */
    public static Optional<String> refusal(String channelCode, String body) {
        Optional<Integer> max = maxFor(channelCode);
        int length = lengthOf(body);
        if (max.isEmpty() || length <= max.get()) {
            return Optional.empty();
        }
        return Optional.of("네이버 답글은 " + max.get() + "자까지 등록할 수 있어요. 지금 " + length + "자예요. 줄인 뒤 다시 해 주세요.");
    }
}
