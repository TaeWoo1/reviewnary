package com.sellerops.review.triage;

/**
 * <b>왜 지금 이 리뷰가 앞에 있는가</b> — one factual sentence, for the review workspace and nowhere else.
 *
 * <p><b>Why this is not {@link ReviewTriageNote#recommendedAction()}.</b> That line is an instruction —
 * 「내용을 읽고 상품 상태를 확인해 보세요」 — and it is the LIST's, where a row has to tell a seller
 * scanning 4,599 of them what to do about this one. The detail screen has the review open; by the time
 * the seller reads this line they have already decided to look. What they need there is the fact that
 * put it in front of them, stated once, not a second imperative over the sentence they are reading.
 * The two surfaces therefore say different things, and {@code recommendedAction} is unchanged —
 * every row, queue and chat line that quotes it still reads exactly as it did.
 *
 * <p><b>The backend owns the sentence because the frontend must not assemble one.</b> A screen that
 * joined {@code reason} and {@code recommendedAction} with a connective would be composing a claim out
 * of two strings written for other places: 「2점」 is a citation and 「확인해 보세요」 is an instruction,
 * and the sentence that comes out of gluing them is neither one the rules made nor one anybody tested.
 * Here it is a fixed map over the tier and two facts — the same discipline as
 * {@link ReviewTriageNote#action} — so there is exactly one place the wording lives and one place a
 * test can sweep it.
 *
 * <p><b>It states; it does not instruct.</b> Every sentence here is of the form 「…이며, …있습니다」.
 * The same two prohibitions as the note apply and for the same reasons:
 *
 * <ul>
 *   <li><b>Nothing may imply replying.</b> Coupang gives sellers no way to answer a 상품평, so a line
 *       that says 답변/답글/회신 would promise a control the channel has not got.</li>
 *   <li><b>Nothing may say 반복.</b> That word is the issue memory's, and 「반복 신호」 is a section of
 *       its own a few centimetres below this line. What {@link ReviewTriageNote#REPEAT_MIN} counts is a
 *       different mechanism over a different input, so this names the bucket it actually counted.</li>
 * </ul>
 *
 * <p>{@code ReviewTriageWhyNowTest} sweeps the whole input space for both.
 */
public final class ReviewTriageWhyNow {

    private ReviewTriageWhyNow() {
    }

    /**
     * The sentence for one review, or {@code null} when there is genuinely nothing to say.
     *
     * <p>Takes the same four inputs as {@link ReviewTriageNote#of} and derives the tier through the same
     * {@link ReviewTriageRules}, so the two can never disagree about which review this is.
     *
     * @param category      the stored {@code item_analyses.category}, or {@code null}
     * @param categoryCount how many of this channel's reviews share that category, unwindowed
     */
    public static String of(Integer rating, String body, String category, long categoryCount) {
        ReviewTriageTier tier = ReviewTriageRules.tier(rating, body);
        boolean textless = ReviewTriageRules.isTextless(body);
        boolean grouped = ReviewTriageNote.tagOf(category) != null && categoryCount >= ReviewTriageNote.REPEAT_MIN;
        String subject = textless
                ? "별점만 남긴 " + rated(rating) + "이며"
                : rated(rating) + "이며";

        return switch (tier) {
            // 1–2점 with text. NEEDS_ATTENTION is unreachable for a textless review
            // ({@link ReviewTriageRules#tier}), so the textless wording below never applies to it.
            case NEEDS_ATTENTION -> grouped
                    ? subject + ", 같은 자동 분류의 상품평이 여러 건 있습니다."
                    : subject + ", 상품 상태를 확인할 내용이 있습니다.";
            case WATCH -> textless
                    ? subject + ", 읽을 본문이 없습니다."
                    : grouped
                            ? subject + ", 같은 자동 분류의 상품평이 여러 건 있습니다."
                            : subject + ", 같은 분류가 늘어나는지 볼 내용이 있습니다.";
            // 참고 gets nothing, exactly as {@code recommendedAction} does. A review with nothing to
            // look at should say nothing; a filler sentence would make every review look like work.
            case FYI -> null;
        };
    }

    /** 「2점 리뷰」, or 「평점 없는 리뷰」 when the channel gave us no rating. */
    private static String rated(Integer rating) {
        return rating == null ? "평점 없는 리뷰" : rating + "점 리뷰";
    }
}
