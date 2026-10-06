package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.order.ChannelOrderStatusVocabulary.Code;
import com.sellerops.order.fact.OrderCancellationState;
import com.sellerops.order.fact.OrderFulfillmentState;
import com.sellerops.order.fact.OrderPaymentState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상태 코드의 뜻을 아는 자리가 하나이고, 그 자리가 아는 것이 하나라는 것.
 *
 * <p>이 파일의 모든 테스트는 같은 질문의 다른 면이다: <b>확인하지 않은 (채널, 코드)가 우리 말로 바뀔 수
 * 있는가.</b> 바뀔 수 있다면 「결제 완료」라고 적힌 화면이 실제로는 아무도 확인하지 않은 영어 토큰의
 * 번역이 되고, 그 문장은 고객에게 간다.
 */
class ChannelOrderStatusVocabularyTest {

    private static final ChannelOrderStatusVocabulary.Axes NOTHING = new ChannelOrderStatusVocabulary
            .Axes(OrderPaymentState.UNKNOWN, OrderCancellationState.UNKNOWN,
            OrderFulfillmentState.UNKNOWN);

    /** 쿠팡 ordersheets가 공식으로 쓰는 상태 전부 — 하나도 뜻을 확인한 적이 없다. */
    private static final List<String> COUPANG_CODES = List.of(
            "ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING");

    @Test
    @DisplayName("표에 있는 것은 NAVER + PAYED 하나뿐이다")
    void onlyOneConfirmedPair() {
        assertThat(ChannelOrderStatusVocabulary.confirmedKeys())
                .as("라이브에서 뜻을 확인한 것은 지금까지 하나다 — 늘리는 일은 관찰 뒤에만 일어난다")
                .containsExactly(new Code("NAVER", "PAYED"));
        assertThat(ChannelOrderStatusVocabulary.labelKo("NAVER", "PAYED")).isEqualTo("결제 완료");
        assertThat(ChannelOrderStatusVocabulary.confirmed("NAVER", "PAYED")).isTrue();
        assertThat(ChannelOrderStatusVocabulary.axesFromStored("NAVER", "PAYED").payment())
                .isEqualTo(OrderPaymentState.PAID);
    }

    @Test
    @DisplayName("같은 PAYED라도 다른 채널이 보낸 것은 번역되지 않는다")
    void payedFromAnotherChannelProvesNothing() {
        for (String channel : List.of("COUPANG", "CAFE24", "GMARKET", "UNKNOWN_CHANNEL", "11ST",
                "naver", "NAVER ")) {
            assertThat(ChannelOrderStatusVocabulary.labelKo(channel, "PAYED"))
                    .as("%s + PAYED — 글자가 같다는 것은 확인이 아니다", channel)
                    .isNull();
            assertThat(ChannelOrderStatusVocabulary.confirmed(channel, "PAYED")).as(channel).isFalse();
            assertThat(ChannelOrderStatusVocabulary.axesFromStored(channel, "PAYED"))
                    .as("%s + PAYED — 어느 축도 증명되지 않는다", channel)
                    .isEqualTo(NOTHING);
            assertThat(NormalizedOrderStatus.fromRaw(channel, "PAYED"))
                    .as("%s — 채널이 하나 늘었다는 이유로 그 채널의 PAYED가 PAID가 되지 않는다", channel)
                    .isEqualTo(NormalizedOrderStatus.UNKNOWN);
        }
    }

    @Test
    @DisplayName("쿠팡 상태 코드는 전부 번역되지 않는다")
    void coupangCodesAreNeverTranslated() {
        for (String code : COUPANG_CODES) {
            assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", code)).as(code).isNull();
            assertThat(ChannelOrderStatusVocabulary.confirmed("COUPANG", code)).as(code).isFalse();
            assertThat(ChannelOrderStatusVocabulary.axesFromStored("COUPANG", code))
                    .as(code).isEqualTo(NOTHING);
        }
    }

