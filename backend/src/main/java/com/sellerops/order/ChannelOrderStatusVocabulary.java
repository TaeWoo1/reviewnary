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
 * <p>쿠팡의 {@code ACCEPT · INSTRUCT · DEPARTURE · DELIVERING · FINAL_DELIVERY · NONE_TRACKING}은
 * {@code channel_orders}에 들어와 있지만 그 뜻을 확인한 적이 없다 — 코드 이름이 영어로 읽히는 것은
 * 확인이 아니다. 확인하지 않은 (채널, 코드)는 <b>번역하지 않고</b>({@link #labelKo}이 {@code null})
 * 화면이 채널이 보낸 값을 그대로 보여 준다. 표를 넓히는 일은 실제 셀러 계정에서 전이를 관찰한 뒤에만
 * 일어난다.
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

    /** 아무것도 증명하지 못한 행 — 뜻을 확인하지 않은 (채널, 코드) 전부가 여기로 떨어진다. */
    private static final Axes NOTHING_PROVEN = new Axes(OrderPaymentState.UNKNOWN,
            OrderCancellationState.UNKNOWN, OrderFulfillmentState.UNKNOWN);

    /**
     * 뜻을 확인한 (채널, 코드)와, 그것이 증명하는 축. 여기 없는 조합은 뜻이 없는 조합이다.
     *
     * <p>한 줄뿐인 것이 이 표의 현재 상태이고, 줄을 늘리는 것은 코드 변경이 아니라 라이브 관찰이다.
     */
    private static final Map<Code, Axes> CONFIRMED = Map.of(
            new Code("NAVER", "PAYED"), new Axes(OrderPaymentState.PAID,
                    OrderCancellationState.UNKNOWN, OrderFulfillmentState.UNKNOWN));

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
        return CONFIRMED.get(new Code(channelCode, rawStatusCode));
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
