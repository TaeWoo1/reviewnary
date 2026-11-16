package com.sellerops.talktalk;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>Which customer messages the delegated guidance may answer</b> — deterministic, closed, no model (lane contract
 * §6-3).
 *
 * <p>Two layers. First the named exclusions (order, arrival, stock, cancellation, change, defect, price), so the
 * reason a person must answer can be shown. Then a <b>closed vocabulary</b>: every word of the message must be a
 * word about dispatch cut-off, dispatch days or closed days, a day or time, a greeting, or a grammatical ending. One
 * word outside it — 「설치도 해주시나요」, 「세탁은 가능한가요」 — makes the message a person's, whatever else it asks,
 * because an automatic answer to half a message silently drops the other half. What the lists miss goes to a person,
 * never to the bot.
 */
public final class TalkTalkShippingClassifier {

    public static final int MAX_LENGTH = 120;

    public enum Topic { GUIDANCE, HUMAN }

    public record Result(Topic topic, String reason) {
        public boolean answerable() {
            return topic == Topic.GUIDANCE;
        }
    }

    private static final Pattern ORDER_SPECIFIC = Pattern.compile(
            "주문\\s*번호|송장|운송장|택배사|제\\s*(주문|택배|물건|상품|거)|저의?\\s*주문|내\\s*주문|"
                    + "주문(한|했|하신|해\\s*놓|했던)|결제(했|한|완료|됐|되었)|구매(했|한|하신)|입금(했|한)|"
                    + "(발송|출고|배송)\\s*(됐|되었|했|하셨|해\\s*주셨|완료|시작)|보내(셨|주셨|졌)|"
                    + "아직\\s*(안|못)|안\\s*(왔|와요|오네|옵니다)|받(았|지\\s*못|지못)");
    private static final Pattern ARRIVAL = Pattern.compile(
            "도착|받을\\s*수|받아\\s*볼|받나요|받을까|언제\\s*(와|오|받)|며칠\\s*(걸|만에|이면|후)|배송\\s*기간|소요|걸리");
    private static final Pattern STOCK = Pattern.compile("재고|품절|재입고|입고|수량|남아\\s*있|있는\\s*거");
    private static final Pattern CANCEL = Pattern.compile("취소|환불|반품|교환|반송|회수");
    private static final Pattern CHANGE = Pattern.compile("주소|변경|바꿔|바꾸|옵션|합배송|묶음|같이\\s*보내");
    private static final Pattern DEFECT = Pattern.compile("불량|파손|깨져|깨졌|누락|오배송|잘못\\s*(왔|온|배송)|하자|고장");
    private static final Pattern PRICE = Pattern.compile("가격|쿠폰|할인|적립|포인트|무통장|입금|결제\\s*(방법|수단)|배송비|택배비");

    /**
     * A target day the reply does not answer (review item 1, temporal boundary): an explicit date or year, a relative
     * period beyond this week, or a named holiday. The rendered guidance speaks of now, the next dispatch day and the
     * closed days of the coming two weeks inside the confirmed calendar — never of a day the customer names.
     * 「금요일에 쉬시나요?」 stays answerable: the dispatch days and the coming closures answer it.
     */
    private static final Pattern SPECIFIC_DATE = Pattern.compile(
            "\\d{1,4}\\s*년|\\d{1,2}\\s*월|\\d{1,2}\\s*/\\s*\\d{1,2}|\\d{1,2}\\s*일|다음\\s*(주|달|번)|다다음|"
                    + "\\d+\\s*(주|달|개월)\\s*(뒤|후)|모레|글피|월말|연말|연초|명절|연휴|공휴일|휴가|설날|설\\s*연휴|추석|"
                    + "크리스마스|성탄|어린이날|광복절|개천절|한글날|현충일|삼일절|대체\\s*휴일");

    /** At least one word must be about the guidance itself. */
    private static final Pattern GUIDANCE_SIGNAL = Pattern.compile(
            "휴무|휴일|쉬는|쉬시|쉬나|쉬어|쉽니|영업|마감|당일|출고|발송|보내|나가");