    @Test
    @DisplayName("어떤 저장된 코드도 취소와 배송을 증명하지 못한다")
    void storedCodesNeverProveCancellationOrFulfillment() {
        List<String> codes = new ArrayList<>(COUPANG_CODES);
        codes.addAll(List.of("PAYED", "PAYMENT_WAITING", "CANCEL", "CANCELLED", "CANCELED", "RETURNS",
                "EXCHANGE", "DELIVERED", "SHIPPED", "DISPATCHED", "payed", "PAYED ", "", "배송완료"));
        for (String channel : List.of("NAVER", "COUPANG", "CAFE24", "UNKNOWN_CHANNEL")) {
            for (String code : codes) {
                ChannelOrderStatusVocabulary.Axes axes =
                        ChannelOrderStatusVocabulary.axesFromStored(channel, code);
                assertThat(axes.cancellation())
                        .as("%s/%s — 마지막 읽기 뒤에 취소된 주문은 취소되지 않은 주문과 똑같이 보인다",
                                channel, code)
                        .isEqualTo(OrderCancellationState.UNKNOWN);
                assertThat(axes.fulfillment())
                        .as("%s/%s — 뜻을 모르는 코드는 발송을 증명하지 못한다", channel, code)
                        .isEqualTo(OrderFulfillmentState.UNKNOWN);
            }
        }
    }

    @Test
    @DisplayName("글자는 채널이 보낸 그대로 맞춘다 — NAVER의 PAYED만 결제를 증명한다")
    void matchingIsExact() {
        for (String near : List.of("payed", "Payed", "PAYED ", " PAYED", "PAID", "PAYMENT_DONE")) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored("NAVER", near).payment())
                    .as("NAVER/%s — 느슨한 매칭은 「확인한 것」의 경계를 느슨하게 만든다", near)
                    .isEqualTo(OrderPaymentState.UNKNOWN);
        }
    }

    @Test
    @DisplayName("우리 말은 축이 가진다 — 「결제 완료」가 두 곳에 적혀 있지 않다")
    void theLabelComesFromTheAxis() {
        assertThat(ChannelOrderStatusVocabulary.labelKo("NAVER", "PAYED"))
                .isEqualTo(OrderPaymentState.PAID.labelKo());
    }

    @Test
    @DisplayName("채널도 코드도 없는 것을 물어볼 수 있다")
    void nullsAreAnswerableQuestions() {
        // 줄마다 코드가 다른 결제 단위에는 하나의 코드가 없고, 채널을 이름으로 부를 수 없는 행도 있다.
        assertThat(ChannelOrderStatusVocabulary.labelKo("NAVER", null)).isNull();
        assertThat(ChannelOrderStatusVocabulary.labelKo(null, "PAYED")).isNull();
        assertThat(ChannelOrderStatusVocabulary.labelKo(null, null)).isNull();
        assertThat(ChannelOrderStatusVocabulary.confirmed(null, "PAYED")).isFalse();
        assertThat(ChannelOrderStatusVocabulary.axesFromStored(null, "PAYED")).isEqualTo(NOTHING);
        assertThat(ChannelOrderStatusVocabulary.axesFromStored("NAVER", null)).isEqualTo(NOTHING);
        assertThat(NormalizedOrderStatus.fromRaw(null, "PAYED"))
                .as("채널을 이름으로 부를 수 없으면 주장도 없다 — raw 코드는 그대로 저장된다")
                .isEqualTo(NormalizedOrderStatus.UNKNOWN);
    }

    @Test
    @DisplayName("수집 시점의 정규화도 같은 표를 읽는다")
    void ingestionReadsTheSameTable() {
        assertThat(NormalizedOrderStatus.fromRaw("NAVER", "PAYED"))
                .isEqualTo(NormalizedOrderStatus.PAID);
        List<String> codes = new ArrayList<>(COUPANG_CODES);
        codes.addAll(List.of("payed", "CANCEL", "DELIVERED", ""));
        for (String code : codes) {
            assertThat(NormalizedOrderStatus.fromRaw("NAVER", code))
                    .as("NAVER/%s — 표를 넓히는 한 번의 수정이 화면과 수집에 함께 닿는다", code)
                    .isEqualTo(NormalizedOrderStatus.UNKNOWN);
        }
        assertThat(NormalizedOrderStatus.fromRaw(null, null)).isEqualTo(NormalizedOrderStatus.UNKNOWN);
    }
}
