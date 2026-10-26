package com.sellerops.review.triage;

import java.util.List;

/**
 * The whole of the tier decision, in one pure function of two inputs.
 *
 * <p><b>The two inputs are the rating and the body.</b> For 1–2★, 4–5★ and an unrated review the body is read for
 * blankness only. <b>For 3★ alone it is also read for a reservation</b> (product-owner decision 2026-10-27,
 * RUBRIC v2 §2.4): a 3★ whose text carries none of {@link #RESERVATION_MARKERS} — 「만족합니다」, 「디자인이 이뻐요」, a
 * product name — is {@link ReviewTriageTier#FYI}; one that carries any of them stays {@link ReviewTriageTier#WATCH}.
 * This only LOWERS a tier, and only out of 지켜보기: nothing here can move a review into or out of 확인 필요.
 *
 * <p><b>Why that boundary exists.</b> {@code contracts/review-eval/naver/v1/RUBRIC.md} §5 is a
 * pre-committed go/no-go: a text-derived detector may be built, but may not be surfaced to an
 * operator until it clears precision ≥ 0.80 (Wilson lower bound), recall ≥ 0.30, ≤ 0.05 false
 * positives on 4–5★ rows, and leaves {@code LOW_RATING_REVIEW} counts unchanged — "a detector may
 * only ADD". {@code labels.json} carries zero labels against an adequacy floor of 200, so nothing
 * can clear it today. Body-derived material still reaches the operator on this surface, but only as
 * a CITATION beside the row ({@link ReviewTriageNote}), never as the reason it ranked where it did.
 *
 * <p><b>One text signal stands beside the tiers, not inside them</b> (product-owner exception, 2026-10-22,
 * RUBRIC §7): {@link ReviewSafetySignal} adds a review that reports a safety or legal problem to the seller's
 * undecided list whatever its rating. It never changes what this class returns.
 *
 * <p>Three cases are worth stating rather than inferring:
 *
 * <ul>
 *   <li><b>1–2★ with no text is {@link ReviewTriageTier#WATCH}, not 확인 필요.</b> RUBRIC §2's
 *       tie-breakers put this case at {@code NO_ACTION}: "There is nothing to detect. Rating
 *       already handles it." It is not demoted to 참고 either — the rating is real and still counts.
 *       A strict SUBSET of RUBRIC's row, which reads "an empty <i>or emoji-only</i> body":
 *       {@link #isTextless} tests blankness only, so a 1★ of "😡😡" is 확인 필요 here. Recognising
 *       emoji-only content means reading the body's characters — the input this rule deliberately
 *       does not have — so the gap over-surfaces rather than hides, and is stated rather than closed.
 *   <li><b>A null rating is {@code WATCH}.</b> Unknown is not good news; sorting it into 참고 would
 *       hide a review nobody has judged.
 *   <li><b>3★ is {@code WATCH}.</b> The attention queue's band is 1–3★
 *       ({@code IngestedReviewVocItemSource}); this surface splits it because 확인 필요 is a stronger
 *       claim than "inside the attention window", and 3★ is where the two honestly differ.
 * </ul>
 *
 * <p><b>This class is one of two representations of the same rule.</b> The other is
 * {@code ReviewRepository.TRIAGE_TIER_RANK}, which sorts and counts pages the service never fully
 * loads. They are pinned equal, exhaustively, by {@code ChannelReviewTriageIT} — see
 * {@code docs/slices/review-triage-v1.md} §3.1. Change one and you must change the other.
 */
public final class ReviewTriageRules {

    /**
     * The words of {@link #RESERVATION_MARKERS}; that list adds {@link ReviewSafetySignal#stems()} — a 3★ that so much
     * as names a harm is not 참고.
     */
    private static final List<String> WORDS = List.of(
            "다만", "하지만", "지만", "는데", "은데", "근데", "그런데", "그러나", "아쉽", "아쉬", "별로", "그닥", "그다지", "글쎄", "실망", "불만",
            "불편", "단점", "보완", "개선", "문제", "불량", "하자", "파손", "고장", "찢", "찌그러", "깨져", "깨짐", "흠집", "기스", "얼룩", "오염",
            "냄새", "늦", "느려", "지연", "누락", "오배송", "빠져", "부족", "다르", "달라", "설명과", "사진과", "안 되", "안되", "안 돼", "안돼", "않",
            "못", "없어", "없네", "없음", "떨어", "약해", "약하", "약간", "조금", "좀", "얇", "비싸", "작아", "작네", "커서", "헐거", "뻑뻑", "빡빡",
            "들뜨", "뜨고", "뜨네", "휘어", "교환", "환불", "반품", "재발송", "ㅠ", "ㅜ", "흠", "ㅡㅡ", "-_-", "씹", "걸리", "빼고", "안좋",
            "안 좋", "비추", "으나", "하급", "최악", "...", "깨졌", "깨진", "깨지", "데", "던지", "던져", "그럭저럭", "보통", "그냥그", "그냥 그");

