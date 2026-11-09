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
 * <p><b>A promise is also what it leaves out</b> (2026-10-31, first real-model round, C06). The policy said 「수령 후 7일
 * 이내, 미개봉 상품에 한해 반품 가능」 and the draft said 「수령 후 7일 이내 반품 가능」 — every word it wrote is in the
 * evidence, so every check above passed a reply that grants a return for an opened product. {@link #droppedConditions}
 * reads the state a grant is limited to (미개봉 · 미사용 · 미착용) when EVERY evidence clause granting that remedy states it,
 * and refuses a draft that grants the remedy without saying it anywhere — unless the customer already said theirs is in
 * that state. A customer who said theirs is OUT of it (「개봉했는데」) gets the state in every granting clause itself, not
 * just somewhere (seller decision 2026-11-01). Same closed shape: three states and the ways a Korean seller or customer
 * writes them.
 *
 * <p><b>Saying back is not promising</b> (2026-10-31, C03). A customer who wrote 「반품하려다가 그냥 쓰려고요」 was answered
 * 「반품까지 고민하게 해 드린 점 아쉽게 생각합니다」, and the noun test refused it as an unsupported return. The three-argument
 * {@link #unsupportedClaims(String, List, String)} passes a remedy noun only where it is the customer's own experience
 * said back: the customer named that remedy, every sentence of the draft naming it puts it in a weighing verb
 * (고민 · 생각 · 하려 · 망설), and none of those sentences grants, offers or routes anything. Whether a sentence the code
 * cannot settle is the seller's promise is the critique's to read (N9), not this list's.
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
    static final String READING = "clauses-v5:cases,no-overlap,kept-states,said-back,left-states,return-law";

    /**
     * The states a grant can be limited to, each with the ways it is written — by the seller (「미개봉 상품에 한해」) or by a
     * customer saying theirs is in it (「아직 뜯지 않았어요」). Matched with whitespace removed.
     */
    static final java.util.Map<String, List<String>> KEPT_STATES = java.util.Map.of(
            "미개봉", List.of("미개봉", "개봉하지않", "개봉안한", "개봉안했", "개봉전", "뜯지않", "안뜯", "포장그대로"),
            "미사용", List.of("미사용", "사용하지않", "사용안한", "사용안했", "사용전", "쓰지않", "안쓴", "안썼"),
            "미착용", List.of("미착용", "착용하지않", "착용안한", "착용안했", "착용전", "입지않", "안입은", "안입었"));

    /**
     * The ways a customer says theirs is NOT in a kept state — 「개봉했는데」, 「한 번 썼어요」 (seller decision 2026-11-01).
     * Read only after the kept forms are taken out, so 「안 뜯었어요」 is never read as 「뜯었」. 「개봉한 상품도 되나요?」 counts
     * too: whether or not it is theirs yet, it asks about exactly the case the condition is for. Matched with whitespace
     * removed.
     */
    static final java.util.Map<String, List<String>> LEFT_STATES = java.util.Map.of(
            "미개봉", List.of("개봉했", "개봉한", "개봉후", "개봉하고", "뜯었", "뜯은", "뜯어보", "뜯고"),
            "미사용", List.of("사용했", "사용한", "사용후", "사용하고", "써봤", "써본", "썼는데", "썼어", "쓰다가", "쓰던"),
            "미착용", List.of("착용했", "착용한", "착용후", "착용하고", "입어봤", "입어본", "입었"));

    /** A remedy noun in a weighing verb — the customer's 「반품까지 고민」, 「교환할까 생각」, 「환불하려다」. */
    private static final Pattern WEIGHED = Pattern.compile(
            "(?:을|를|까지|도|이나|할까|하실까|해야하나|할지)?(?:고민|생각|하려|하실려|할려|망설|결심)");

    /** What turns a sentence into the seller granting, offering or routing a remedy — never a saying-back. */
    private static final List<String> OFFERS = List.of(
            "드리겠", "드릴게", "드릴수", "해드립", "해드릴", "도와드", "가능", "진행", "처리", "접수", "보내드", "신청",
            "하실수", "해주겠", "해줄", "받아드", "됩니다", "돼요", "되세요");

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
                if (!inside && !has(grounds, term) && SAME_TERM.getOrDefault(term, List.of()).stream()
                        .noneMatch(grounds::contains)) {
                    found.add(term);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * The same cost written another way (2026-11-01, fifth round: 「반품에 드는 비용은 판매자가 부담」 in the policy did not
     * back 「반품 비용은 판매자가 부담」). Matched on text with whitespace removed; a short list, not a thesaurus.
     */
    static final java.util.Map<String, List<String>> SAME_TERM = java.util.Map.of(
            "반품비", List.of("반품비용", "반품에드는비용", "반품배송비", "반품택배비", "반환에필요한비용", "반환비용"),
            "교환비", List.of("교환비용", "교환에드는비용", "교환배송비", "교환택배비"),
            "택배비", List.of("배송비"),
            "배송비", List.of("택배비"));

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
     * The states the evidence limits a granted remedy to that the draft's grant leaves out — 「미개봉」 for 「7일 이내 반품
     * 가능」 against 「7일 이내 미개봉 상품에 한해 반품 가능」. A state counts only when every evidence clause granting that
     * remedy states it: where one grant is unlimited, which case the draft is about is the claim check's to read. The draft
     * keeps the state if it says it anywhere (「단, 개봉하지 않은 상품만」 as the next sentence is fine); the customer keeps
     * it if they said theirs is in it.
     *
     * <p><b>A customer who said theirs is out of it</b> (seller decision 2026-11-01: 「이미 개봉했다고 밝혔다면 교환 가능으로
     * 단정하지 않는다」). Then saying the state somewhere is not enough: 「교환 가능합니다. 미개봉 상품 기준입니다」 still tells
     * them yes. Every clause granting that remedy must state it itself — 「미개봉 상품에 한해 교환 가능합니다」 is the policy
     * read to them, not a yes. Whether another reason applies (a defect, a mismatch with the listing) is the evidence's
     * to say: where one grant of the remedy is unlimited, this list stays out of it as above.
     *
     * @param asked the customer's own words — 「아직 뜯지 않았는데」 makes 「7일 이내 반품 가능」 the right answer
     */
    public static List<String> droppedConditions(String body, List<String> evidence, String asked) {
        if (body == null || body.isBlank() || evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        List<Clause> grounds = new ArrayList<>();
        for (String passage : evidence) {
            if (passage != null) {
                grounds.addAll(clausesOf(passage));
            }
        }
        String draft = normalize(body);
        String customer = normalize(asked == null ? "" : asked);
        if (faultReported(customer)) {
            // A change-of-mind condition does not govern a defect or a wrong item: that is lawConflicts' case, not this.
            return List.of();
        }
        Set<String> dropped = new LinkedHashSet<>();
        for (Clause said : clausesOf(body)) {
            if (said.refusal()) {
                continue;
            }
            for (String remedy : said.remedies()) {
                List<Clause> grants = grounds.stream()
                        .filter(g -> !g.refusal() && g.remedies().contains(remedy)).toList();
                if (grants.isEmpty()) {
                    continue;
                }
                KEPT_STATES.forEach((state, forms) -> {
                    boolean everyGrant = grants.stream().allMatch(g -> states(g.text(), forms));
                    boolean kept = left(customer, state) ? states(said.text(), forms)
                            : states(draft, forms) || states(customer, forms);
                    if (everyGrant && !kept) {
                        dropped.add(state);
                    }
                });
            }
        }
        return KEPT_STATES.keySet().stream().sorted().filter(dropped::contains).toList();
    }

    private static boolean states(String squashed, List<String> forms) {
        return forms.stream().anyMatch(squashed::contains);
    }

    /**
     * The kept states the customer said theirs is out of — 「미개봉」 for 「개봉했는데 교환되나요?」. For the notes a revision
     * reads; {@link #droppedConditions} applies it itself.
     */
    public static List<String> leftStates(String asked) {
        String customer = normalize(asked == null ? "" : asked);
        return KEPT_STATES.keySet().stream().sorted().filter(state -> left(customer, state)).toList();
    }

    private static boolean left(String squashed, String state) {
        String rest = squashed;
        for (String form : KEPT_STATES.get(state)) {
            rest = rest.replace(form, "|");
        }
        String remaining = rest;
        return LEFT_STATES.get(state).stream().anyMatch(remaining::contains);
    }

    /** A return applied to the seller's own unopened-only rule, or refused for opening — 청약철회 (전자상거래법 §17②1). */
    public static final String LAW_WITHDRAWAL = "WITHDRAWAL";
    /** A defect, wrong item or listing mismatch held to change-of-mind terms (미개봉 · 단순 변심) — §17③. */
    public static final String LAW_FAULT = "FAULT";

    /** The remedies that are the customer's statutory withdrawal, not the seller's service: a return and a refund. */
    static final List<String> WITHDRAWALS = List.of("반품", "환불");

    /** What opening is written as — in a refusal or a limit. 「미개봉」 contains it too. Matched with whitespace removed. */
    static final List<String> OPENING = List.of("개봉", "뜯", "포장훼손", "포장을훼손", "포장이훼손");

    /** What a customer reporting a fault says, by closed list — never a meaning test. Matched with whitespace removed. */
    static final List<String> FAULTS = List.of(
            "불량", "하자", "파손", "깨져", "깨진", "깨졌", "고장", "작동안", "작동하지않", "오배송", "잘못왔", "잘못온",
            "잘못배송", "다른상품이", "다른제품이", "다른색이", "사진과달", "사진이랑달", "설명과달", "상세페이지와달",
            "표시와달", "누락", "빠져있", "빠진채");

    /** Change-of-mind terms: what a seller's own policy limits a service to, and never a fault. */
    static final List<String> CHANGE_OF_MIND = List.of("단순변심", "변심");

    /**
     * <b>Whether the seller's condition may be applied to this case at all</b> (2026-11-01 review, after the seller's
     * 「개봉하지 않은 상품에 한해」 decision). {@link #droppedConditions} holds a reply to the seller's policy; this holds the
     * policy's application to the law, and the two are not the same check. Closed shapes, each a clear case:
     *
     * <ul>
     *   <li>{@link #LAW_WITHDRAWAL} — a draft clause that limits a return or refund to an unopened product, refuses one for
     *       opening, or grants one where every evidence grant of it is limited to unopened. A customer may withdraw within
     *       7 days even after opening the packaging to see the contents (전자상거래법 제17조 제2항 제1호 단서), and a term
     *       worse for them than the statute is void (제35조) — so a return answer standing on that policy is the seller's
     *       to confirm, not the model's to write. An exchange for change of mind is the seller's own service: its unopened
     *       limit stays {@link #droppedConditions}' to keep.</li>
     *   <li>{@link #LAW_FAULT} — the customer reports a defect, a wrong item or a mismatch with the listing ({@link #FAULTS})
     *       and a draft clause about any remedy applies an unopened, unused or change-of-mind term to it, or refuses it for
     *       opening (제17조 제3항: such goods may be withdrawn within 3 months).</li>
     * </ul>
     * Saying back is not applying: a clause in a weighing verb that grants nothing (「반품까지 고민하게 해 드린 점」) is
     * skipped as {@link #unsupportedClaims(String, List, String)} skips it. Whether a use that reduced the product's value
     * allows a refusal (제17조 제2항 제2호) is not read here — it is the seller's call, never a list's.
     */
    public static List<String> lawConflicts(String body, List<String> evidence, String asked) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<Clause> grounds = new ArrayList<>();
        for (String passage : evidence == null ? List.<String>of() : evidence) {
            if (passage != null) {
                grounds.addAll(clausesOf(passage));
            }
        }
        boolean fault = faultReported(normalize(asked == null ? "" : asked));
        List<String> unopened = KEPT_STATES.get("미개봉");
        Set<String> found = new LinkedHashSet<>();
        for (Clause said : clausesOf(body)) {
            if (WEIGHED.matcher(said.text()).find() && OFFERS.stream().noneMatch(said.text()::contains)) {
                continue;
            }
            // 「개봉 여부와 관계없이」 names opening to set it aside, not to apply it (2026-11-01, fifth round: the statute's
            // own wording for a defect was held as an unopened term put on a fault).
            String applied = REGARDLESS.matcher(said.text()).replaceAll("·");
            boolean opening = states(applied, OPENING);
            if (fault && (opening || states(applied, CHANGE_OF_MIND)
                    || KEPT_STATES.values().stream().anyMatch(forms -> states(applied, forms)))) {
                found.add(LAW_FAULT);
            }
            for (String remedy : said.remedies()) {
                if (!WITHDRAWALS.contains(remedy)) {
                    continue;
                }
                List<Clause> grants = grounds.stream()
                        .filter(g -> !g.refusal() && g.remedies().contains(remedy)).toList();
                boolean policyLimits = !grants.isEmpty() && grants.stream().allMatch(g -> states(g.text(), unopened));
                if (said.refusal() ? opening : states(applied, unopened) || (policyLimits && !fault)) {
                    found.add(LAW_WITHDRAWAL);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * A state named to set it aside — 「개봉 여부와 관계없이」, 「사용하셨더라도」 — matched on text with whitespace removed.
     * What it names does not limit the remedy, so the law check does not read it as a limit.
     */
    static final Pattern REGARDLESS = Pattern.compile(
            "(미?개봉|뜯|포장훼손|미?사용|미?착용|단순변심|변심)(여부|유무|상태)?(와|과|에)?(관계|상관)없이"
                    + "|(개봉|뜯|사용|착용)(을|를)?(하셨|하신|했|한|하|해)?(더라도|어도|해도|셔도|하셔도)");

    static boolean faultReported(String squashed) {
        return states(squashed, FAULTS);
    }

    /**
     * As {@link #unsupportedClaims(String, List)}, except a remedy noun the customer named and the draft only says back —
     * every draft sentence naming it weighs it (「반품까지 고민하게 해 드린 점」) and grants, offers or routes nothing.
     *
     * @param reviewText the customer's own words; null or blank passes nothing back
     */
    public static List<String> unsupportedClaims(String body, List<String> evidence, String reviewText) {
        List<String> found = unsupportedClaims(body, evidence);
        if (found.isEmpty() || reviewText == null || reviewText.isBlank()) {
            return found;
        }
        List<String> named = remediesIn(normalize(reviewText));
        List<String> sentences = new ArrayList<>();
        for (String sentence : body.split("(?<=[.!?。])\\s+|\\n+")) {
            sentences.add(normalize(sentence));
        }
        return found.stream().filter(term -> !(named.contains(term) && saidBack(term, sentences))).toList();
    }

    private static boolean saidBack(String term, List<String> sentences) {
        boolean any = false;
        for (String sentence : sentences) {
            if (!remediesIn(sentence).contains(term)) {
                continue;
            }
            any = true;
            if (OFFERS.stream().anyMatch(sentence::contains)) {
                return false;
            }
            boolean weighed = false;
            for (int i = sentence.indexOf(term); i >= 0; i = sentence.indexOf(term, i + 1)) {
                Matcher m = WEIGHED.matcher(sentence);
                m.region(i + term.length(), sentence.length());
                if (!m.lookingAt()) {
                    return false;
                }
                weighed = true;
            }
            if (!weighed) {
                return false;
            }
        }
        return any;
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
