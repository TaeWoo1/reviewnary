package com.sellerops.review.draft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A public reply may promise only what the seller's own text already promises.</b>
 * (Grounded Review Drafting v1, 2026-09-03)
 *
 * <p>The prompt asks for this. This checks it — the same relationship {@code AnswerStyleInstruction}
 * has with the org's forbidden phrases, and for the same reason: asking politely is not a control. A
 * review reply is public and irreversible, and the sentence that does the damage is never a wrong
 * specification. It is 「교환 도와드리겠습니다」 written for a seller who never agreed to an exchange.
 *
 * <p><b>What it looks for is a COMMITMENT, not a topic.</b> The seven classes the product refuses to
 * invent (cause, usage, delivery timing, exchange/refund, compensation, re-shipment, internal action)
 * are not all detectable in text, and pretending otherwise would be the sentiment heuristic
 * {@code RuleBasedReviewReplyProvider} refuses to grow. Five of them share one observable shape: a
 * named remedy. Those five are checked here by their nouns, and the check is deliberately narrow —
 * it can miss an invented cause and it will never call a plain thank-you a promise.
 *
 * <p><b>The test is not "did the model use a forbidden word".</b> It is «did the model promise
 * something the evidence does not mention». A seller whose exchange policy is in front of the
 * drafter may absolutely have an exchange written into the reply — that is the whole point of
 * grounding. So a term is a violation only when it appears in the draft and appears in NONE of the
 * passages the drafter was shown.
 *
 * <p><b>The response is refusal, never editing.</b> Deleting 「교환」 out of a sentence leaves a reply
 * whose meaning nobody chose; keeping it makes the rule a preference. The caller falls back to the
 * seller's own template, which promises nothing by construction.
 *
 * <p><b>A promise is its word and its terms</b> (2026-10-23 external review). The word alone is not enough: a policy
 * that still says 「교환」 after its period went from 7 to 14 days still "supports" 「7일 이내 교환」 by the noun test.
 * {@link #unsupportedConditions} reads the terms a promise sentence states — how long (7일 · 즉시), how much (무료 ·
 * 전액 · 왕복 배송비) and for what (단순 변심 · 개봉 후) — and each must be in the evidence too. Same closed shape: a
 * short list, checked against the evidence, never a meaning test. It cannot see a term written in words it does not
 * list, nor a term the evidence states somewhere unrelated to the promise.
 *
 * <p>Pure: no I/O, no model, no state.
 */
public final class ReviewClaimGuard {

    /**
     * The remedies a reply may not offer unprompted, by the nouns a Korean seller writes them with.
     *
     * <p>Stems rather than full words: 「환불」 catches 환불해, 환불을, 환불 드리 — Korean attaches
     * particles and verb endings to the noun, and a whole-word list would miss every real sentence.
     * Short and reviewable on purpose; a longer list is a classifier by another name.
     */
    static final List<String> COMMITMENT_TERMS = List.of(
            "환불", "반품", "교환", "재발송", "재배송", "보상", "배상", "할인", "쿠폰", "적립금", "회수");

    /**
     * A period or an amount a promise states: a number with its unit. Read from the sentence as written — squashing
     * 「1 7일」 would read a number nobody wrote.
     */
    static final Pattern FIGURE = Pattern.compile(
            "\\d+(?:[.,]\\d+)*\\s*(?:영업일|일|시간|개월|주|년|만원|원|%|퍼센트|회|번)");

    /**
     * The terms of a promise in words, by what a Korean seller writes — when (no number), how much, and for what.
     * Matched with whitespace removed. A term that has a listed 「미」 form (개봉 · 미개봉) is not matched inside it:
     * 「미개봉 상품만 교환」 must never authorize 「개봉 후에도 교환」.
     */
    static final List<String> CONDITION_TERMS = List.of(
            "즉시", "당일", "익일", "일주일", "한달",
            "전액", "무료", "무상", "차액", "왕복", "편도", "배송비", "택배비", "반품비", "교환비",
            "단순변심", "변심", "개봉", "미개봉", "사용후", "미사용", "착용", "미착용", "세탁후", "포장훼손",
            "모든상품", "전상품", "전제품");

