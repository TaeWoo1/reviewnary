package com.sellerops.inquiry.reply;

import java.util.Optional;

/**
 * <b>How long an inquiry answer may be — kept apart from how long a draft may be</b> (2026-11-02: every inquiry draft was
 * saved under ESM's 1000-byte answer limit, so two real drafts that passed every check were lost at the save with
 * 「답변 내용이 너무 깁니다」 — on a channel whose limit is not that one, and with the seller's money spent).
 *
 * <ul>
 *   <li><b>Keeping a draft</b> is bounded only by {@link #STORAGE_MAX_BYTES}, a SellerOps internal cap. An answer too long
 *       for its channel is still kept, for the seller to shorten — never cut by us: the sentence cut may be the
 *       condition that makes it true.</li>
 *   <li><b>Sending</b> is bounded by the send limit of that channel's inquiry kind, where there is one
 *       ({@link #forInquiry}): the confirm refuses a longer answer and says how long it is. The adapters keep their own
 *       check as the last door.</li>
 *   <li><b>Writing</b>: the drafter is told the same limit ({@link #draftLine}).</li>
 * </ul>
 *
 * <p><b>A limit is a channel, an inquiry kind, a unit and a source — never one number for a channel</b> (2026-11-02,
 * external review): Coupang's 고객센터 문의답변 states 「최소 2 ~ 최대 1,000자」 in characters, while the 상품별 고객문의
 * answer this product sends ({@code POST .../onlineInquiries/{inquiryId}/replies}) states no length at all.
 *
 * <p>An inquiry kind whose answer limit no contract copy states has none here — 「공식 제한 미확인」, not a guessed
 * number and not SellerOps's own (2026-11-02, fourth review: the 1000 bytes once enforced on Coupang had no product
 * reason, and asked the seller to shorten what the platform may take). Coupang 상품별 고객문의; NAVER, whose answer
 * contracts say only that a length-policy breach is a 400; Cafe24, whose article contract bounds the title, not the
 * body. A rejection the platform makes is its answer: the draft is kept.
 */
public final class InquiryReplyLength {

    /** SellerOps internal cap on a kept draft's body, in UTF-8 bytes — not any channel's limit. */
    public static final int STORAGE_MAX_BYTES = 20_000;

    /** What a limit counts. Both are counted on the normalized body ({@link EsmAnswerValidation#normalize}). */
    public enum Unit {
        BYTES("바이트"), CHARS("자");

        private final String ko;

        Unit(String ko) {
            this.ko = ko;
        }

        public String ko() {
            return ko;
        }
    }

    /** Where the number comes from. Only the channel's own answer contract, today — no limit is SellerOps's. */
    public enum Source {
        /** The channel's own answer contract states it. */
        OFFICIAL
    }

    /**
     * A send limit.
     *
     * @param inquiryKind the channel resource it bounds, in words — a limit of one resource is not another's
     * @param basis       where the number is written, for whoever has to correct it
     */
    public record Limit(String channelCode, String inquiryKind, Unit unit, int max, Source source, String basis) {
    }

    private InquiryReplyLength() {
    }

    /**
     * The send limit for an answer to this channel's inquiry of this subtype, or empty when neither the channel states
     * one nor SellerOps sets one.
     */
    public static Optional<Limit> forInquiry(String channelCode, String sourceSubtype) {
        if (channelCode == null) {
            return Optional.empty();
        }
        return switch (channelCode) {
            case "GMARKET" -> Optional.of(new Limit(channelCode, "ESM 문의", Unit.BYTES,
                    EsmAnswerValidation.COMMENTS_MAX_BYTES, Source.OFFICIAL, "ESM 답변 계약 (EsmAnswerValidation)"));
            // COUPANG 상품별 고객문의: its answer contract states no length — 공식 제한 미확인. The 1,000자 of 고객센터
            // 문의답변 is another resource's and is not borrowed.
            default -> Optional.empty();
        };
    }

    /** The body's length in the limit's unit. */
    public static int length(Limit limit, String body) {
        if (body == null) {
            return 0;
        }
        String normalized = EsmAnswerValidation.normalize(body);
        return limit.unit() == Unit.CHARS
                ? normalized.codePointCount(0, normalized.length())
                : EsmAnswerValidation.utf8Bytes(normalized);
    }

    /** Whether this answer is longer than that inquiry's send limit. False when there is none. */
    public static boolean overLimit(String channelCode, String sourceSubtype, String body) {
        return forInquiry(channelCode, sourceSubtype).map(l -> length(l, body) > l.max()).orElse(false);
    }

    /** What the seller is told when the confirm refuses an answer for its length: how long, how long allowed, what to do. */
    public static String overLimitKo(Limit limit, int length) {
        String max = String.format("%,d", limit.max()) + limit.unit().ko();
        String now = String.format("%,d", length) + limit.unit().ko();
        return "답변이 이 채널의 길이 한도(" + max + ")를 넘어요. 지금 " + now + "예요. 줄여서 저장한 뒤 다시 보내 주세요. "
                + "자동으로 잘라 보내지 않아요.";
    }

    /** The drafter's line: the send limit, or nothing. It names no source — the drafter only needs the bound. */
    public static String draftLine(String channelCode, String sourceSubtype) {
        return forInquiry(channelCode, sourceSubtype).map(l -> "- 답변 길이: 답변 본문은 " + l.max() + l.unit().ko()
                + (l.unit() == Unit.BYTES ? "(한글 약 " + l.max() / 3 + "자, " : "(")
                + "줄바꿈 · 띄어쓰기 포함)까지 보낼 수 있습니다. 그 안에 씁니다").orElse("");
    }
}