    /** Word stems the closed vocabulary admits. */
    private static final List<String> STEMS = List.of(
            // the guidance
            "휴무", "휴일", "쉬는날", "쉬는", "쉬시", "쉬", "쉽니", "영업일", "영업", "정상영업", "정상", "마감", "당일", "출고",
            "발송", "배송", "택배", "보내", "나가", "주문", "결제", "구매",
            // days and times
            "오늘", "내일", "모레", "지금", "이번주", "다음주", "이번", "다음", "주말", "평일", "주중", "월요일", "화요일",
            "수요일", "목요일", "금요일", "토요일", "일요일", "공휴일", "연휴", "명절", "요일", "몇시", "몇", "시간", "시각",
            "시", "분", "오전", "오후", "낮", "점심", "아침", "저녁", "전", "이전", "이후", "후", "까지", "부터", "날", "날짜",
            "일정", "기준", "언제", "며칠날", "하루", "주",
            // greetings and frames
            "안녕하세요", "안녕하십니까", "감사합니다", "감사해요", "고맙습니다", "수고하세요", "문의", "질문", "궁금",
            "혹시", "그럼", "그러면", "네", "저기요", "여쭤", "여쭙", "알려", "확인",
            // verbs that carry no other request
            "하", "되", "돼", "될", "있", "가능", "인가", "이에", "예요", "이요");

    /** Endings and particles a stem may carry. */
    private static final Set<String> ENDINGS = Set.of(
            "", "요", "은", "는", "이", "가", "을", "를", "에", "에는", "에도", "도", "만", "까지", "부터", "이면", "면",
            "하면", "되면", "인가요", "인가", "인지", "인데", "이죠", "죠", "이에요", "예요", "이요", "나요", "까요", "가요",
            "해요", "하나요", "하시나요", "하세요", "합니다", "하니", "시나요", "세요", "십니까", "습니까", "니까", "니다",
            "되나요", "돼요", "되요", "될까요", "되는지", "되는", "됩니까", "주시나요", "주세요", "주실", "주시는", "주나요",
            "주시면", "해주시나요", "해주세요", "드려요", "드립니다", "드려", "드릴게요", "고", "고요", "어요", "아요",
            "는지", "ㄴ가요", "한가요", "할까요", "해", "있나요", "있어요", "있습니까", "가능한가요", "가능해요", "가능하나요",
            "인가요요", "에요", "이요요", "였나", "실", "시는", "시면", "셔요", "실까요", "인지요", "일", "일까요");

    private static final Pattern NUMBERISH = Pattern.compile("\\d{1,4}(시|일|월|분|년|주)?(\\d{1,2}(시|일|분))?");
    private static final Pattern SPLIT = Pattern.compile("[\\s,.?!？。~·()\\[\\]/:\\-]+");

    private TalkTalkShippingClassifier() {
    }

    public static Result classify(String text, String inputType) {
        if (text == null || text.isBlank()) {
            return human(TalkTalkEventParser.nonText(inputType) ? "NON_TEXT" : "EMPTY");
        }
        if (!"typing".equals(inputType)) {
            return human("NOT_TYPED");
        }
        String t = text.strip();
        if (t.length() > MAX_LENGTH) {
            return human("TOO_LONG");
        }
        if (ORDER_SPECIFIC.matcher(t).find()) {
            return human("ORDER_SPECIFIC");
        }
        if (ARRIVAL.matcher(t).find()) {
            return human("ARRIVAL");
        }
        if (STOCK.matcher(t).find()) {
            return human("STOCK");
        }
        if (CANCEL.matcher(t).find()) {
            return human("CANCEL_REFUND_EXCHANGE");
        }
        if (CHANGE.matcher(t).find()) {
            return human("CHANGE_REQUEST");
        }
        if (DEFECT.matcher(t).find()) {
            return human("DEFECT");
        }
        if (PRICE.matcher(t).find()) {
            return human("PRICE");
        }
        if (SPECIFIC_DATE.matcher(t).find()) {
            return human("SPECIFIC_DATE");
        }
        if (!GUIDANCE_SIGNAL.matcher(t).find()) {
            return human("NOT_SHIPPING_GUIDANCE");
        }
        for (String word : SPLIT.split(t)) {
            if (!word.isEmpty() && !admitted(word, 0)) {
                return human("UNHANDLED_REQUEST");
            }
        }
        return new Result(Topic.GUIDANCE, "SHIPPING_GUIDANCE");
    }

    /** A word is admitted when it is a number/date, or a stem followed by an ending or another admitted word. */
    static boolean admitted(String word, int depth) {
        if (ENDINGS.contains(word) && depth > 0) {
            return true;
        }
        if (NUMBERISH.matcher(word).matches()) {
            return true;
        }
        if (depth > 3) {
            return false;
        }
        for (String stem : STEMS) {
            if (word.startsWith(stem)) {
                String rest = word.substring(stem.length());
                if (ENDINGS.contains(rest) || admitted(rest, depth + 1)) {
                    return true;
                }
            }
        }
        // A leading number glued to a word: 「12시전에」, 「21일은」.
        java.util.regex.Matcher m = Pattern.compile("^(\\d{1,4}(시|일|월|분)?)(.+)$").matcher(word);
        return m.matches() && admitted(m.group(3), depth + 1);
    }

    private static Result human(String reason) {
        return new Result(Topic.HUMAN, reason);
    }
}