    private ReviewClaimGuard() {
    }

    /**
     * The terms the draft's promise sentences state that the evidence does not — each one as the draft wrote it,
     * whitespace removed, in the order they appear. Only a sentence that carries a {@link #COMMITMENT_TERMS} noun the
     * evidence supports is read: 「7일 걸렸네요」 promises nothing, and a remedy the evidence never names is already
     * {@link #unsupportedClaims}' finding.
     *
     * @param body       the reply
     * @param evidence   the passage texts that may authorize a promise — the same ones {@link #unsupportedClaims} reads
     * @param reviewText the customer's own review: it may authorize a number repeated back to them (「3일 만에
     *                   고장」) and nothing else — a customer asking for 「단순 변심 환불」 is not the seller granting it
     */
    public static List<String> unsupportedConditions(String body, List<String> evidence, String reviewText) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String grounds = normalize(evidence == null ? "" : String.join(" ", evidence));
        String asked = normalize(reviewText == null ? "" : reviewText);
        Set<String> found = new LinkedHashSet<>();
        for (String sentence : body.split("(?<=[.!?。])\\s+|\\n+")) {
            String squashed = normalize(sentence);
            List<String> remedies = COMMITMENT_TERMS.stream().filter(squashed::contains).toList();
            // No remedy, nothing promised; a remedy the evidence does not name at all is unsupportedClaims' finding,
            // and its terms add nothing to it.
            if (remedies.isEmpty() || remedies.stream().anyMatch(r -> !grounds.contains(r))) {
                continue;
            }
            Matcher figure = FIGURE.matcher(sentence);
            while (figure.find()) {
                String f = normalize(figure.group()).replace(",", "");
                if (!statesFigure(grounds, f) && !statesFigure(asked, f)) {
                    found.add(f);
                }
            }
            List<String> stated = CONDITION_TERMS.stream().filter(t -> has(squashed, t)).toList();
            for (String term : stated) {
                // 「단순 변심」 is one term, not two: the shorter one inside a longer stated term is not reported again.
                boolean inside = stated.stream().anyMatch(o -> o.length() > term.length() && o.contains(term));
                if (!inside && !has(grounds, term)) {
                    found.add(term);
                }
            }
        }
        return List.copyOf(found);
    }

    /** Whether {@code text} states the figure itself — 「7일」 is not inside 「17일」, nor 「1000원」 inside 「11,000원」. */
    private static boolean statesFigure(String text, String figure) {
        return Pattern.compile("(?<![\\d.])" + Pattern.quote(figure)).matcher(text.replace(",", "")).find();
    }

    /** Whether {@code text} states {@code term} — not only inside its listed 「미」 form. */
    private static boolean has(String text, String term) {
        boolean negatedForm = CONDITION_TERMS.contains("미" + term);
        for (int i = text.indexOf(term); i >= 0; i = text.indexOf(term, i + 1)) {
            if (!negatedForm || i == 0 || text.charAt(i - 1) != '미') {
                return true;
            }
        }
        return false;
    }

    /**
     * Which commitment terms the draft makes that the evidence does not support.
     *
     * @param body     the generated reply
     * @param evidence the passage texts the drafter was actually shown — the ONLY thing that can
     *                 authorize a commitment. An empty list means nothing may be promised at all.
     * @return the unsupported terms, in the order they are declared; empty when the draft is clean
     */
    public static List<String> unsupportedClaims(String body, List<String> evidence) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String draft = normalize(body);
        String grounds = evidence == null ? "" : normalize(String.join(" ", evidence));
        List<String> found = new ArrayList<>();
        for (String term : COMMITMENT_TERMS) {
            if (draft.contains(term) && !grounds.contains(term)) {
                found.add(term);
            }
        }
        return List.copyOf(found);
    }

    /** Whitespace collapsed and lower-cased, so spacing between a noun and its particle cannot hide it. */
    private static String normalize(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
