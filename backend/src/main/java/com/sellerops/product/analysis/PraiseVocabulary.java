package com.sellerops.product.analysis;

import com.sellerops.reviewissue.IssueVocabulary;
import com.sellerops.reviewissue.NegationScope;
import java.util.List;
import java.util.Optional;

/**
 * The closed praise list the product analysis reads a <b>strength</b> with — the counterpart of
 * {@link IssueVocabulary}'s problems, applied to the same opinion units and the same aspects.
 *
 * <p><b>Why a word list and not the rating (night handoff 2026-11-16, owner's recommendation adopted).</b>
 * A 5★ review that says nothing about the lid is not evidence that the lid is good. A strength here is
 * an opinion unit that names an aspect from {@link IssueVocabulary} AND praises it with one of the words
 * below, not negated, not a wish (「더 튼튼했으면 좋겠어요」) and not a question. Praise that names no
 * aspect (「너무 좋아요」) is counted separately and never becomes a strength.
 *
 * <p><b>Measured state: none.</b> {@code RuleBasedIssueSignatureExtractor} records why a praise list
 * "needs its own bar" — this list has no labelled measurement yet, so the screen says 「규칙 기반 ·
 * 정확도 측정 전」 and every strength carries the customer's own sentence to check it against. It feeds
 * the analysis read only: never a queue, a reply, a draft or an approval.
 */
public final class PraiseVocabulary {

    public static final String VERSION = "praise-rules-v1";

    /** Praise forms. Closed; widen only against labelled reviews. */
    private static final List<String> PRAISE = List.of(
            "좋아요", "좋아서", "좋아용", "좋네", "좋습니다", "좋았", "좋고", "좋은", "좋음", "좋더", "좋다",
            "만족", "훌륭", "최고", "마음에 들", "맘에 들", "마음에 쏙", "맘에 쏙", "추천",
            "빠르", "빨라", "빨리", "빠른", "꼼꼼", "튼튼", "단단", "견고", "안전하게", "깔끔", "매끈",
            "예쁘", "예뻐", "이쁘", "이뻐", "고급스럽", "딱 맞", "잘 맞", "정확", "쉽", "쉬워", "쉬웠",
            "간편", "편해", "편하", "편리", "자세", "친절", "저렴", "착해", "착한", "잘 붙", "잘 도착", "잘 왔",
            "잘 받");

    /**
     * A syllable right before the praise word that turns it into its opposite: 불만족 · 불편 · 아쉽 · 비추천.
     */
    private static final String OPPOSITE_PREFIX = "불아비";

    /** A wish or a conditional is a request for the quality, not a report of it. */
    private static final List<String> WISH = List.of(
            "면 좋겠", "면 좋을", "았으면", "었으면", "했으면", "좋겠", "좋을 것", "좋을지", "좋을까", "였으면");

    /** A question is not a report: 「설치 쉬운가요?」. */
    private static final List<String> QUESTION = List.of("?", "나요", "까요", "는지", "을지");

    private PraiseVocabulary() {
    }

    /** One praise word found in a unit, with where it sits. */
    public record PraiseHit(String word, int start, int end) {
    }

    /** The first praise word in {@code unit} that reports the quality, or empty. */
    public static Optional<PraiseHit> praiseOf(String unit) {
        if (unit == null || unit.isBlank()) {
            return Optional.empty();
        }
        for (String w : WISH) {
            if (unit.contains(w)) {
                return Optional.empty();
            }
        }
        for (String q : QUESTION) {
            if (unit.contains(q)) {
                return Optional.empty();
            }
        }
        for (String word : PRAISE) {
            int at = unit.indexOf(word);
            while (at >= 0) {
                int end = at + word.length();
                if (!oppositePrefix(unit, at) && !negated(unit, at, end)) {
                    return Optional.of(new PraiseHit(word, at, end));
                }
                at = unit.indexOf(word, at + 1);
            }
        }
        return Optional.empty();
    }

    /**
     * The aspect the unit praises: the first aspect named and not itself negated before it
     * (「미설치」, 「안 붙여 봐서」). Empty when no aspect is named — 「너무 좋아요」 praises nothing in particular.
     */
    public static Optional<String> praisedAspect(String unit) {
        for (IssueVocabulary.Hit hit : IssueVocabulary.aspectHits(unit)) {
            if (!NegationScope.negatedBefore(unit, hit.start(), true)) {
                return Optional.of(hit.key());
            }
        }
        return Optional.empty();
    }

    private static boolean oppositePrefix(String unit, int at) {
        return at >= 1 && OPPOSITE_PREFIX.indexOf(unit.charAt(at - 1)) >= 0;
    }

    /**
     * Negated by the shared rule (「안 좋아요」 · 「좋지 않아요」), or by 않/못 a few characters on inside the
     * same clause (「만족스럽지 않아요」 — 스럽 is not one of the shared bridge tokens). The clause ends at 서/고
     * followed by a space, so 「튼튼해서 걱정 없어요」 keeps its praise.
     */
    private static boolean negated(String unit, int start, int end) {
        if (NegationScope.negated(unit, start, end, false)) {
            return true;
        }
        int limit = Math.min(unit.length(), end + 6);
        String after = unit.substring(end, limit);
        int cut = clauseEnd(after);
        String clause = cut < 0 ? after : after.substring(0, cut);
        return clause.contains("않") || clause.contains("못");
    }

    private static int clauseEnd(String s) {
        int best = -1;
        for (String connective : List.of("서 ", "고 ", "며 ", "데 ")) {
            int at = s.indexOf(connective);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }
}
