package com.sellerops.inquiry.draft;

import com.sellerops.review.draft.ReviewClaimGuard;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>An inquiry reply may say only what its evidence says</b> (Inquiry Claim Guard v1, 2026-09-19).
 *
 * <p>The review lane has had {@link ReviewClaimGuard} since Grounded Review Drafting v1; the inquiry lane checked a
 * model's reply for the seller's forbidden phrases and nothing else. The Past Answer Prefill proof
 * ({@code apr-3d8b27e5}) then produced, over two passages that say how the parts come apart, a GROUNDED draft ending
 * 「추가로 필요하시면 사용 중 사진을 보내주시면 확인 후 안내드리겠습니다」 — a request the seller never made of a customer
 * and a follow-up nobody on the seller's side agreed to. Both were invented, and both would have gone out in the
 * seller's voice.
 *
 * <p><b>Six closed shapes, each checked against the evidence rather than banned outright.</b> A shape is a violation
 * only when the draft has it and the text that may authorize it does not:
 * <ul>
 *   <li>{@link Kind#SELLER_PROMISE} — a follow-up the seller commits to: 「확인 후 안내드리겠습니다」, 「연락드리겠습니다」,
 *   「조치하겠습니다」 — or a saying only the seller can stand behind ({@link #STAND_BEHIND}: 「안내받으실 수 있습니다」,
 *   「후기 하나하나 확인하고 있습니다」, 「살펴보겠습니다」, a contact path). Authorized by the evidence or by the seller's own
 *   approved sentences (their unknown-fallback and required phrases).</li>
 *   <li>{@link Kind#CUSTOMER_ACTION} — the customer asked to send or attach something: 사진·영상·주문번호·송장 + 보내·첨부·
 *   올려. Authorized by the evidence or the seller's approved sentences.</li>
 *   <li>{@link Kind#REMEDY} — a named remedy (환불·교환·보상…), by {@link ReviewClaimGuard#unsupportedClaims}'s own list,
 *   authorized by the evidence alone — and answered the way the evidence answers it, by
 *   {@link ReviewClaimGuard#contradictedPromises}. Reused, not copied, and that class is not changed.</li>
 *   <li>{@link Kind#CONDITION} — a remedy granted without the state every evidence grant of it is limited to
 *   (「미개봉」 left out of 「7일 이내 교환 가능」), unless the customer's question says theirs is in it —
 *   {@link ReviewClaimGuard#droppedConditions} (2026-10-31).</li>
 *   <li>{@link Kind#LAW} — the policy applied where the law does not let it apply: a return or refund limited to an
 *   unopened product or refused for opening, or a defect held to change-of-mind terms —
 *   {@link ReviewClaimGuard#lawConflicts} (2026-11-01). It holds the policy's application, not the reply's fidelity.</li>
 *   <li>{@link Kind#FIGURE} — a number the reply states that the text alone can refuse: written nowhere in the evidence,
 *   the order's sentence or the customer's question, or only in another unit. Whether a figure that IS written there is
 *   about the same thing under the same condition is not decided here — {@link #figuresToVerify} hands those to the
 *   drafting model's figure check, and anything it does not confirm is refused (owner, 2026-10-28).</li>
 * </ul>
 *
 * <p><b>What it does not do.</b> It is not a fact checker: an invented cause or a paraphrase that changes a meaning
 * without a number or one of these shapes passes. Its figure check is the certain half only (value and unit); meaning
 * is the model's.
 * The lists are short on purpose; a longer one is a classifier by another name. It does not stop a clarifying question — 「어떤 규격을 쓰실지 알려주시면」 names no material to send.
 *
 * <p><b>The response is refusal, never editing</b>, for the reason {@link ReviewClaimGuard} gives: a reply with a
 * clause deleted says something nobody chose.
 *
 * <p>Pure: no I/O, no model, no state.
 */
public final class InquiryClaimGuard {

    public enum Kind { SELLER_PROMISE, CUSTOMER_ACTION, REMEDY, FIGURE, CONDITION, LAW }

    /** One unsupported claim: its kind and the text that matched. */
    public record Violation(Kind kind, String matched) {
    }

    /** Matched on text with whitespace removed, so spacing between a noun, its particle and its verb cannot hide it. */
    static final List<Pattern> SELLER_PROMISES = List.of(
            Pattern.compile("확인(?:후|해서|하여|한뒤|한후|뒤|하고|되는대로|되면)(?:에)?(?:다시|바로|빠르게|신속히|곧)?"
                    + "(?:안내|연락|답변|회신|알려)"),
            Pattern.compile("(?:연락|회신)(?:을|를)?(?:다시)?드리(?:겠|도록)"),
            Pattern.compile("다시(?:안내|연락)(?:드리|해)"),
            Pattern.compile("조치(?:하겠|해드리겠|드리겠|하도록)"));

    /**
     * The first shape with words between — 「확인해 안내드리겠습니다」, 「확인한 뒤 처리 방법을 안내드리겠습니다」 (2026-10-31: three of
     * six saved review drafts carried one past the first pattern, which wants the follow-up word right after 「확인 후」).
     * The seller's own ending makes it a promise. Read only where the first pattern found nothing, and authorized by the
     * seller's sentences matching either: it is the same promise, written looser.
     */
    static final Pattern LATER_ANSWER = Pattern.compile(
            "확인(?:후|해서|해|하여|한뒤|한후|뒤|하고|되는대로|되면)[^.!?\\n]{0,12}?(?:안내|연락|답변|회신|알려)"
                    + "(?:드리|드립|해드리|해드립|드릴|하겠|해주겠|해줄)");

    /** The follow-ups {@code draft} makes that {@code voice} does not authorize, as matched — squashed text both. */
    private static List<String> followUps(String draft, String voice) {
        List<String> out = new ArrayList<>();
        for (Pattern promise : SELLER_PROMISES) {
            Matcher m = promise.matcher(draft);
            if (m.find() && !promise.matcher(voice).find()) {
                out.add(m.group());
            }
        }
        Matcher looser = LATER_ANSWER.matcher(draft);
        if (!SELLER_PROMISES.get(0).matcher(draft).find() && looser.find()
                && !LATER_ANSWER.matcher(voice).find() && !SELLER_PROMISES.get(0).matcher(voice).find()) {
            out.add(looser.group());
        }
        return out;
    }

    /**
     * <b>Sayings the seller has to stand behind</b> (2026-11-01 review, the expression rules restated): written only when
     * the seller's material or own sentences say the same. Each is matched on squashed text, and each is authorized by its
     * own pattern matching the authorizing side.
     * <ul>
     *   <li>a follow-up service, whatever its subject — 「안내받으실 수 있습니다」 promises a guidance nobody confirmed exists
     *       as surely as 「안내드리겠습니다」 does;</li>
     *   <li>an operating claim — 「후기 하나하나 확인하고 있습니다」 is true only where the seller says it is done;</li>
     *   <li>「잘 살펴보겠습니다」 — not in a default reply; a thank-you or empathy closes it instead;</li>
     *   <li>a contact path — 고객센터 · 1:1 문의 · 문의하기 · 톡톡 · 전화 · 메일: only one the material names.</li>
     * </ul>
     */
    static final List<Pattern> STAND_BEHIND = List.of(
            Pattern.compile("(?:안내|도움|상담|처리|답변|지원)(?:을|를)?받(?:으실|을|으시|아보실)수(?:있|도있)"),
            Pattern.compile("(?:안내|도움|상담|처리)(?:을|를)?(?:해)?드릴수(?:있|도있)"),
            Pattern.compile("(?:하나하나|한분한분|한건한건|빠짐없이|모든(?:후기|리뷰|의견))(?:를|을)?(?:꼼꼼히|모두)?"
                    + "(?:확인|읽|살펴|검토|보고)"),
            Pattern.compile("(?:후기|리뷰|의견)(?:를|을)?(?:꼼꼼히|매일|항상)(?:확인|읽|살펴|검토)(?:하고|하며)?있"),
            Pattern.compile("살펴보(?:겠|도록|고있)"),
            Pattern.compile("고객센터|1:1문의|일대일문의|문의하기|톡톡|채팅상담|문의게시판|q&a|카카오톡|카톡|"
                    + "전화(?:로|주시|문의|상담)|이메일|메일(?:로|주시)", Pattern.CASE_INSENSITIVE));

    /**
     * The {@link #STAND_BEHIND} sayings {@code body} has that {@code authorizing} (the seller's material and approved
     * sentences) does not, as matched. Used by both lanes: the inquiry guard as {@link Kind#SELLER_PROMISE}, the review
     * drafter and reply agent as N9.
     */
    public static List<String> unbackedSayings(String body, List<String> authorizing) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String draft = squash(body).toLowerCase(java.util.Locale.ROOT);
        String voice = squash(join(authorizing)).toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Pattern saying : STAND_BEHIND) {
            Matcher m = saying.matcher(draft);
            if (m.find() && !saying.matcher(voice).find()) {
                out.add(m.group());
            }
        }
        return List.copyOf(out);
    }

    static final Pattern CUSTOMER_ACTION = Pattern.compile(
            "(사진|영상|동영상|이미지|주문번호|송장번호|송장)[^.!?\\n]{0,12}?(보내|첨부|올려|전송|남겨|회신)");

    /**
     * The same material, as a seller writes that they collect it — 「사진을 받아 확인한 뒤 안내합니다」 is the seller asking
     * for a photo in their own words, so on the authorizing side receiving counts as well as sending.
     */
    static final Pattern CUSTOMER_ACTION_AUTHORIZED = Pattern.compile(
            "(사진|영상|동영상|이미지|주문번호|송장번호|송장)[^.!?\\n]{0,12}?(보내|첨부|올려|전송|남겨|회신|받)");

    /**
     * A number that states something: followed by a unit, or one end of a range. A list marker 「1.」 is not a figure
     * and must not be able to refuse a draft.
     */
    static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?(?=\\s*(?:~|-|mm|cm|ml|oz|온스|kg|g\\b|m\\b|L\\b|"
            + "리터|미터|센티|일|시간|분|개월|개|원|%|호|가닥|장|매|박스|세트|주|년|회|번째|차))");

    private InquiryClaimGuard() {
    }

    /**
     * The follow-ups a reply promises that {@code authorizing} does not — 「확인 후 안내드리겠습니다」, 「연락드리겠습니다」.
     * The same patterns as {@link Kind#SELLER_PROMISE}, for the review lane's reply agent (owner, 2026-10-28 earlier:
     * 「확인 전 안내는 답글에 쓰지 않게 해줘」 — a lesson the first agent prompt lost).
     */
    public static List<String> unauthorizedFollowUps(String body, List<String> authorizing) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return List.copyOf(followUps(squash(body), squash(join(authorizing))));
    }

    /**
     * @param body        the generated reply
     * @param evidence    the passage texts the drafter was shown — the only thing that authorizes a fact or a remedy
     * @param sellerVoice the seller's own approved sentences (unknown-fallback, required phrases) — they may authorize
     *                    a follow-up or a request, because the seller wrote them to be said
     * @param asked       the customer's question — it may authorize a number the reply says back as theirs, nothing else
     * @return the unsupported claims, in declaration order; empty when the draft is clean
     */
    public static List<Violation> unsupportedClaims(String body, List<String> evidence, List<String> sellerVoice,
                                                    String asked) {
        return unsupportedClaims(body, evidence, sellerVoice, asked, null);
    }

    /**
     * As {@link #unsupportedClaims(String, List, List, String)}, with the order's own sentence — system facts about
     * this order, which authorize a figure as the evidence does.
     */
    public static List<Violation> unsupportedClaims(String body, List<String> evidence, List<String> sellerVoice,
                                                    String asked, String orderSentence) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String draft = squash(body);
        String grounds = squash(join(evidence));
        String voice = grounds + squash(join(sellerVoice));
        List<Violation> found = new ArrayList<>();
        for (String promise : followUps(draft, voice)) {
            found.add(new Violation(Kind.SELLER_PROMISE, promise));
        }
        // …and a service, operating claim, 「살펴보겠습니다」 or contact path nobody on the seller's side wrote (2026-11-01).
        List<String> authorizing = new ArrayList<>(evidence == null ? List.of() : evidence);
        if (sellerVoice != null) {
            authorizing.addAll(sellerVoice);
        }
        for (String saying : unbackedSayings(body, authorizing)) {
            found.add(new Violation(Kind.SELLER_PROMISE, saying));
        }
        Set<String> authorizedMaterial = new LinkedHashSet<>();
        Matcher allowed = CUSTOMER_ACTION_AUTHORIZED.matcher(voice);
        while (allowed.find()) {
            authorizedMaterial.add(allowed.group(1));
        }
        Matcher action = CUSTOMER_ACTION.matcher(draft);
        while (action.find()) {
            if (!authorizedMaterial.contains(action.group(1))) {
                found.add(new Violation(Kind.CUSTOMER_ACTION, action.group()));
                break;
            }
        }
        for (String remedy : ReviewClaimGuard.unsupportedClaims(body, evidence)) {
            found.add(new Violation(Kind.REMEDY, remedy));
        }
        // A remedy the evidence names but answers the other way — 「불가」 written up as 「가능」 (2026-10-27 review).
        for (String remedy : ReviewClaimGuard.contradictedPromises(body, evidence)) {
            found.add(new Violation(Kind.REMEDY, remedy));
        }
        // …or one it answers both ways, with nothing in the reply saying which case this is.
        for (String remedy : ReviewClaimGuard.undecidedPromises(body, evidence)) {
            found.add(new Violation(Kind.REMEDY, remedy));
        }
        // …or the policy applied where the law does not let it apply: a return held to 미개봉, a fault to 단순 변심 (2026-11-01).
        for (String conflict : ReviewClaimGuard.lawConflicts(body, evidence, asked)) {
            found.add(new Violation(Kind.LAW, conflict));
        }
        // …or one it grants only for a state (미개봉) the reply leaves out and the customer did not claim (2026-10-31).
        for (String state : ReviewClaimGuard.droppedConditions(body, evidence, asked)) {
            found.add(new Violation(Kind.CONDITION, state));
        }
        // Only the certain part is decided here; whether a figure is about the same thing is the model's (below).
        for (FigureClaim claim : figureClaims(body, evidence, asked, orderSentence)) {
            if (claim.unsupported()) {
                found.add(new Violation(Kind.FIGURE, claim.value()));
            }
        }
        return List.copyOf(found);
    }

    /**
     * One number the reply states, and what the text alone can say about it. {@code unsupported} is certain: the value is
     * written nowhere in the evidence, the order's sentence or the question, or only in another unit (「10mm」 for
     * 「10cm」). Every other figure is <b>to verify</b> — whether it is about the same thing, under the same condition,
     * is a reading of meaning, and the owner's rule is that a model reads meaning, not a word list (2026-10-28: 「주변
     * 낱말 비교로는 맞는 초안이 막히거나 틀린 초안이 통과한다」). {@code questionOnly}: the value is the customer's, not
     * the seller's, and may only be said back as theirs; {@code inQuestion}: the customer wrote it (saying it back is
     * harmless whoever else wrote it).
     */
    public record FigureClaim(int index, String value, String unit, String sentence, boolean questionOnly,
                              boolean inQuestion, boolean unsupported) {
    }

    /** The reply's figures the model must verify — every figure the text cannot settle. Empty when none. */
    public static List<FigureClaim> figuresToVerify(String body, List<String> evidence, String asked,
                                                    String orderSentence) {
        return figureClaims(body, evidence, asked, orderSentence).stream().filter(c -> !c.unsupported()).toList();
    }

    static List<FigureClaim> figureClaims(String body, List<String> evidence, String asked, String orderSentence) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        // Numbers are read from the text as written: squashing 「10 20」 would read one number that nobody wrote.
        List<Figure> sources = figures(ANY_NUMBER, join(evidence) + "\n" + (orderSentence == null ? "" : orderSentence));
        List<Figure> question = figures(ANY_NUMBER, asked == null ? "" : asked);
        List<FigureClaim> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Figure f : figures(NUMBER, body)) {
            if (!seen.add(f.value() + "|" + f.unit() + "|" + f.clause())) {
                continue;
            }
            boolean seller = sources.stream().anyMatch(g -> sameQuantity(g, f));
            boolean customer = question.stream().anyMatch(q -> sameQuantity(q, f));
            out.add(new FigureClaim(out.size() + 1, f.value(), f.unit(), f.clause().strip(), customer && !seller,
                    customer, !seller && !customer));
        }
        return List.copyOf(out);
    }

    private static final Pattern ANY_NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    /** The units a figure is written in, normalized so 「10 센티」 and 「10cm」 are the same and 「10mm」 is not. */
    private static final Pattern UNIT = Pattern.compile("^\\s*\\(?\\s*(mm|cm|ml|oz|온스|kg|g|m|L|리터|미터|센티|일|시간|분|"
            + "개월|개|원|%|호|가닥|장|매|박스|세트|주|년|회|번째|차)", Pattern.CASE_INSENSITIVE);

    /** The joiners of a range or a dimension list: 「1~2일」, 「10 x 20 mm」 — the unit is the list's last. */
    private static final Pattern JOINER = Pattern.compile("^\\s*(?:~|-|x|×|\\*|/|,)\\s*(?=\\d)");

    /** One number as written: its value, its unit (null = none written) and its clause. */
    record Figure(String value, String unit, String clause) {
    }

    /**
     * Exactly convertible units (second outside review, 2026-10-28: the check refused 「1cm」 over 「10mm」 before the
     * model, whose prompt allows an exact conversion). Arithmetic is code's: mm · cm · m, ml · l, g · kg. Anything else
     * converts to nothing and must match as written.
     */
    private static final java.util.Map<String, java.util.Map.Entry<String, java.math.BigDecimal>> SCALE = java.util.Map.of(
            "mm", java.util.Map.entry("length", java.math.BigDecimal.ONE),
            "cm", java.util.Map.entry("length", java.math.BigDecimal.TEN),
            "m", java.util.Map.entry("length", java.math.BigDecimal.valueOf(1000)),
            "ml", java.util.Map.entry("volume", java.math.BigDecimal.ONE),
            "l", java.util.Map.entry("volume", java.math.BigDecimal.valueOf(1000)),
            "g", java.util.Map.entry("mass", java.math.BigDecimal.ONE),
            "kg", java.util.Map.entry("mass", java.math.BigDecimal.valueOf(1000)));

    /** The same amount: the same value with no unit on the source side or the same unit, or an exact conversion. */
    static boolean sameQuantity(Figure source, Figure reply) {
        if (source.value().equals(reply.value()) && (source.unit() == null || source.unit().equals(reply.unit()))) {
            return true;
        }
        var a = source.unit() == null ? null : SCALE.get(source.unit());
        var b = reply.unit() == null ? null : SCALE.get(reply.unit());
        if (a == null || b == null || !a.getKey().equals(b.getKey())) {
            return false;
        }
        try {
            return new java.math.BigDecimal(source.value()).multiply(a.getValue())
                    .compareTo(new java.math.BigDecimal(reply.value()).multiply(b.getValue())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The numbers {@code body} states, as {@link Kind#FIGURE} reads them — for {@link InquiryFigureGrounds}. */
    static List<Figure> statedFigures(String body) {
        return body == null ? List.of() : figures(NUMBER, body);
    }

    /** Every number written in {@code text}, each with its clause — the side a figure is looked up on. */
    static List<Figure> writtenFigures(String text) {
        return text == null ? List.of() : figures(ANY_NUMBER, text);
    }

    private static List<Figure> figures(Pattern pattern, String text) {
        List<Figure> out = new ArrayList<>();
        for (String clause : text.split("(?<!\\d)\\.|\\.(?!\\d)|[!?\\n;]")) {
            Matcher m = pattern.matcher(clause);
            while (m.find()) {
                out.add(new Figure(m.group().replace(",", ""), unitAfter(clause, m.end()), clause));
            }
        }
        return out;
    }

    private static String unitAfter(String clause, int from) {
        String rest = clause.substring(from);
        Matcher joiner = JOINER.matcher(rest);
        if (joiner.find()) {
            Matcher next = ANY_NUMBER.matcher(rest);
            next.region(joiner.end(), rest.length());
            if (next.lookingAt()) {
                return unitAfter(rest, next.end());
            }
        }
        Matcher unit = UNIT.matcher(rest);
        return unit.find() ? normalUnit(unit.group(1)) : null;
    }

    private static String normalUnit(String unit) {
        return switch (unit.toLowerCase(java.util.Locale.ROOT)) {
            case "센티" -> "cm";
            case "미터" -> "m";
            case "리터", "l" -> "l";
            case "온스" -> "oz";
            default -> unit.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String join(List<String> texts) {
        return texts == null ? "" : String.join(" ", texts.stream().map(t -> t == null ? "" : t).toList());
    }

    private static String squash(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
