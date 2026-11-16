package com.sellerops.knowledge.claim;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Reads the values a text states about the closed list of attributes</b> — rules, not a model (2026-11-15,
 * {@code DESIGN_conflict.md} §4 「1단계는 추론하지 않는다」).
 *
 * <p>The text is cut into sentences and each sentence into clauses. A figure is read with its unit (원 · 일 · 영업일 ·
 * 시) and is about an attribute only when the clause — or, for the subject word, its sentence — names that attribute:
 * 「수령 후 7일 이내에 교환·반품」 is a return window and an exchange window; 「출고 후 1~2일 안에 도착」 is a delivery time;
 * a figure the list does not name is not read at all, so it can be neither a conflict nor an agreement.
 *
 * <p>The same reader reads the seller's material and a reply. A reply that says 「교환은 14일 안에 가능합니다」 states the
 * exchange window as 14일, exactly as a document would.
 */
public final class ClaimReader {

    private ClaimReader() {
    }

    /** Sentence ends: a full stop followed by space, a line break, a bullet. */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?。])\\s+|\\n+|^\\s*[-•·]\\s+", Pattern.MULTILINE);
    /** Clause ends inside a sentence: a comma that is not a thousands separator. */
    private static final Pattern CLAUSE = Pattern.compile(",(?!\\d{3})\\s*");

    private static final Pattern DAY_RANGE = Pattern.compile("(\\d{1,3})\\s*[~∼〜\\-]\\s*(\\d{1,3})\\s*일");
    private static final Pattern DAY = Pattern.compile("(?<![\\d~∼〜\\-])(\\d{1,3})\\s*일");
    private static final Pattern WON = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(만|천)?\\s*원");
    private static final Pattern HOUR = Pattern.compile("(오전|오후)?\\s*(\\d{1,2})\\s*시(?!간)");

    private static final Pattern RETURN = Pattern.compile("반품");
    private static final Pattern EXCHANGE = Pattern.compile("교환");
    private static final Pattern WINDOW_WORD = Pattern.compile("신청|이내|안에|까지|기한|기간|수령|받은|받고|받으신|받으시고|받은 날");
    private static final Pattern FEE_WORD = Pattern.compile("배송비|택배비|운임|배송 ?비용");
    private static final Pattern FREE = Pattern.compile("무료");
    private static final Pattern AT_LEAST = Pattern.compile("이상|넘|부터");
    private static final Pattern MAKE = Pattern.compile("제작|생산");
    private static final Pattern MAKE_TIME = Pattern.compile("걸|기간|소요|영업일");
    private static final Pattern ARRIVE = Pattern.compile("도착|배송 ?기간|받아 보|받으실|받아보");
    private static final Pattern PAY = Pattern.compile("결제|주문");
    private static final Pattern CUTOFF = Pattern.compile("이전|전에|전 |까지|이후|넘");
    private static final Pattern SHIP = Pattern.compile("출고|발송");
    private static final Pattern ZERO_FEE = Pattern.compile("배송비 ?없이|배송비(는|가)? ?무료|무료로 ?(교환|반품|환불|회수)");
    private static final Pattern REFUNDISH = Pattern.compile("반품|환불|교환");

    private static final Pattern CHANGE_OF_MIND = Pattern.compile("단순 ?변심|변심|마음이 바뀌");
    private static final Pattern DEFECT = Pattern.compile("불량|오배송|파손|하자");
    private static final Pattern REMOTE = Pattern.compile("제주|도서|산간");
    private static final Pattern PAST = Pattern.compile("그 ?전에는|이전에는|종전에는|예전에는|였습니다|였어요|이었습니다|이었어요|였던|었던 ");
    /** A number followed by 일 that is a calendar date (「11월 25일」), not a number of days. */
    private static final Pattern MONTH_BEFORE = Pattern.compile("월\\s*$");

    /** Every value {@code text} states about an attribute of the closed list, in reading order. */
    public static List<KnowledgeClaim> read(String text) {
        return read(text, null);
    }

    /**
     * The same, with the document's title as the subject a sentence leaves unsaid: under 「배송 문의 답변」, 「보통 5일
     * 걸립니다」 is a delivery time; under 「반품 배송비 안내」, 「편도 3,000원입니다」 a return fee. The title never supplies
     * a value and never a condition — only what the value is about, when the sentence names nothing.
     */
    public static List<KnowledgeClaim> read(String text, String title) {
        return read(text, title, false);
    }

    /**
     * A reply, read as a customer reads it: a sentence that names no 반품 · 교환 goes on about the one before it when that
     * one did — 「단순 변심 반품은 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 고객님이 부담합니다」 states the 단순 변심 return
     * fee, not the order's shipping fee (2026-11-15 review, q06). Only what the earlier sentence was about and under which
     * condition is carried, never a value; a sentence about an order (주문 · 결제 · 출고 · 구매) starts afresh.
     */
    public static List<KnowledgeClaim> readReply(String text) {
        return read(text, null, true);
    }

    /** 「구매자」 · 「주문자」 name who pays, not an order (r4 review, 2026-11-15). */
    private static final Pattern OUTBOUND = Pattern.compile("주문(?!\\s*자)|결제|출고|구매(?!\\s*자)");

    private static List<KnowledgeClaim> read(String text, String title, boolean reply) {
        List<KnowledgeClaim> out = new ArrayList<>();
        String hint = title == null ? "" : title;
        if (text == null || text.isBlank()) {
            return out;
        }
        String carried = "";
        for (String sentence : SENTENCE.split(text)) {
            String s = sentence.strip();
            if (s.isEmpty()) {
                continue;
            }
            boolean pastSentence = PAST.matcher(s).find();
            boolean namesRemedy = RETURN.matcher(s).find() || EXCHANGE.matcher(s).find();
            String context = s;
            if (reply && !namesRemedy && !carried.isEmpty() && !OUTBOUND.matcher(s).find()) {
                context = s + " " + carried;
            }
            for (String clause : CLAUSE.split(s)) {
                String c = clause.strip();
                if (!c.isEmpty()) {
                    readClause(c, context, hint, pastSentence, out);
                }
            }
            if (reply && namesRemedy) {
                carried = subjectOf(s);
            } else if (reply && OUTBOUND.matcher(s).find()) {
                carried = "";
            }
        }
        return out;
    }

    /** What a sentence is about, as words a following sentence may carry: 「단순 변심 반품」. */
    private static String subjectOf(String s) {
        StringBuilder sb = new StringBuilder();
        for (Pattern p : new Pattern[] {CHANGE_OF_MIND, DEFECT, REMOTE, RETURN, EXCHANGE}) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                sb.append(m.group()).append(' ');
            }
        }
        return sb.toString().strip();
    }

    private static void readClause(String c, String s, String hint, boolean pastSentence, List<KnowledgeClaim> out) {
        boolean historical = pastSentence || PAST.matcher(c).find();
        ClaimCondition condition = conditionOf(c, s);
        // Days: ranges first, then single values outside any range.
        List<long[]> days = new ArrayList<>();
        Matcher range = DAY_RANGE.matcher(c);
        List<int[]> taken = new ArrayList<>();
        while (range.find()) {
            days.add(new long[] {Long.parseLong(range.group(1)), Long.parseLong(range.group(2))});
            taken.add(new int[] {range.start(), range.end()});
        }
        Matcher single = DAY.matcher(c);
        while (single.find()) {
            int at = single.start();
            if (taken.stream().anyMatch(t -> at >= t[0] && at < t[1])) {
                continue;
            }
            if (MONTH_BEFORE.matcher(c.substring(0, at)).find()) {
                continue; // 「11월 25일」 is a date
            }
            long v = Long.parseLong(single.group(1));
            days.add(new long[] {v, v});
        }
        if (!days.isEmpty()) {
            ClaimAttribute.Unit unit = c.contains("영업일") ? ClaimAttribute.Unit.BUSINESS_DAY : ClaimAttribute.Unit.DAY;
            for (ClaimAttribute a : dayAttributes(c, s, hint)) {
                for (long[] d : days) {
                    out.add(new KnowledgeClaim(a, condition, null, d[0], d[1], unit, historical, c));
                }
            }
        }
        // Won.
        Matcher won = WON.matcher(c);
        boolean anyWon = false;
        while (won.find()) {
            anyWon = true;
            long v = amount(won.group(1), won.group(2));
            for (ClaimAttribute a : wonAttributes(c, s, hint)) {
                KnowledgeClaim.Leg leg = a == ClaimAttribute.RETURN_SHIPPING_FEE ? legOf(c, s) : null;
                KnowledgeClaim.Payer payer = a == ClaimAttribute.FREE_SHIPPING_THRESHOLD ? null : payerOf(c, s);
                out.add(new KnowledgeClaim(a, condition, leg, v, v, ClaimAttribute.Unit.WON, historical, c, payer));
            }
        }
        if (!anyWon && ZERO_FEE.matcher(c).find() && REFUNDISH.matcher(s).find()) {
            // 「불량·오배송은 배송비 없이 교환 또는 환불」: a return shipping fee of nothing, under its condition.
            out.add(new KnowledgeClaim(ClaimAttribute.RETURN_SHIPPING_FEE, condition, null, 0, 0,
                    ClaimAttribute.Unit.WON, historical, c));
        }
        // Clock hours: an order cut-off.
        Matcher hour = HOUR.matcher(c);
        while (hour.find()) {
            if (!(PAY.matcher(s).find() && CUTOFF.matcher(c).find() && SHIP.matcher(s).find())) {
                break;
            }
            long h = Long.parseLong(hour.group(2));
            if ("오후".equals(hour.group(1)) && h < 12) {
                h += 12;
            }
            if (h > 24) {
                continue;
            }
            out.add(new KnowledgeClaim(ClaimAttribute.DISPATCH_CUTOFF, ClaimCondition.GENERAL, null, h, h,
                    ClaimAttribute.Unit.HOUR, historical, c));
        }
    }

    private static final Pattern TAKES = Pattern.compile("걸|소요");
    private static final Pattern SHIPPING_SUBJECT = Pattern.compile("배송|도착|택배");

    private static Set<ClaimAttribute> dayAttributes(String c, String s, String hint) {
        Set<ClaimAttribute> out = EnumSet.noneOf(ClaimAttribute.class);
        if (MAKE.matcher(c).find() && MAKE_TIME.matcher(c).find()) {
            out.add(ClaimAttribute.PRODUCTION_DAYS);
            return out;
        }
        if (ARRIVE.matcher(c).find()) {
            out.add(ClaimAttribute.DELIVERY_DAYS);
            return out;
        }
        boolean namesSubject = MAKE.matcher(s).find() || RETURN.matcher(s).find() || EXCHANGE.matcher(s).find()
                || SHIPPING_SUBJECT.matcher(s).find();
        if (!namesSubject && TAKES.matcher(c).find()) {
            // The sentence names no subject; the title may (「배송 문의 답변」: 「보통 5일 걸립니다」).
            if (MAKE.matcher(hint).find()) {
                out.add(ClaimAttribute.PRODUCTION_DAYS);
            } else if (SHIPPING_SUBJECT.matcher(hint).find() && !RETURN.matcher(hint).find()
                    && !EXCHANGE.matcher(hint).find()) {
                out.add(ClaimAttribute.DELIVERY_DAYS);
            }
            return out;
        }
        if (!namesSubject && WINDOW_WORD.matcher(c).find()) {
            s = s + " " + hint; // 「반품 안내」: 「수령 후 7일 이내에 신청해 주세요」
        }
        boolean ret = RETURN.matcher(c).find() || !EXCHANGE.matcher(c).find() && RETURN.matcher(s).find();
        boolean exc = EXCHANGE.matcher(c).find() || !RETURN.matcher(c).find() && EXCHANGE.matcher(s).find();
        if ((ret || exc) && WINDOW_WORD.matcher(c).find()) {
            if (ret) {
                out.add(ClaimAttribute.RETURN_WINDOW);
            }
            if (exc) {
                out.add(ClaimAttribute.EXCHANGE_WINDOW);
            }
        }
        return out;
    }

    private static Set<ClaimAttribute> wonAttributes(String c, String s, String hint) {
        Set<ClaimAttribute> out = EnumSet.noneOf(ClaimAttribute.class);
        if (FREE.matcher(c).find() && AT_LEAST.matcher(c).find()) {
            out.add(ClaimAttribute.FREE_SHIPPING_THRESHOLD);
            return out;
        }
        if (!FEE_WORD.matcher(s).find() && !RETURN.matcher(s).find() && !EXCHANGE.matcher(s).find()) {
            s = s + " " + hint; // 「반품 배송비 안내」: 「편도 3,000원입니다」
        }
        boolean fee = FEE_WORD.matcher(c).find() || FEE_WORD.matcher(s).find();
        if (!fee) {
            return out;
        }
        if (RETURN.matcher(c).find() || RETURN.matcher(s).find()) {
            out.add(ClaimAttribute.RETURN_SHIPPING_FEE);
        } else if (!EXCHANGE.matcher(c).find() && !EXCHANGE.matcher(s).find()) {
            out.add(ClaimAttribute.SHIPPING_FEE);
        }
        return out;
    }

    private static final Pattern CUSTOMER_PAYS = Pattern.compile(
            "고객님?(?:이|께서|의|이 직접)?\\s*(?:직접\\s*)?부담|구매자\\s*부담|차감");
    private static final Pattern SELLER_PAYS = Pattern.compile(
            "판매자(?:가)?\\s*부담|저희(?:가)?\\s*부담|당사(?:가)?\\s*부담|무상");

    /** Who the clause — or else its sentence — says pays; null when neither says, or both ways are said. */
    private static KnowledgeClaim.Payer payerOf(String c, String s) {
        for (String where : new String[] {c, s}) {
            boolean customer = CUSTOMER_PAYS.matcher(where).find();
            boolean seller = SELLER_PAYS.matcher(where).find();
            if (customer != seller) {
                return customer ? KnowledgeClaim.Payer.CUSTOMER : KnowledgeClaim.Payer.SELLER;
            }
            if (customer) {
                return null;
            }
        }
        return null;
    }

    private static KnowledgeClaim.Leg legOf(String c, String s) {
        String where = c.contains("왕복") || c.contains("편도") ? c : s;
        if (where.contains("왕복")) {
            return KnowledgeClaim.Leg.ROUND_TRIP;
        }
        return where.contains("편도") ? KnowledgeClaim.Leg.ONE_WAY : null;
    }

    private static ClaimCondition conditionOf(String c, String s) {
        for (String where : new String[] {c, s}) {
            boolean mind = CHANGE_OF_MIND.matcher(where).find();
            boolean defect = DEFECT.matcher(where).find();
            boolean remote = REMOTE.matcher(where).find();
            int said = (mind ? 1 : 0) + (defect ? 1 : 0) + (remote ? 1 : 0);
            if (said == 1) {
                return mind ? ClaimCondition.CHANGE_OF_MIND : defect ? ClaimCondition.DEFECT : ClaimCondition.REMOTE_AREA;
            }
            if (said > 1 && where == c) {
                // Two conditions in one clause cannot be told apart; the sentence will not either.
                return ClaimCondition.GENERAL;
            }
        }
        return ClaimCondition.GENERAL;
    }

    private static long amount(String digits, String scale) {
        double v = Double.parseDouble(digits.replace(",", ""));
        if ("만".equals(scale)) {
            v *= 10_000;
        } else if ("천".equals(scale)) {
            v *= 1_000;
        }
        return Math.round(v);
    }

    // ── what a question asks ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A question about the window: 「며칠 안에」, and the time already gone by — 「받은 지 10일 지났어도 신청할 수 있나요?」
     * (2026-11-15 review B166: the fault rule of B163 must not take a window question asked in elapsed days with it).
     */
    private static final Pattern ASK_DEADLINE = Pattern.compile("며칠|기한|기간|언제까지|이내|안에|까지|날짜|일 ?안"
            + "|지났|지나(?:서|도|면|버렸|버린)|지난 ?(?:뒤|후|다음)|넘었|넘어서|넘어도|늦었"
            + "|(?:받|수령|배송|도착|구매|주문)(?:은|한|았|받은)? ?지 ?(?:\\d+|한|두|세|며칠|일주일|보름|오래)"
            + "|\\d+ ?(?:일|주|주일|개월|달) ?(?:이 ?|가 ?)?(?:지나|넘|됐|되었|째)|일주일 ?(?:이 ?)?(?:지나|넘)");
    private static final Pattern ASK_COST = Pattern.compile("배송비|택배비|비용|얼마|금액|돈|부담");
    private static final Pattern ASK_FREE = Pattern.compile("무료 ?배송|무료");
    private static final Pattern ASK_CUTOFF = Pattern.compile("몇 ?시|마감|당일 ?(출고|발송)|오늘 ?(출고|발송|보내)|\\d{1,2} ?시에? ?결제");
    private static final Pattern ASK_DELIVERY = Pattern.compile("언제 ?(받|와|도착|오)|며칠 ?(걸|만에)|도착|배송 ?기간|얼마나 ?걸");
    /** A question about something missing, broken or sent wrong — a fault, not a change of mind. */
    private static final Pattern ASK_FAULT = Pattern.compile(
            "누락|빠져|빠졌|빠진|안 ?들어 ?있|불량|파손|깨져|깨졌|하자|고장|찢어|오배송|잘못 ?(왔|온|배송|보내|발송)|다른 ?상품이 ?왔");
    private static final Pattern ASK_MAKE = Pattern.compile("제작|생산");
    private static final Pattern ASK_MAKE_WHEN = Pattern.compile("언제|며칠|기간|걸|시작");

    /**
     * The attributes of the closed list a question asks about — what its answer would state. 「교환은 받고 며칠 안에
     * 신청해야 하나요?」 asks the exchange window; 「단순 변심 반품 배송비? 며칠 안에?」 the return fee and the return window.
     * A question about returning at all (「반품되나요?」) asks the window, the one value that decides whether it can.
     */
    public static Set<ClaimAttribute> asked(String question) {
        Set<ClaimAttribute> out = EnumSet.noneOf(ClaimAttribute.class);
        if (question == null || question.isBlank()) {
            return out;
        }
        String q = question;
        boolean ret = RETURN.matcher(q).find();
        boolean exc = EXCHANGE.matcher(q).find();
        boolean deadline = ASK_DEADLINE.matcher(q).find();
        boolean cost = ASK_COST.matcher(q).find();
        // 「Can it be returned at all」 is decided by the window; a remedy asked for something missing, broken or wrong is not
        // (2026-11-15 real eval q12: 「패킹이 빠져 있으니 보내주거나 교환 등 조치를 해주세요」 was held on the exchange-window
        // disagreement). Only an explicit 「며칠 · 기한」 asks the window then.
        boolean implicitWindow = !cost && !ASK_FAULT.matcher(q).find();
        if (ret) {
            if (cost) {
                out.add(ClaimAttribute.RETURN_SHIPPING_FEE);
            }
            if (deadline || implicitWindow) {
                out.add(ClaimAttribute.RETURN_WINDOW);
            }
        }
        if (exc && (deadline || implicitWindow)) {
            out.add(ClaimAttribute.EXCHANGE_WINDOW);
        }
        if (ASK_FREE.matcher(q).find()) {
            out.add(ClaimAttribute.FREE_SHIPPING_THRESHOLD);
        } else if (!ret && !exc && FEE_WORD.matcher(q).find()) {
            out.add(ClaimAttribute.SHIPPING_FEE);
        }
        if (ASK_CUTOFF.matcher(q).find()) {
            out.add(ClaimAttribute.DISPATCH_CUTOFF);
        }
        boolean make = ASK_MAKE.matcher(q).find() && ASK_MAKE_WHEN.matcher(q).find();
        if (make) {
            out.add(ClaimAttribute.PRODUCTION_DAYS);
        }
        if (ASK_DELIVERY.matcher(q).find() && !make) {
            out.add(ClaimAttribute.DELIVERY_DAYS);
        }
        return out;
    }
}
