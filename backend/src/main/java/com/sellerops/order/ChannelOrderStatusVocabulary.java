package com.sellerops.order;

import com.sellerops.order.fact.OrderCancellationState;
import com.sellerops.order.fact.OrderFulfillmentState;
import com.sellerops.order.fact.OrderPaymentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 채널이 보낸 주문 상태 코드의 <b>뜻</b>을 아는 유일한 자리.
 *
 * <p><b>왜 한 곳인가.</b> 같은 코드의 뜻이 두 곳에 적히면 두 곳이 서로 다른 날 고쳐진다 — 문의 화면은
 * 「배송 중」이라 쓰고 주문 화면은 raw 값을 그대로 두는 식으로. 그 불일치는 화면의 흠이 아니라 고객에게
 * 잘못된 말을 하는 길이다. 그래서 번역표는 이 파일 하나이고, 코드를 우리 말로 바꾸는 모든 경로가
 * 여기를 지난다 — 문의 상세, 주문 목록·상세, 그리고 수집 시점의 정규화
 * ({@link NormalizedOrderStatus#fromRaw}).
 *
 * <p><b>확인의 단위는 코드가 아니라 (채널, 코드)다.</b> 상태 코드는 채널의 어휘이고, 채널 사이에서
 * 같은 글자가 같은 뜻이라는 보장은 없다. 이 저장소가 라이브에서 뜻을 확인한 것은
 * <b>{@code NAVER}의 {@code PAYED}</b> 하나뿐이다({@code lastChangedType=PAYED} 범위의 주문 읽기).
 * 그러므로 {@code COUPANG}이 보낸 {@code PAYED}도, 아직 아무것도 확인하지 않은 새 채널이 보낸
 * {@code PAYED}도 번역되지 않는다 — 글자가 같다는 것은 확인이 아니다. 키를 코드 하나로 두면 채널이
 * 하나 늘어나는 날 그 채널의 모든 {@code PAYED}가 조용히 「결제 완료」가 된다.
 *
 * <p><b>2026-10-10 — 쿠팡 네 코드가 번역된다 (Order Context Foundation v1, D2).</b> 그 전까지
 * {@code ACCEPT · INSTRUCT · DEPARTURE · DELIVERING · FINAL_DELIVERY · NONE_TRACKING}은
 * {@code channel_orders}에 들어와 있으면서 번역되지 않았다 — 「코드 이름이 영어로 읽히는 것은 확인이
 * 아니다」. 그 확인에는 <b>서로 다른 증거 둘</b>이 필요했고 이제 둘 다 있다.
 *
 * <ol>
 *   <li><b>코드가 실제 셀러 계정에 도착하는 것을 보았다</b> — 2026-08-06 승인된 read-only run
 *       ({@code docs/coupang_ordersheets_response_contract_hardening_v1.md}: FINAL_DELIVERY 30 ·
 *       DELIVERING 13 · ACCEPT 7).</li>
 *   <li><b>그 코드가 무엇을 뜻하는지 플랫폼이 문서로 말한다</b> —
 *       {@code docs/vendor/coupang-openapi/get-ordersheet-history.md}의 {@code deliveryStatus} 표.</li>
 * </ol>
 *
 * <p>하나만으로는 번역이 되지 않는다. 관측만 있으면 도착한 글자를 우리가 해석한 것이고, 문서만 있으면
 * 이 판매자의 계정에서 실제로 쓰이는지 모른다. 그래서 {@link Confirmation}이 증거 문서를 함께 들고
 * 있고, 테스트가 그 파일이 디스크에 있는지 확인한다 — 증거가 삭제된 선언은 삭제된 뒤에도 권위 있어
 * 보인다.
 *
 * <p><b>두 코드는 승격되지 않았고 그것이 이 변경의 절반이다.</b> {@code DEPARTURE}는 공식 문서의
 * 영문판이 *Shipped*, 한국어판이 *배송지시*라고 말한다 — 물건이 떠났다는 말과 떠나라고 지시했다는
 * 말이다. 같은 코드에 대한 플랫폼 자신의 두 문장이 다르면 그것은 확인이 아니다.
 * {@code NONE_TRACKING}은 「추적 없이 판매자가 보냈다」이고 이 어휘의 어느 축에도 그대로 들어가지
 * 않는다. 둘은 계속 번역되지 않고({@link #labelKo}이 {@code null}) 화면이 raw 값을 그대로 보여 준다.
 *
 * <p>확인하지 않은 (채널, 코드)는 여전히 <b>번역하지 않는다</b>. 표를 넓히는 일은 관측과 계약이 둘 다
 * 생긴 뒤에만 일어난다.
 *
 * <p><b>우리 말은 축의 것이다.</b> 번역표는 (채널, 코드)가 어느 축의 무엇을 증명하는지만 적고, 그 축의
 * 한국어는 {@link OrderPaymentState#labelKo()} 계열이 가진다 — 「결제 완료」라는 문자열이 두 곳에
 * 적히지 않도록.
 *
 * <p><b>저장된 행은 증명한 축까지만 말한다.</b> 어떤 코드도 「취소되지 않았습니다」를 증명하지 못한다 —
 * 마지막 읽기 뒤에 취소된 주문은 한 번도 취소되지 않은 주문과 똑같이 보이므로, 그 부정은 지금 채널에
 * 묻는 길({@code ExactOrderReader})만 만들 수 있다. {@link #axesFromStored}가 그 부정을 표에서
 * 들어온다 해도 지워 버리는 이유다.
 */
public final class ChannelOrderStatusVocabulary {

    /** 확인의 단위 — 어느 채널이 보낸 어느 코드인가. 글자만으로는 키가 되지 못한다. */
    public record Code(String channelCode, String rawStatusCode) {
    }

    /**
     * 한 (채널, 코드)가 증명하는 축과, <b>그 확인을 지탱하는 증거</b>.
     *
     * <p>{@code evidenceDoc}은 저장소 상대 경로이고 테스트가 디스크에 있는지 확인한다 —
     * {@code ExactOrderLookupCapability.CONTRACT_DOCS}가 같은 이유로 같은 모양을 쓴다. 증거를 축과
     * 같은 자리에 두는 이유는 표가 넓어질 때 「이건 왜 번역되나」가 코드에서 바로 읽히게 하려는 것이다;
     * 주석에 적힌 증거는 다음 줄이 추가될 때 따라오지 않는다.
     */
    public record Confirmation(Axes axes, String evidenceDoc) {
    }

    /** 아무것도 증명하지 못한 행 — 뜻을 확인하지 않은 (채널, 코드) 전부가 여기로 떨어진다. */
    private static final Axes NOTHING_PROVEN = new Axes(OrderPaymentState.UNKNOWN,
            OrderCancellationState.UNKNOWN, OrderFulfillmentState.UNKNOWN);

    private static final String NAVER_PAYED_EVIDENCE =
            "docs/vendor/naver-commerce-api/get-v1-pay-order-seller-product-orders-last-changed-statuses.md";
    private static final String COUPANG_STATUS_EVIDENCE =
            "docs/vendor/coupang-openapi/get-ordersheet-history.md";

    /**
     * 뜻을 확인한 (채널, 코드)와, 그것이 증명하는 축 · 그 확인의 증거. 여기 없는 조합은 뜻이 없는 조합이다.
     *
     * <p><b>각 줄은 자기가 증명하는 축까지만 적는다.</b> {@code FINAL_DELIVERY}는 배송 완료를 증명하고
     * <b>결제를 증명하지 않는다</b> — 배송된 주문이 결제되지 않았을 리 없다는 것은 상식이지만 이 코드가
     * 말한 것은 아니고, 상식으로 축을 채우기 시작하면 세 축을 나눠 둔 이유가 사라진다. 그래서 133건의
     * 배송 완료 주문은 「배송 완료 · 결제는 확인되지 않음」으로 읽힌다. 그 문장이 이상하게 들리는 것이
     * 정확하다.
     */
    private static final Map<Code, Confirmation> CONFIRMED = Map.of(
            new Code("NAVER", "PAYED"), new Confirmation(
                    new Axes(OrderPaymentState.PAID,
                            OrderCancellationState.UNKNOWN, OrderFulfillmentState.UNKNOWN),
                    NAVER_PAYED_EVIDENCE),
            // 결제완료. 결제 축만 — 쿠팡이 주문을 접수했다는 것이 발송 축의 무엇도 증명하지 않는다.
            new Code("COUPANG", "ACCEPT"), new Confirmation(
                    new Axes(OrderPaymentState.PAID,
                            OrderCancellationState.UNKNOWN, OrderFulfillmentState.UNKNOWN),
                    COUPANG_STATUS_EVIDENCE),
            // 상품준비중 — 아직 발송되지 않았다. Cafe24의 F와 같은 축의 같은 값.
            new Code("COUPANG", "INSTRUCT"), new Confirmation(
                    new Axes(OrderPaymentState.UNKNOWN,
                            OrderCancellationState.UNKNOWN, OrderFulfillmentState.AWAITING_SHIPMENT),
                    COUPANG_STATUS_EVIDENCE),
            // 배송중.
            new Code("COUPANG", "DELIVERING"), new Confirmation(
                    new Axes(OrderPaymentState.UNKNOWN,
                            OrderCancellationState.UNKNOWN, OrderFulfillmentState.IN_TRANSIT),
                    COUPANG_STATUS_EVIDENCE),
            // 배송완료. 이 한 줄이 Post-purchase context의 발송 축 전체다.
            new Code("COUPANG", "FINAL_DELIVERY"), new Confirmation(
                    new Axes(OrderPaymentState.UNKNOWN,
                            OrderCancellationState.UNKNOWN, OrderFulfillmentState.DELIVERED),
                    COUPANG_STATUS_EVIDENCE));

    private ChannelOrderStatusVocabulary() {
    }

    /**
     * 이 (채널, 코드)의 우리 말, 또는 {@code null} — 뜻을 확인하지 않았다는 뜻이고, 화면은 raw 값을
     * 그대로 쓴다.
     *
     * <p>채널 코드와 상태 코드 모두 채널이 보낸 그대로 맞춘다. {@code payed}는 {@code PAYED}가 아니고
     * {@code naver}는 {@code NAVER}가 아니다 — 느슨하게 받으면 「우리가 확인한 것」의 경계가
     * 느슨해진다.
     */
    public static String labelKo(String channelCode, String rawStatusCode) {
        // null은 물어볼 만한 질문이다 — 줄마다 코드가 다른 결제 단위에는 하나의 코드가 없고, 채널을
        // 이름으로 부를 수 없는 행도 있다.
        Axes axes = lookup(channelCode, rawStatusCode);
        if (axes == null) {
            return null;
        }
        List<String> proven = new ArrayList<>(3);
        add(proven, axes.cancellation().labelKo());
        add(proven, axes.payment().labelKo());
        add(proven, axes.fulfillment().labelKo());
        return proven.isEmpty() ? null : String.join(" · ", proven);
    }

    /** 뜻을 확인한 조합인가 — 화면이 「우리 말로 쓸 것인가, raw로 둘 것인가」를 묻는 자리. */
    public static boolean confirmed(String channelCode, String rawStatusCode) {
        return labelKo(channelCode, rawStatusCode) != null;
    }

    /** 뜻을 확인한 (채널, 코드) 전부. 테스트가 이 표의 크기를 지킬 수 있도록 공개한다. */
    public static Set<Code> confirmedKeys() {
        return CONFIRMED.keySet();
    }

    /**
     * 이 (채널, 코드)의 번역을 지탱하는 저장소 상대 문서 경로, 또는 {@code null}.
     *
     * <p>테스트가 모든 줄의 문서가 디스크에 있는지 확인한다. 증거가 지워진 선언은 지워진 뒤에도 권위
     * 있어 보이고, 그것이 이 getter가 존재하는 유일한 이유다.
     */
    public static String evidenceDocFor(String channelCode, String rawStatusCode) {
        Confirmation confirmation = CONFIRMED.get(new Code(channelCode, rawStatusCode));
        return confirmation == null ? null : confirmation.evidenceDoc();
    }

    /**
     * 저장된 한 행이 증명하는 세 축 — 표가 아는 만큼, 그리고 부정은 뺀 채로.
     *
     * <p>문의 화면과 주문 화면이 같은 이 함수를 지난다. 두 화면이 각자 매핑을 들고 있던 동안은 한쪽을
     * 고치면서 다른 쪽을 잊는 것이 가능했다.
     */
    public static Axes axesFromStored(String channelCode, String rawStatusCode) {
        Axes axes = lookup(channelCode, rawStatusCode);
        if (axes == null) {
            return NOTHING_PROVEN;
        }
        // 부정은 저장된 행이 만들 수 없다. 표에 그런 줄이 생기더라도 여기서 끝난다.
        return new Axes(
                axes.payment() == OrderPaymentState.UNPAID ? OrderPaymentState.UNKNOWN : axes.payment(),
                axes.cancellation() == OrderCancellationState.NOT_CANCELLED
                        ? OrderCancellationState.UNKNOWN : axes.cancellation(),
                axes.fulfillment());
    }

    private static Axes lookup(String channelCode, String rawStatusCode) {
        if (channelCode == null || rawStatusCode == null) {
            return null;
        }
        Confirmation confirmation = CONFIRMED.get(new Code(channelCode, rawStatusCode));
        return confirmation == null ? null : confirmation.axes();
    }

    private static void add(List<String> out, String label) {
        if (label != null) {
            out.add(label);
        }
    }

    /** 세 축을 한 번에 건네는 운반체 — 셋을 따로 돌려주면 호출자가 순서를 섞을 수 있다. */
    public record Axes(OrderPaymentState payment,
                       OrderCancellationState cancellation,
                       OrderFulfillmentState fulfillment) {
    }
}
