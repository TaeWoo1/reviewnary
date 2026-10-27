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
 * <p><b>A promise is also its yes or no</b> (2026-10-27 review). 「단순 변심은 무료 교환이 불가합니다」 holds every word
 * and term of 「단순 변심도 무료 교환이 가능합니다」, so both checks above pass the reversal.
 * {@link #contradictedPromises} reads each remedy clause as a grant or a refusal — a refusal word written after the
 * remedy noun, where Korean puts its predicate — and refuses a draft clause when the evidence has a clause about the same
 * remedy under the same stated terms that says the opposite, and none that says the same. Same closed shape again: it
 * reads the clause's ending only, so it cannot see a refusal in words {@link #REFUSALS} does not list, nor one that
 * ends on something else (「교환이 어려운 점 양해 부탁드립니다」).
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

    /**
     * The words that make a remedy clause a refusal — read only in its ending ({@value #ENDING} characters, whitespace
     * and brackets removed, after the last remedy noun), where Korean puts the yes or no. Anywhere else they qualify:
     * 「개봉하지 않은 상품에 한해 가능합니다」 and 「7일(주말 제외) 이내 가능합니다」 grant. Not 「없」 on its own:
     * 「추가 비용 없이 교환」 and 「배송비 부담은 없습니다」 grant; 「할 수 없」 refuses.
     */
    static final List<String> REFUSALS = List.of(
            "불가", "어렵", "어려운", "안됩", "안되", "안돼", "않", "못합", "못하", "못드", "수없", "제외", "불허", "금지");

    /**
     * Which way of reading a promise this is — a change to how a clause is matched changes what waiting drafts are
     * held to, so it is part of {@code ReviewDraftBasis}' policy fingerprint like the word lists are.
     */
    static final String READING = "clauses-v2:cases,no-overlap";

    /** How much of a clause's end is its predicate. */
    static final int ENDING = 8;

    /** A clause boundary inside a sentence: a Korean connective that can join a yes to a no. */
    private static final Pattern CLAUSE = Pattern.compile("(?<=며|지만|으나|하고)[,\\s]+|(?<=나,)\\s*");

    private ReviewClaimGuard() {
    }

    /**
     * The remedies the draft promises — or refuses — the opposite of what the evidence says, in the order the draft
     * says them. A clause counts when it names a {@link #COMMITMENT_TERMS} noun, or follows one in the same sentence
     * (「…가능하며, 단순 변심은 불가합니다」), and it refuses when its ending carries a {@link #REFUSALS} word.
     *
     * <p>When every evidence clause about the remedy answers one way, a draft clause answering the other way is
     * contradicted if those clauses state every {@link #CONDITION_TERMS} term it states. When the evidence answers
     * both ways — 「7일 이내 가능 · 14일 이후 불가」, 「초기 불량은 가능 · 고객 과실은 불가」 — a yes about one case does not
     * license the other (2026-10-27 second review): the draft clause is read against the evidence clause whose own
     * wording (period, reason, scope) it shares most, and is contradicted when that clause says the opposite. A draft
     * the evidence cannot place either way is {@link #undecidedPromises}, never passed.
     *
     * @param body     the reply
     * @param evidence the passage texts that may authorize a promise — the same ones {@link #unsupportedClaims} reads
     */
    public static List<String> contradictedPromises(String body, List<String> evidence) {
        return judged(body, evidence).contradicted();
    }

    /**
     * The remedies the draft answers where the evidence answers the same remedy both ways and nothing in the draft's
     * clause says which case it is — 「교환해 드리겠습니다」 against 「7일 이내 가능 · 14일 이후 불가」. Not a contradiction
     * the guard can prove, and not a promise it can pass: a reply that needs the seller's eyes.
     */
    public static List<String> undecidedPromises(String body, List<String> evidence) {
        return judged(body, evidence).undecided();
    }

    private record Judged(List<String> contradicted, List<String> undecided) {
    }

    private static Judged judged(String body, List<String> evidence) {
        if (body == null || body.isBlank() || evidence == null || evidence.isEmpty()) {
            return new Judged(List.of(), List.of());
        }
        List<Clause> grounds = new ArrayList<>();
        for (String passage : evidence) {
            grounds.addAll(clausesOf(passage));
        }
        Set<String> contradicted = new LinkedHashSet<>();
        Set<String> undecided = new LinkedHashSet<>();
        for (Clause said : clausesOf(body)) {
            List<Clause> about = grounds.stream()
                    .filter(g -> g.remedies().stream().anyMatch(said.remedies()::contains)).toList();
            boolean yes = about.stream().anyMatch(g -> g.refusal() == said.refusal());
            boolean no = about.stream().anyMatch(g -> g.refusal() != said.refusal());
            if (!no) {
                continue;
            }
            if (!yes) {
                // One answer only: opposite under the same stated terms is a contradiction; a draft naming a term the
                // evidence never speaks to is unsupportedConditions' finding.
                List<String> terms = CONDITION_TERMS.stream().filter(t -> has(said.text(), t)).toList();
                if (about.stream().anyMatch(g -> terms.stream().allMatch(t -> has(g.text(), t)))) {
                    contradicted.addAll(said.remedies());
                }
                continue;
            }
            // Both answers: which case is the draft about? The wording no clause about this remedy shares with all of
            // them — 「14일 이후」, 「고객 과실」 — and the clause the draft shares most of it with.
            Set<String> common = null;
            for (Clause g : about) {
                if (common == null) {
                    common = new java.util.HashSet<>(g.wording());
                } else {
                    common.retainAll(g.wording());
                }
            }
            int same = 0;
            int opposite = 0;
            for (Clause g : about) {
                Set<String> own = new java.util.HashSet<>(g.wording());
                own.removeAll(common);
                own.retainAll(said.wording());
                if (g.refusal() == said.refusal()) {
                    same = Math.max(same, own.size());
                } else {
                    opposite = Math.max(opposite, own.size());
                }
            }
            if (opposite > same) {
                contradicted.addAll(said.remedies());
            } else if (opposite == same) {
                undecided.addAll(said.remedies());
            }
        }
        return new Judged(List.copyOf(contradicted), List.copyOf(undecided));
    }

    /**
     * The two-letter pieces of how a yes or a no is said — 「가능합니다」, 「불가하며」, 「드리겠습니다」. They tell the two
     * answers apart and nothing about which case a clause is about, so case matching never counts them.
     */
    private static final Set<String> PREDICATE_PIECES = bigrams(String.join("|", List.of(
            "가능합니다", "가능하며", "가능해요", "가능하고", "불가합니다", "불가하며", "불가능합니다", "어렵습니다", "어려운",
            "안됩니다", "안되며", "안돼요", "되지않습니다", "하지않습니다", "못합니다", "못드립니다", "할수없습니다",
            "제외됩니다", "드립니다", "드리겠습니다", "해드립니다", "해드리겠습니다", "도와드리겠습니다", "됩니다", "입니다")));

    private static Set<String> bigrams(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            String piece = text.substring(i, i + 2);
            if (piece.indexOf('|') < 0) {
                out.add(piece);
            }
        }
        return out;
    }

    /**
     * One remedy clause: its text without whitespace, the remedies it is about, whether it refuses them, and its
     * wording — the two-letter pieces of what it says apart from the remedy nouns and the yes or no itself.
     */
    private record Clause(String text, List<String> remedies, boolean refusal, Set<String> wording) {
    }

    private static List<Clause> clausesOf(String text) {
        List<Clause> out = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?。])\\s+|\\n+")) {
            List<String> carried = List.of();
            for (String part : CLAUSE.split(sentence)) {
                String squashed = normalize(part);
                List<String> named = remediesIn(squashed);
                List<String> remedies = named.isEmpty() ? carried : named;
                if (remedies.isEmpty()) {
                    continue;
                }
                // The predicate: the clause's ending, after the last noun it names — 「불가 품목은 교환」 ends on a grant.
                String plain = normalize(part.replaceAll("\\([^)]*\\)", "")).replaceAll("[.!?。,~…]+$", "");
                int from = lastRemedyEnd(plain);
                String ending = plain.substring(Math.max(Math.max(from, 0), plain.length() - ENDING));
                String bare = plain;
                for (String r : COMMITMENT_TERMS) {
                    bare = bare.replace(r, "|");
                }
                Set<String> wording = bigrams(bare);
                wording.removeAll(PREDICATE_PIECES);
                out.add(new Clause(squashed, remedies, REFUSALS.stream().anyMatch(ending::contains), wording));
                carried = remedies;
            }
        }
        return out;
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
            List<String> remedies = remediesIn(squashed);
            // No remedy, nothing promised; a remedy the evidence does not name at all is unsupportedClaims' finding,
            // and its terms add nothing to it.
            Set<String> grounded = remediesOf(evidence);
            if (remedies.isEmpty() || remedies.stream().anyMatch(r -> !grounded.contains(r))) {
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
        List<String> said = remediesIn(normalize(body));
        Set<String> granted = remediesOf(evidence);
        List<String> found = new ArrayList<>();
        for (String term : COMMITMENT_TERMS) {
            if (said.contains(term) && !granted.contains(term)) {
                found.add(term);
            }
        }
        return List.copyOf(found);
    }

    /**
     * The remedy nouns {@code squashed} names, in declaration order — read left to right, each from where the last one
     * ended. With whitespace gone, 「교환 불가」 is 「교환불가」 and holds 「환불」; that 환불 was never written, and reading it
     * made a policy that refuses exchanges authorize a refund (2026-10-27 second review).
     */
    static List<String> remediesIn(String squashed) {
        Set<String> found = new LinkedHashSet<>();
        scan(squashed, (term, end) -> found.add(term));
        return COMMITMENT_TERMS.stream().filter(found::contains).toList();
    }

    /** The remedy nouns any of {@code texts} names, each text read on its own so no noun spans two of them. */
    private static Set<String> remediesOf(List<String> texts) {
        Set<String> out = new LinkedHashSet<>();
        if (texts != null) {
            texts.forEach(t -> out.addAll(remediesIn(normalize(t == null ? "" : t))));
        }
        return out;
    }

    /** Where the last remedy noun {@code squashed} names ends, or 0 when it names none. */
    private static int lastRemedyEnd(String squashed) {
        int[] last = {0};
        scan(squashed, (term, end) -> last[0] = end);
        return last[0];
    }

    private static void scan(String squashed, java.util.function.ObjIntConsumer<String> each) {
        int i = 0;
        while (i < squashed.length()) {
            String hit = null;
            for (String term : COMMITMENT_TERMS) {
                if (squashed.startsWith(term, i)) {
                    hit = term;
                    break;
                }
            }
            if (hit == null) {
                i++;
            } else {
                i += hit.length();
                each.accept(hit, i);
            }
        }
    }

    /** Whitespace collapsed and lower-cased, so spacing between a noun and its particle cannot hide it. */
    private static String normalize(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
