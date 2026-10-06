package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.order.fact.OrderCancellationState;
import com.sellerops.order.fact.OrderFulfillmentState;
import com.sellerops.order.fact.OrderPaymentState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상태 코드의 뜻을 아는 자리가 하나이고, 그 자리가 아는 것이 하나라는 것.
 *
 * <p>이 파일의 모든 테스트는 같은 질문의 다른 면이다: <b>확인하지 않은 코드가 우리 말로 바뀔 수
 * 있는가.</b> 바뀔 수 있다면 「배송 중」이라고 적힌 화면이 실제로는 아무도 확인하지 않은 영어 토큰의
 * 번역이 되고, 그 문장은 고객에게 간다.
 */
class ChannelOrderStatusVocabularyTest {

    /** 쿠팡 ordersheets가 공식으로 쓰는 상태 전부 — 하나도 뜻을 확인한 적이 없다. */
    private static final List<String> COUPANG = List.of(
            "ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING");

    @Test
    @DisplayName("표에 있는 코드는 PAYED 하나뿐이다")
    void onlyOneConfirmedCode() {
        assertThat(ChannelOrderStatusVocabulary.confirmedCodes())
                .as("라이브에서 뜻을 확인한 상태 토큰은 지금까지 하나다 — 늘리는 일은 관찰 뒤에만 일어난다")
                .containsExactly("PAYED");
        assertThat(ChannelOrderStatusVocabulary.labelKo("PAYED")).isEqualTo("결제 완료");
        assertThat(ChannelOrderStatusVocabulary.confirmed("PAYED")).isTrue();
    }

    @Test
    @DisplayName("우리 말은 축이 가진다 — 「결제 완료」가 두 곳에 적혀 있지 않다")
    void theLabelComesFromTheAxis() {
        assertThat(ChannelOrderStatusVocabulary.labelKo("PAYED"))
                .isEqualTo(OrderPaymentState.PAID.labelKo());
    }

    @Test
    @DisplayName("쿠팡 상태 코드는 전부 번역되지 않는다")
    void coupangCodesAreNeverTranslated() {
        for (String code : COUPANG) {
            assertThat(ChannelOrderStatusVocabulary.labelKo(code)).as(code).isNull();
            assertThat(ChannelOrderStatusVocabulary.confirmed(code)).as(code).isFalse();
            assertThat(ChannelOrderStatusVocabulary.axesFromStored(code))
                    .as(code)
                    .isEqualTo(new ChannelOrderStatusVocabulary.Axes(OrderPaymentState.UNKNOWN,
                            OrderCancellationState.UNKNOWN, OrderFulfillmentState.UNKNOWN));
        }
    }

    @Test
    @DisplayName("어떤 저장된 코드도 취소와 배송을 증명하지 못한다")
    void storedCodesNeverProveCancellationOrFulfillment() {
        List<String> probes = new java.util.ArrayList<>(COUPANG);
        probes.addAll(List.of("PAYED", "PAYMENT_WAITING", "CANCEL", "CANCELLED", "CANCELED",
                "RETURNS", "EXCHANGE", "DELIVERED", "SHIPPED", "DISPATCHED", "payed", "PAYED ", "",
                "배송완료"));
        for (String code : probes) {
            ChannelOrderStatusVocabulary.Axes axes = ChannelOrderStatusVocabulary.axesFromStored(code);
            assertThat(axes.cancellation())
                    .as("%s — 마지막 읽기 뒤에 취소된 주문은 취소되지 않은 주문과 똑같이 보인다", code)
                    .isEqualTo(OrderCancellationState.UNKNOWN);
            assertThat(axes.fulfillment())
                    .as("%s — 뜻을 모르는 코드는 발송을 증명하지 못한다", code)
                    .isEqualTo(OrderFulfillmentState.UNKNOWN);
        }
        assertThat(ChannelOrderStatusVocabulary.axesFromStored(null))
                .isEqualTo(new ChannelOrderStatusVocabulary.Axes(OrderPaymentState.UNKNOWN,
                        OrderCancellationState.UNKNOWN, OrderFulfillmentState.UNKNOWN));
    }

    @Test
    @DisplayName("PAYED만 결제를 증명하고, 글자는 채널이 보낸 그대로 맞춘다")
    void onlyPayedProvesPayment() {
        assertThat(ChannelOrderStatusVocabulary.axesFromStored("PAYED").payment())
                .isEqualTo(OrderPaymentState.PAID);
        for (String near : List.of("payed", "Payed", "PAYED ", " PAYED", "PAID", "PAYMENT_DONE")) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored(near).payment())
                    .as("%s — 느슨한 매칭은 「확인한 코드」의 경계를 느슨하게 만든다", near)
                    .isEqualTo(OrderPaymentState.UNKNOWN);
        }
    }

    @Test
    @DisplayName("수집 시점의 정규화도 같은 표를 읽는다")
    void ingestionReadsTheSameTable() {
        assertThat(NormalizedOrderStatus.fromRaw("PAYED")).isEqualTo(NormalizedOrderStatus.PAID);
        List<String> probes = new java.util.ArrayList<>(COUPANG);
        probes.addAll(List.of("payed", "CANCEL", "DELIVERED", ""));
        for (String code : probes) {
            assertThat(NormalizedOrderStatus.fromRaw(code))
                    .as("%s — 표를 넓히는 한 번의 수정이 화면과 수집에 함께 닿는다", code)
                    .isEqualTo(NormalizedOrderStatus.UNKNOWN);
        }
        assertThat(NormalizedOrderStatus.fromRaw(null)).isEqualTo(NormalizedOrderStatus.UNKNOWN);
    }
}
