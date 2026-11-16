package com.sellerops.knowledge.defaults;

/**
 * The questionnaire for a seller who has no manual (owner decision 2026-11-17): twelve questions a customer
 * actually asks, each answered and confirmed by the seller one by one.
 *
 * <p><b>Two of them are not stored here.</b> {@link #DISPATCH_CUTOFF} and {@link #HOLIDAYS} live in the TalkTalk
 * shipping guidance and nowhere else ({@link #shared()}); this list shows them and writes them into the shipping
 * document, and the seller changes them in one place.
 *
 * <p><b>Legal baseline is a hint, never an answer.</b> {@link #legal()} is shown beside the question as 「법정 기준」. It
 * is not rendered into the document: what the shop does is what the seller confirmed, and an answer that recited
 * the law as the shop's policy would be the product deciding the policy. Text checked against 전자상거래 등에서의
 * 소비자보호에 관한 법률 (MST 282793) on 2026-11-17.
 *
 * <p>Every rendered sentence is written so {@code ClaimReader} reads the same value the seller typed — a 7-day window
 * reads as RETURN_WINDOW 7, a fee as RETURN_SHIPPING_FEE — which is what lets the 자료끼리 다른 값 check compare it with
 * an uploaded manual ({@code DefaultPolicyRenderTest}).
 */
public enum DefaultPolicyItem {

    DISPATCH_CUTOFF(DefaultPolicyGroup.SHIPPING, Kind.SHARED, "출고 마감 시각",
            "몇 시까지 결제한 주문을 그날 보내나요?", null, null),
    HOLIDAYS(DefaultPolicyGroup.SHIPPING, Kind.SHARED, "휴무일",
            "출고하지 않는 날(공휴일 포함)은 언제인가요?", null, null),
    COURIER(DefaultPolicyGroup.SHIPPING, Kind.TEXT, "택배사",
            "어느 택배사로 보내나요?", "예: CJ대한통운", null),
    DELIVERY_DAYS(DefaultPolicyGroup.SHIPPING, Kind.DAYS, "보통 도착까지 걸리는 날",
            "출고하고 보통 며칠 안에 도착하나요?", "예: 2", null),
    REMOTE_AREA(DefaultPolicyGroup.SHIPPING, Kind.TEXT, "제주·도서산간 추가 배송비",
            "제주·도서산간은 배송비가 더 붙나요?", "예: 제주 3,000원, 도서산간 5,000원 추가", null),
    RETURN_WINDOW(DefaultPolicyGroup.RETURNS, Kind.DAYS, "교환·반품 신청 기간",
            "상품을 받고 며칠 안에 교환·반품을 신청할 수 있나요?", "예: 7",
            "상품을 받은 날부터 7일 안에는 청약철회(반품)를 할 수 있어요. 더 길게 정하는 것은 괜찮아요. (전자상거래법 제17조 제1항)"),
    RETURN_FEE(DefaultPolicyGroup.RETURNS, Kind.WON, "단순 변심 반품 배송비 (왕복)",
            "마음이 바뀌어 반품하면 왕복 배송비가 얼마인가요?", "예: 6000",
            "단순 변심 반품의 배송비는 소비자가 내요(제18조 제9항). 상품이 표시·광고나 계약 내용과 달라서 반품하면 판매자가 내요(제18조 제10항)."),
    RETURN_METHOD(DefaultPolicyGroup.RETURNS, Kind.TEXT, "반품 신청 방법",
            "교환·반품은 어떻게 신청하나요?", "예: 구매한 곳의 반품 신청 버튼으로 신청하면 택배 기사님이 찾아가요.", null),
    NON_RETURNABLE(DefaultPolicyGroup.RETURNS, Kind.TEXT, "교환·반품이 안 되는 경우",
            "교환·반품을 받지 않는 경우가 있나요?", "예: 포장을 뜯어 사용한 경우, 주문 제작 상품",
            "법에서 반품을 막을 수 있는 경우는 정해져 있어요: 고객 잘못으로 상품이 없어지거나 훼손된 경우, 사용해서 가치가 크게 줄어든 경우, "
                    + "시간이 지나 다시 팔기 어려운 경우, 복제할 수 있는 상품의 포장을 뜯은 경우 등. 이런 경우도 미리 잘 보이게 알려야 해요. (제17조 제2항)"),
    WARRANTY(DefaultPolicyGroup.SERVICE, Kind.TEXT, "A/S·품질 보증",
            "고장이나 불량은 어떻게 처리하나요? 보증 기간이 있나요?", "예: 구매 후 1년 무상 A/S, 소모품 제외", null),
    CS_HOURS(DefaultPolicyGroup.SERVICE, Kind.TEXT, "상담 시간",
            "고객 상담은 언제 받나요?", "예: 평일 10시~17시 (점심 12시~13시), 주말·공휴일 휴무", null),
    RECEIPTS(DefaultPolicyGroup.RECEIPTS, Kind.TEXT, "현금영수증·세금계산서",
            "현금영수증이나 세금계산서를 발급하나요?", "예: 현금영수증 발급 가능, 세금계산서는 사업자등록증을 보내 주시면 발급", null);

    /** How the answer is typed. {@link #SHARED} is read from the TalkTalk guidance, never typed here. */
    public enum Kind { TEXT, DAYS, WON, SHARED }

    public static final int MAX_TEXT = 300;
    public static final int MAX_DAYS = 90;
    public static final int MAX_WON = 1_000_000;

    private final DefaultPolicyGroup group;
    private final Kind kind;
    private final String label;
    private final String question;
    private final String placeholder;
    private final String legal;

    DefaultPolicyItem(DefaultPolicyGroup group, Kind kind, String label, String question, String placeholder,
                      String legal) {
        this.group = group;
        this.kind = kind;
        this.label = label;
        this.question = question;
        this.placeholder = placeholder;
        this.legal = legal;
    }

    public DefaultPolicyGroup group() {
        return group;
    }

    public Kind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public String question() {
        return question;
    }

    public String placeholder() {
        return placeholder;
    }

    public String legal() {
        return legal;
    }

    public boolean shared() {
        return kind == Kind.SHARED;
    }

    /**
     * The sentence the document carries for this answer, or null when there is nothing to say. A DAYS or WON item
     * needs its number; its text is an optional note that follows the sentence.
     */
    public String render(Integer number, String text) {
        String note = text == null || text.isBlank() ? null : text.strip();
        return switch (kind) {
            case SHARED -> null;
            case TEXT -> note == null ? null : label + ": " + note;
            case DAYS, WON -> number == null ? null : withNote(numberSentence(number), note);
        };
    }

    private String numberSentence(int n) {
        return switch (this) {
            case DELIVERY_DAYS -> "출고 후 보통 " + n + "일 안에 도착해요(주말·공휴일 제외).";
            case RETURN_WINDOW -> "교환·반품은 상품을 받은 날부터 " + n + "일 안에 신청할 수 있어요.";
            case RETURN_FEE -> n == 0
                    ? "단순 변심으로 교환·반품해도 배송비는 무료예요."
                    : "단순 변심으로 교환·반품하면 왕복 배송비 " + String.format("%,d", n) + "원은 고객님이 부담해요.";
            default -> throw new IllegalStateException("no number sentence for " + this);
        };
    }

    private static String withNote(String sentence, String note) {
        return note == null ? sentence : sentence + " " + note;
    }

    /** A plain-language caution shown beside the answer; never blocks the seller. */
    public String warning(Integer number) {
        if (this == RETURN_WINDOW && number != null && number < 7) {
            return "법정 기준(받은 날부터 7일)보다 짧아요.";
        }
        return null;
    }
}