    /**
     * Words that mark a reservation in a review — any one keeps a 3★ in 지켜보기 (RUBRIC v2 §2.4).
     *
     * <p>Traced, as RUBRIC §6.3 requires, to the committed reason vocabulary (하자·파손·고장 · 배송 지연 · 포장 ·
     * 설명·사진과 다름 · 설치·사용 안 됨 · 교환·환불 요구 · 만족하지만 짚음 · 아쉬움) and Korean's concession endings, and
     * then to the six {@code DEV} complaints the first list missed (씹 · 걸리 · 빼고 · 안좋 · 비추 · 으나 · 하급 · …) and to
     * the complaints this repository's own tests already name (깨졌 · 던지 · 「예쁜데」's 데 · 그럭저럭).
     * Over-marking errs the safe way: a word that also appears in praise only keeps that praise in 지켜보기.
     *
     * <p>{@code ReviewRepository.TRIAGE_TIER_RANK} spells the same list out in SQL; {@code ReviewTriageRulesTest}
     * pins the two equal, term for term.
     */
    public static final List<String> RESERVATION_MARKERS = markers();

    private static List<String> markers() {
        List<String> all = new java.util.ArrayList<>(WORDS);
        for (String stem : ReviewSafetySignal.stems()) {
            if (!all.contains(stem)) {
                all.add(stem);
            }
        }
        return List.copyOf(all);
    }

    /** The rating at or below which a review is a complaint rather than a middling report. */
    static final int LOW_RATING_MAX = 2;
    /** The rating at or above which a review carries no action. */
    static final int GOOD_RATING_MIN = 4;

    private ReviewTriageRules() {
    }

    /**
     * The tier for a rating and a body.
     *
     * <p>{@code body} is read for blankness ONLY. It is taken as a parameter rather than a boolean so
     * that callers cannot disagree about what "textless" means — the same
     * {@code null-or-blank} test the list, the detail and the JPQL rank all use.
     */
    public static ReviewTriageTier tier(Integer rating, String body) {
        if (rating == null) {
            return ReviewTriageTier.WATCH;
        }
        if (rating <= LOW_RATING_MAX) {
            return isTextless(body) ? ReviewTriageTier.WATCH : ReviewTriageTier.NEEDS_ATTENTION;
        }
        if (rating >= GOOD_RATING_MIN) {
            return ReviewTriageTier.FYI;
        }
        return reservationFree(body) ? ReviewTriageTier.FYI : ReviewTriageTier.WATCH;
    }

    /**
     * There is text and none of it marks a reservation. A blank body is not reservation-free: a 3★ with nothing
     * written stays 지켜보기, as a 1–2★ with nothing written does — the rating is real and still counts.
     */
    public static boolean reservationFree(String body) {
        if (isTextless(body)) {
            return false;
        }
        for (String marker : RESERVATION_MARKERS) {
            if (body.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The sort key: 0 is the top of the operator's list.
     *
     * <p>Stated explicitly rather than taken from {@link Enum#ordinal()}. An ordinal is a
     * declaration-order accident; reordering the enum for readability would silently rearrange a
     * worklist, and the failure would be invisible in review. It is also the value
     * {@code ReviewRepository.TRIAGE_TIER_RANK} must reproduce in SQL, so it needs to be a number
     * this code names on purpose.
     */
    public static int rank(ReviewTriageTier tier) {
        return switch (tier) {
            case NEEDS_ATTENTION -> 0;
            case WATCH -> 1;
            case FYI -> 2;
        };
    }

    /**
     * The buyer rated and wrote nothing.
     *
     * <p>The channel's placeholder for an empty review cell is never stored, so a blank body means
     * exactly this and nothing else — there is no case where a body went missing. Mirrors
     * {@code ChannelReviewService.isTextless}; both must keep agreeing with the JPQL rank's
     * {@code trim(r.body) = ''} test.
     */
    public static boolean isTextless(String body) {
        return body == null || body.isBlank();
    }
}
