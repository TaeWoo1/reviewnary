package com.sellerops.review.triage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>A review that says someone was hurt, made ill, put in danger, or is taking it further</b> — the one text
 * signal that may move a review ahead of the inquiries (product-owner decisions, 2026-10-22: interview questions
 * 19–20's 「매우 심각한 리뷰는 문의 우선의 예외」, judged by safety and legal expressions, <b>regardless of the
 * rating</b>).
 *
 * <p><b>An owner-granted exception to a standing gate, and it says so.</b>
 * {@code contracts/review-eval/naver/v1/RUBRIC.md} §5 keeps every text-derived detector away from an operator until
 * it clears four measured bars, and nothing has been labelled to measure this one. The owner was shown that gate,
 * and the false positive it exists for — a happy customer's 「다칠 걱정 없어요」 — and chose to surface this signal
 * anyway (RUBRIC §7). It is narrow on purpose: it is not 「does this review need handling」, which stays the rating's
 * and {@link ReviewTriageRules}'s; it never changes a tier; and it only ADDS a review to the seller's list.
 *
 * <p><b>No model.</b> A fixed list of expressions, so why a review rose can always be said. The list is the one the
 * owner was shown, tightened where a word mostly means something else in a review: 상처 · 찢어 · 터졌 (a scratched
 * table, a torn bag, a burst box), 상했 (기분 상했어요), 고소 (고소한 맛), and 병원 · 폭발 · 신고 only in the forms that
 * report an event (병원에 갔 · 폭발했 · 신고했).
 *
 * <p><b>How a match is read.</b> Whitespace is removed first, because a customer's spacing is not a signal
 * (타는 냄새 · 타는냄새). Compounds that only look like a term are removed before that (화상회의). A match followed
 * within {@value #NEGATION_WINDOW} characters by a negation or a reassurance (없 · 않 · 방지 · 괜찮 …) does not
 * count — 「이물질 없이 깨끗해요」. The window is short so that a later, unrelated clause cannot cancel a real report
 * (「피가 났는데 연락이 없어요」).
 *
 * <p><b>A worry is not a reassurance</b> (product-owner decision, 2026-10-23). 「너무 뜨거워서 화상 입을까 걱정돼요」 is
 * a customer telling the seller the product may hurt someone, so 걱정 · 염려 no longer cancel a match on their own;
 * only a worry that is itself denied does (「화상 걱정 없어요」 · 「다칠 걱정은 전혀 없어요」). The signal is a
 * candidate for the seller to look at — the mark says 「안전 관련 확인」, not that harm happened — and an occurrence,
 * a worry and a background mention (「알레르기 있는 아이도 잘 써요」, which still rises) are not told apart here.
 */
public final class ReviewSafetySignal {

    /** What kind of thing the review reported — the reason a seller is told when they ask why it rose. */
    public enum Kind { INJURY, BODY_REACTION, PRODUCT_HAZARD, LEGAL }

    static final int NEGATION_WINDOW = 5;

    /** Every expression, whitespace-free, in the order it is tried. */
    static final Map<String, Kind> TERMS = terms();

    /** Words that contain a term and mean something else. Removed before matching. */
    static final List<String> NOT_SAFETY = List.of(
            "화상회의", "화상통화", "화상채팅", "화상카메라", "화상캠", "화상수업", "화상강의", "화상면접");

    /** What, right after a term, turns it into its absence or a reassurance. */
    static final List<String> NEGATIONS = List.of(
            "없", "않", "아니", "방지", "예방", "제거", "지워", "퇴치", "괜찮", "안심", "안생", "안들", "안나", "잘써");

    /** A worry about the term. It keeps the match standing unless a negation follows the worry itself. */
    static final List<String> WORRIES = List.of("걱정", "염려");

    private ReviewSafetySignal() {
    }

    private static Map<String, Kind> terms() {
        Map<String, Kind> t = new LinkedHashMap<>();
        for (String s : List.of("다쳤", "다쳐", "다치", "다칠", "부상", "화상", "데였", "데어서", "베였", "베여서", "피가났",
                "피가나", "멍이들", "멍들었")) {
            t.put(s, Kind.INJURY);
        }
        for (String s : List.of("알레르기", "알러지", "두드러기", "발진", "식중독", "배탈", "설사를", "설사가", "설사했",
                "설사해", "구토", "토했", "병원에갔", "병원갔", "병원다녀", "병원진료", "응급실")) {
            t.put(s, Kind.BODY_REACTION);
        }
        for (String s : List.of("이물질", "벌레가나", "벌레가있", "벌레가들어", "벌레나왔", "곰팡이", "변질", "감전", "누전",
                "불이났", "불이붙", "불꽃", "타는냄새", "연기가나", "연기가났", "폭발했", "폭발해")) {
            t.put(s, Kind.PRODUCT_HAZARD);
        }
        for (String s : List.of("신고했", "신고할", "신고하겠", "신고합니다", "고소하겠", "고소할", "고소장", "고발", "소송",
                "변호사", "소비자원", "식약처", "공정위")) {
            t.put(s, Kind.LEGAL);
        }
        return java.util.Collections.unmodifiableMap(t);
    }

    /** Whether the review carries the signal. */
    public static boolean in(String body) {
        return of(body).isPresent();
    }

    /** The first expression's kind that stands, or empty. */
    public static Optional<Kind> of(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        String text = body.replaceAll("\\s+", "");
        for (String compound : NOT_SAFETY) {
            text = text.replace(compound, "");
        }
        for (Map.Entry<String, Kind> term : TERMS.entrySet()) {
            int at = text.indexOf(term.getKey());
            while (at >= 0) {
                int end = at + term.getKey().length();
                if (!negatedAfter(text, end)) {
                    return Optional.of(term.getValue());
                }
                at = text.indexOf(term.getKey(), end);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether any expression occurs at all, negated or not — what the safety evaluation set
     * ({@code contracts/review-eval/safety/v1}) samples the reviews the negation filter cancelled from.
     */
    public static boolean mentions(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String text = body.replaceAll("\\s+", "");
        for (String compound : NOT_SAFETY) {
            text = text.replace(compound, "");
        }
        for (String term : TERMS.keySet()) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean negatedAfter(String text, int from) {
        String after = window(text, from);
        if (NEGATIONS.stream().anyMatch(after::contains)) {
            return true;
        }
        for (String worry : WORRIES) {
            int at = after.indexOf(worry);
            if (at >= 0 && NEGATIONS.stream().anyMatch(window(text, from + at + worry.length())::contains)) {
                return true;
            }
        }
        return false;
    }

    private static String window(String text, int from) {
        return text.substring(from, Math.min(text.length(), from + NEGATION_WINDOW));
    }

    /**
     * The two-character openings the database filters on before this class reads a body —
     * {@code ReviewRepository.SAFETY_STEM_PREDICATE} must name exactly these ({@code ReviewSafetySignalTest}). Two
     * characters because a stored body keeps the customer's spacing, and no term is ever split inside its first two.
     */
    public static Set<String> stems() {
        Set<String> stems = new TreeSet<>();
        for (String term : TERMS.keySet()) {
            stems.add(term.substring(0, 2));
        }
        return stems;
    }
}
