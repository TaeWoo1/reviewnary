package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.order.ChannelOrderStatusVocabulary.Code;
import com.sellerops.order.fact.OrderCancellationState;
import com.sellerops.order.fact.OrderFulfillmentState;
import com.sellerops.order.fact.OrderPaymentState;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상태 코드의 뜻을 아는 자리가 하나이고, 그 자리가 아는 것이 무엇인지.
 *
 * <p>이 파일의 모든 테스트는 같은 질문의 다른 면이다: <b>확인하지 않은 (채널, 코드)가 우리 말로 바뀔 수
 * 있는가.</b> 바뀔 수 있다면 「결제 완료」라고 적힌 화면이 실제로는 아무도 확인하지 않은 영어 토큰의
 * 번역이 되고, 그 문장은 고객에게 간다.
 *
 * <p><b>2026-10-10에 다섯 테스트가 다시 쓰였다 (Order Context Foundation v1, D2).</b> 그 전까지 이 파일은
 * 「표에 있는 것은 하나뿐」을 고정했고, 표가 정당하게 넓어지는 날 깨지도록 되어 있었다 — 그리고 깨졌다.
 * 다시 쓰면서 <b>규율은 하나도 버리지 않았다</b>: 글자는 여전히 정확히 맞추고, 저장된 행은 여전히 어떤
 * 부정도 증명하지 못하고, 뜻이 확인되지 않은 코드는 여전히 번역되지 않는다. 바뀐 것은 확인된 줄의
 * <b>수</b>이고, 늘어난 각 줄은 자기 증거 문서를 들고 있다.
 */
class ChannelOrderStatusVocabularyTest {

    private static final ChannelOrderStatusVocabulary.Axes NOTHING = new ChannelOrderStatusVocabulary
            .Axes(OrderPaymentState.UNKNOWN, OrderCancellationState.UNKNOWN,
            OrderFulfillmentState.UNKNOWN);

    /** 쿠팡 ordersheets가 공식으로 쓰는 상태 전부. */
    private static final List<String> COUPANG_CODES = List.of(
            "ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING");

    /** 공식 문서가 자기 안에서 일관되지 않거나 우리 축에 들어가지 않는 둘 — 승격되지 않았다. */
    private static final List<String> COUPANG_UNPROMOTED = List.of("DEPARTURE", "NONE_TRACKING");

    @Test
    @DisplayName("표에 있는 것은 다섯 줄이고, 줄마다 무엇을 증명하는지 적혀 있다")
    void theConfirmedTableIsExactlyFive() {
        assertThat(ChannelOrderStatusVocabulary.confirmedKeys())
                .as("표를 넓히는 일은 관측과 계약이 둘 다 생긴 뒤에만 일어난다")
                .containsExactlyInAnyOrder(
                        new Code("NAVER", "PAYED"),
                        new Code("COUPANG", "ACCEPT"),
                        new Code("COUPANG", "INSTRUCT"),
                        new Code("COUPANG", "DELIVERING"),
                        new Code("COUPANG", "FINAL_DELIVERY"));

        assertThat(ChannelOrderStatusVocabulary.labelKo("NAVER", "PAYED")).isEqualTo("결제 완료");
        assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", "ACCEPT")).isEqualTo("결제 완료");
        assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", "INSTRUCT")).isEqualTo("발송 준비 중");
        assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", "DELIVERING")).isEqualTo("배송 중");
        assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", "FINAL_DELIVERY")).isEqualTo("배송 완료");

        assertThat(ChannelOrderStatusVocabulary.axesFromStored("COUPANG", "FINAL_DELIVERY").fulfillment())
                .as("이 한 줄이 post-purchase context의 발송 축 전체다")
                .isEqualTo(OrderFulfillmentState.DELIVERED);
    }

    @Test
    @DisplayName("승격되지 않은 두 쿠팡 코드는 계속 번역되지 않는다 — 그리고 그것이 이 변경의 절반이다")
    void theTwoUnpromotedCoupangCodesStayUntranslated() {
        for (String code : COUPANG_UNPROMOTED) {
            // DEPARTURE: 공식 문서의 영문판이 Shipped, 한국어판이 배송지시다. 플랫폼 자신의 두 문장이
            // 다르면 그것은 확인이 아니다. NONE_TRACKING: 「추적 없이 판매자가 보냈다」이고 우리 세 축의
            // 어느 값에도 그대로 들어가지 않는다.
            assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", code)).as(code).isNull();
            assertThat(ChannelOrderStatusVocabulary.confirmed("COUPANG", code)).as(code).isFalse();
            assertThat(ChannelOrderStatusVocabulary.axesFromStored("COUPANG", code))
                    .as("%s — 애매한 코드는 어느 축도 증명하지 않는다", code).isEqualTo(NOTHING);
            assertThat(ChannelOrderStatusVocabulary.evidenceDocFor("COUPANG", code)).as(code).isNull();
        }
    }

