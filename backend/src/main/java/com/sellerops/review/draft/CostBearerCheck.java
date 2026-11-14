package com.sellerops.review.draft;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>Who pays the shipping of an exchange or a return</b> (owner, 2026-11-10: F5 — 「교환 배송비는 저희가 부담해요」 went out
 * unchecked, and so did its opposite against a policy that said the customer pays). One reading for both lanes, for a
 * model's draft and a seller's own text alike:
 * <ol>
 *   <li>a reply's statement that an applicable source states the same way stands;</li>
 *   <li>one no source speaks to is unbacked — the 「이번 건만 예외」 decision, as any unbacked promise;</li>
 *   <li>one an applicable source states the other way is a conflict — fixed first, never excepted past.</li>
 * </ol>
 * <b>The sources are the seller's material only</b> — policies, product documents, the seller's record of this order.
 * Never the customer's question and never the reply itself: neither is passed in.
 *
 * <p><b>The case and the cost are compared together.</b> A statement is read as the cost's kind (교환 · 반품 배송비), the
 * case it is for (파손 · 불량 · 오배송, or 단순 변심; or none stated) and who bears it. A source applies when its kind is
 * the reply's and its case is the reply's — a source's statement for no particular case applies to every case unless the
 * same source states the reply's case on its own. A reply that names no case is decided only by a source that applies
 * whatever the case: one stated for no case, or one that states every case the same way (B134). What cannot be told is not forced either way: a source that speaks only
 * of another case ({@link Verdict#OTHER_CASE}), or a reply that does not say which case while the source sets them apart,
 * or a source whose statement names no kind ({@link Verdict#UNCLEAR_CASE}) — both are unbacked, never a match and never
 * a conflict.
 *
 * <p>A clause whose bearer cannot be read (both or neither named) states nothing here; the cost word's own checks still
 * read it. Pure: no I/O, no model, no state.
 */
public final class CostBearerCheck {

    public enum Bearer { SELLER, CUSTOMER }

    public enum Cause { DEFECT, CHANGE_OF_MIND }

    public enum Kind { EXCHANGE, RETURN }

    public enum Verdict { BACKED, UNBACKED, OTHER_CASE, UNCLEAR_CASE, CONFLICT }

    /** One clause that says who bears a cost: its kinds (empty — not named), its case (null — none stated), its bearer. */
    public record Statement(String clause, Set<Kind> kinds, Cause cause, Bearer bearer) {

        /** How the seller is shown it: 「파손·불량 시 교환 배송비 판매자 부담」. */
        public String label() {
            String kind = kinds.containsAll(EnumSet.allOf(Kind.class)) ? "교환·반품 배송비"
                    : kinds.contains(Kind.EXCHANGE) ? "교환 배송비" : kinds.contains(Kind.RETURN) ? "반품 배송비" : "배송비";
            String when = cause == Cause.DEFECT ? "파손·불량 시 " : cause == Cause.CHANGE_OF_MIND ? "단순 변심 시 " : "";
            return when + kind + " " + (bearer == Bearer.SELLER ? "판매자 부담" : "고객 부담");
        }
    }

    /** A document a statement may be read against: {@code title} names a policy or product document, "" a cited passage. */
    public record Source(String title, String text) {
    }

    /** What one statement of the reply came to. {@code title}: the source that says otherwise, for a conflict. */
    public record Finding(Statement said, Verdict verdict, String title, boolean clash) {

        /** The promise word a hold names — the label, and for an unclear case why it is not decided. */
        public String word() {
            return switch (verdict) {
                case OTHER_CASE -> said.label() + " (등록된 정책은 다른 경우만 정해요)";
                case UNCLEAR_CASE -> said.label() + " (어느 경우에 적용되는지 분명하지 않아요)";
                default -> said.label();
            };
        }
    }

    /** The cost words a bearer clause is about; the cost word's own term check leaves such a clause to this reading. */
    static final List<String> COST_TERMS = List.of("배송비", "택배비", "반품비", "교환비", "반송비", "왕복", "편도", "무료", "무상");

    private static final Pattern COST = Pattern.compile("배송비|택배비|반품비|교환비|반송비|왕복|편도|(?:교환|반품|반송)\\S{0,6}비용");
    private static final Pattern SELLER = Pattern.compile("판매자|판매처|저희|당사|회사|쇼핑몰|업체|본사|스토어");
    private static final Pattern CUSTOMER = Pattern.compile("고객|구매자|소비자|주문자");
    private static final Pattern NOT_BORNE = Pattern.compile(
            "부담하지않|부담하실필요없|부담없|부담은없|내지않|내실필요없|받지않|청구하지않|청구되지않|비용없이|무료|무상");
    private static final Pattern BORNE = Pattern.compile("부담|지불|내셔야|내주셔야|차감|동봉|입금");
    /** Asked of the customer without naming them: 「부담해 주셔야」, 「차감 후 환불」, 「동봉해 주세요」. */
    private static final Pattern ASKED_OF_CUSTOMER = Pattern.compile("부담하셔야|부담해주셔야|부담해주시|부담하실|내셔야|내주셔야|차감|동봉|입금해");
    private static final Pattern DEFECT = Pattern.compile(
            "파손|불량|하자|오배송|잘못배송|잘못보내|잘못발송|누락|손상|찢어|찢겨|깨져|깨진|결함|판매자사유|판매자귀책|판매자과실|상품문제|제품문제");
    private static final Pattern CHANGE_OF_MIND = Pattern.compile("단순변심|변심|고객사유|고객귀책|마음이바뀌|마음이변");

    private CostBearerCheck() {
    }

    /** The bearer statements of {@code text}, clause by clause, each with the kinds of its line or the line it falls under. */
    public static List<Statement> statements(String text) {
        List<Statement> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Set<Kind> heading = EnumSet.noneOf(Kind.class);
        for (String sentence : text.split("(?<=[.!?。])\\s+|\\n+")) {
            Set<Kind> sentenceKinds = kindsOf(squash(sentence));
            if (sentenceKinds.isEmpty()) {
                sentenceKinds = heading;
            } else {
                heading = sentenceKinds;
            }
            Cause sentenceCause = causeOf(squash(sentence));
            // Not inside an amount: 「6,000원 고객 부담」 is one clause.
            for (String clause : sentence.split("(?<!\\d),|,(?!\\d)|;|(?<=이고|이며|지만)\\s")) {
                String s = squash(clause);
                if (!COST.matcher(s).find()) {
                    continue;
                }
                Bearer bearer = bearerOf(s);
                if (bearer == null) {
                    continue;
                }
                Set<Kind> kinds = kindsOf(s);
                Cause cause = causeOf(s);
                out.add(new Statement(clause.strip(), kinds.isEmpty() ? sentenceKinds : kinds,
                        cause != null ? cause : mixed(squash(sentence)) ? null : sentenceCause, bearer));
            }
        }
        return out;
    }

    /**
     * Every bearer statement of {@code reply}, read against {@code backing} (cited passages, the seller's record — untitled)
     * and {@code rules} (every document in use, titled). With no rules, a check of what the cited ground alone says.
     */
    public static List<Finding> judge(String reply, List<String> backing, List<Source> rules) {
        List<Source> sources = new ArrayList<>();
        if (backing != null) {
            backing.stream().filter(t -> t != null && !t.isBlank()).forEach(t -> sources.add(new Source("", t)));
        }
        if (rules != null) {
            sources.addAll(rules);
        }
        List<List<Statement>> read = sources.stream().map(s -> statements(s.text())).toList();
        List<Finding> out = new ArrayList<>();
        for (Statement said : statements(reply)) {
            String against = null;
            boolean same = false;
            boolean unclear = false;
            boolean other = false;
            for (int i = 0; i < sources.size(); i++) {
                Reading r = readOne(said, read.get(i));
                same |= r.same;
                unclear |= r.unclear;
                other |= r.other;
                // A titled document names the conflict over an untitled passage (the same policy, cited).
                if (r.opposite && (against == null || against.isEmpty())) {
                    against = sources.get(i).title();
                }
            }
            if (against != null) {
                out.add(new Finding(said, Verdict.CONFLICT, against, same));
            } else if (same) {
                out.add(new Finding(said, Verdict.BACKED, "", false));
            } else if (unclear) {
                out.add(new Finding(said, Verdict.UNCLEAR_CASE, "", false));
            } else if (other) {
                out.add(new Finding(said, Verdict.OTHER_CASE, "", false));
            } else {
                out.add(new Finding(said, Verdict.UNBACKED, "", false));
            }
        }
        return out;
    }

    /** The words of what {@code findings} did not let stand, in order: what a hold or a drafting check names. */
    public static List<String> notStanding(List<Finding> findings) {
        return findings.stream().filter(f -> f.verdict() != Verdict.BACKED).map(Finding::word).distinct().toList();
    }

    private static final Pattern WORD = Pattern.compile("배송비 (?:판매자|고객) 부담(?: \\(.*\\))?$");

    /** Whether a promise word is one of this check's — for the hold's own sentence about it. */
    public static boolean isWord(String word) {
        return word != null && WORD.matcher(word).find();
    }

    /** The reply's clauses a bearer statement covers — the cost words there are this check's, not the term check's. */
    static boolean covers(String sentence) {
        return !statements(sentence).isEmpty();
    }

    private record Reading(boolean same, boolean opposite, boolean unclear, boolean other) {
    }

    /** One source against one statement: what applies in it, and whether that says the same, the other, or nothing clear. */
    private static Reading readOne(Statement said, List<Statement> source) {
        List<Statement> sameKind = new ArrayList<>();
        boolean unclear = false;
        for (Statement e : source) {
            if (e.kinds().isEmpty() || said.kinds().isEmpty()) {
                unclear = true; // whose cost it is cannot be told from the words
            } else if (e.kinds().stream().anyMatch(said.kinds()::contains)) {
                sameKind.add(e);
            }
        }
        if (sameKind.isEmpty()) {
            return new Reading(false, false, unclear, false);
        }
        List<Statement> general = sameKind.stream().filter(e -> e.cause() == null).toList();
        List<Statement> specific = sameKind.stream().filter(e -> e.cause() != null).toList();
        List<Statement> applies;
        boolean other = false;
        if (said.cause() != null) {
            List<Statement> mine = specific.stream().filter(e -> e.cause() == said.cause()).toList();
            applies = !mine.isEmpty() ? mine : general;
            other = applies.isEmpty() && !specific.isEmpty();
        } else if (!general.isEmpty()) {
            applies = general;
        } else {
            // The reply names no case and the source states only cases (B134, 2026-11-10): a ground for one case is not the
            // ground of a promise for every case. It decides only when it states EVERY case, each the same way — then it
            // applies whatever the case, as a statement for no case does. Anything narrower is unclear, either way.
            Set<Cause> stated = EnumSet.noneOf(Cause.class);
            specific.forEach(e -> stated.add(e.cause()));
            boolean everyCase = stated.containsAll(EnumSet.allOf(Cause.class));
            boolean allSame = specific.stream().allMatch(e -> e.bearer() == said.bearer());
            boolean allOpposite = specific.stream().allMatch(e -> e.bearer() != said.bearer());
            applies = everyCase && (allSame || allOpposite) ? specific : List.of();
            unclear |= applies.isEmpty();
        }
        boolean same = applies.stream().anyMatch(e -> e.bearer() == said.bearer());
        boolean opposite = applies.stream().anyMatch(e -> e.bearer() != said.bearer());
        return new Reading(same, opposite, unclear && !same && !opposite, other);
    }

    /** Words that hold a party's name without making it the one who pays: a case (판매자 사유) or someone else (택배 업체). */
    private static final Pattern NOT_A_PARTY = Pattern.compile(
            "판매자(?:사유|귀책|과실)|고객(?:사유|귀책|변심|센터)|(?:택배|배송|운송)업체");

    static Bearer bearerOf(String squashed) {
        String s = NOT_A_PARTY.matcher(squashed).replaceAll("");
        boolean seller = SELLER.matcher(s).find();
        boolean customer = CUSTOMER.matcher(s).find();
        boolean notBorne = NOT_BORNE.matcher(s).find();
        if (notBorne) {
            if (customer && !seller) {
                return Bearer.SELLER; // 「고객님은 부담하지 않으셔도 돼요」
            }
            if (seller && !customer) {
                return Bearer.CUSTOMER; // 「판매자가 부담하지 않아요」
            }
            return seller || customer ? null : Bearer.SELLER; // 「교환 배송비는 무료예요」
        }
        if (seller && customer) {
            return null;
        }
        if (customer && BORNE.matcher(s).find()) {
            return Bearer.CUSTOMER;
        }
        if (seller && BORNE.matcher(s).find()) {
            return Bearer.SELLER;
        }
        if (!seller && ASKED_OF_CUSTOMER.matcher(s).find()) {
            return Bearer.CUSTOMER;
        }
        return null;
    }

    static Set<Kind> kindsOf(String s) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        if (s.contains("교환")) {
            kinds.add(Kind.EXCHANGE);
        }
        if (s.contains("반품") || s.contains("환불") || s.contains("반송")) {
            kinds.add(Kind.RETURN);
        }
        return kinds;
    }

    static Cause causeOf(String s) {
        boolean defect = DEFECT.matcher(s).find();
        boolean mind = CHANGE_OF_MIND.matcher(s).find();
        return defect == mind ? null : defect ? Cause.DEFECT : Cause.CHANGE_OF_MIND;
    }

    private static boolean mixed(String s) {
        return DEFECT.matcher(s).find() && CHANGE_OF_MIND.matcher(s).find();
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