    @Test
    @DisplayName("발송을 증명하는 코드가 결제를 증명하지는 않는다")
    void aFulfillmentCodeDoesNotProvePayment() {
        // 배송된 주문이 결제되지 않았을 리 없다는 것은 상식이지만 FINAL_DELIVERY가 말한 것은 아니다.
        // 상식으로 축을 채우기 시작하면 세 축을 나눠 둔 이유가 사라진다 — 그래서 133건의 배송 완료
        // 주문은 「배송 완료 · 결제는 확인되지 않음」으로 읽힌다. 이상하게 들리는 것이 정확하다.
        for (String code : List.of("INSTRUCT", "DELIVERING", "FINAL_DELIVERY")) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored("COUPANG", code).payment())
                    .as("COUPANG/%s — 발송 코드가 결제를 증명하면 「미결제」가 조용히 사라진다", code)
                    .isEqualTo(OrderPaymentState.UNKNOWN);
            assertThat(NormalizedOrderStatus.fromRaw("COUPANG", code))
                    .as("COUPANG/%s — 수집 시점의 정규화는 결제 축이고 발송으로 채워지지 않는다", code)
                    .isEqualTo(NormalizedOrderStatus.UNKNOWN);
        }
        // 그리고 결제를 증명하는 코드가 발송을 증명하지도 않는다.
        assertThat(ChannelOrderStatusVocabulary.axesFromStored("COUPANG", "ACCEPT").fulfillment())
                .as("쿠팡이 주문을 접수했다는 것은 발송 축의 무엇도 증명하지 않는다")
                .isEqualTo(OrderFulfillmentState.UNKNOWN);
        assertThat(ChannelOrderStatusVocabulary.axesFromStored("NAVER", "PAYED").fulfillment())
                .isEqualTo(OrderFulfillmentState.UNKNOWN);
    }

    @Test
    @DisplayName("확인된 줄은 모두 디스크에 있는 증거 문서를 가리킨다")
    void everyConfirmationNamesADocumentThatExists() {
        // 증거가 지워진 선언은 지워진 뒤에도 권위 있어 보인다. 경로는 backend/ 기준이므로 한 단계 위다.
        for (Code code : ChannelOrderStatusVocabulary.confirmedKeys()) {
            String doc = ChannelOrderStatusVocabulary.evidenceDocFor(
                    code.channelCode(), code.rawStatusCode());
            assertThat(doc).as("%s/%s — 증거 없는 번역", code.channelCode(), code.rawStatusCode())
                    .isNotNull();
            assertThat(Files.exists(Paths.get("..").resolve(doc)))
                    .as("%s/%s 의 증거 %s 가 디스크에 없다", code.channelCode(), code.rawStatusCode(), doc)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("같은 코드라도 다른 채널이 보낸 것은 번역되지 않는다")
    void theSameCodeFromAnotherChannelProvesNothing() {
        // PAYED는 NAVER의 말, FINAL_DELIVERY는 COUPANG의 말이다. 양쪽으로 확인한다 — 표가 두 채널을
        // 갖게 된 지금은 「채널이 키의 일부」가 한 방향으로만 성립할 위험이 처음 생겼다.
        for (String channel : List.of("COUPANG", "CAFE24", "GMARKET", "UNKNOWN_CHANNEL", "11ST",
                "naver", "NAVER ")) {
            assertThat(ChannelOrderStatusVocabulary.labelKo(channel, "PAYED"))
                    .as("%s + PAYED — 글자가 같다는 것은 확인이 아니다", channel).isNull();
            assertThat(NormalizedOrderStatus.fromRaw(channel, "PAYED"))
                    .as("%s — 채널이 하나 늘었다는 이유로 그 채널의 PAYED가 PAID가 되지 않는다", channel)
                    .isEqualTo(NormalizedOrderStatus.UNKNOWN);
        }
        for (String channel : List.of("NAVER", "CAFE24", "GMARKET", "coupang", "COUPANG ")) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored(channel, "FINAL_DELIVERY"))
                    .as("%s + FINAL_DELIVERY — 쿠팡의 어휘는 쿠팡에서만 뜻을 가진다", channel)
                    .isEqualTo(NOTHING);
        }
    }

    @Test
    @DisplayName("저장된 행은 어떤 부정도 증명하지 못하고, 뜻 모르는 코드는 발송도 증명하지 못한다")
    void storedCodesNeverProveNegativesOrUnconfirmedFulfillment() {
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
                if (!ChannelOrderStatusVocabulary.confirmed(channel, code)) {
                    assertThat(axes.fulfillment())
                            .as("%s/%s — 뜻을 모르는 코드는 발송을 증명하지 못한다", channel, code)
                            .isEqualTo(OrderFulfillmentState.UNKNOWN);
                }
            }
        }
    }

    @Test
    @DisplayName("글자는 채널이 보낸 그대로 맞춘다")
    void matchingIsExact() {
        for (String near : List.of("payed", "Payed", "PAYED ", " PAYED", "PAID", "PAYMENT_DONE")) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored("NAVER", near).payment())
                    .as("NAVER/%s — 느슨한 매칭은 「확인한 것」의 경계를 느슨하게 만든다", near)
                    .isEqualTo(OrderPaymentState.UNKNOWN);
        }
        for (String near : List.of("final_delivery", "Final_Delivery", "FINAL_DELIVERY ",
                "FINALDELIVERY", "DELIVERY_COMPLETE")) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored("COUPANG", near).fulfillment())
                    .as("COUPANG/%s — 비슷한 글자는 같은 코드가 아니다", near)
                    .isEqualTo(OrderFulfillmentState.UNKNOWN);
        }
    }

    @Test
    @DisplayName("우리 말은 축이 가진다 — 「배송 완료」가 두 곳에 적혀 있지 않다")
    void theLabelComesFromTheAxis() {
        assertThat(ChannelOrderStatusVocabulary.labelKo("NAVER", "PAYED"))
                .isEqualTo(OrderPaymentState.PAID.labelKo());
        assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", "FINAL_DELIVERY"))
                .isEqualTo(OrderFulfillmentState.DELIVERED.labelKo());
        assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", "DELIVERING"))
                .isEqualTo(OrderFulfillmentState.IN_TRANSIT.labelKo());
        assertThat(ChannelOrderStatusVocabulary.labelKo("COUPANG", "INSTRUCT"))
                .isEqualTo(OrderFulfillmentState.AWAITING_SHIPMENT.labelKo());
    }

    @Test
    @DisplayName("채널도 코드도 없는 것을 물어볼 수 있다")
    void nullsAreAnswerableQuestions() {
        // 줄마다 코드가 다른 결제 단위에는 하나의 코드가 없고, 채널을 이름으로 부를 수 없는 행도 있다.
        assertThat(ChannelOrderStatusVocabulary.labelKo("NAVER", null)).isNull();
        assertThat(ChannelOrderStatusVocabulary.labelKo(null, "PAYED")).isNull();
        assertThat(ChannelOrderStatusVocabulary.labelKo(null, null)).isNull();
        assertThat(ChannelOrderStatusVocabulary.confirmed(null, "PAYED")).isFalse();
        assertThat(ChannelOrderStatusVocabulary.evidenceDocFor(null, null)).isNull();
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
        assertThat(NormalizedOrderStatus.fromRaw("COUPANG", "ACCEPT"))
                .as("표를 넓히는 한 번의 수정이 화면과 수집에 함께 닿는다")
                .isEqualTo(NormalizedOrderStatus.PAID);
        List<String> unpaid = new ArrayList<>(COUPANG_UNPROMOTED);
        unpaid.addAll(List.of("payed", "CANCEL", "DELIVERED", ""));
        for (String code : unpaid) {
            assertThat(NormalizedOrderStatus.fromRaw("NAVER", code))
                    .as("NAVER/%s", code).isEqualTo(NormalizedOrderStatus.UNKNOWN);
            assertThat(NormalizedOrderStatus.fromRaw("COUPANG", code))
                    .as("COUPANG/%s", code).isEqualTo(NormalizedOrderStatus.UNKNOWN);
        }
        assertThat(NormalizedOrderStatus.fromRaw(null, null)).isEqualTo(NormalizedOrderStatus.UNKNOWN);
    }
}
