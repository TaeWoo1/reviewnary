package com.sellerops.talktalk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The closed automation scope (lane contract §6-3, review item 1). */
class TalkTalkShippingClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "오늘 주문하면 오늘 출고되나요?",
            "금요일에 쉬시나요?",
            "출고 마감 몇 시예요?",
            "몇 시까지 주문하면 당일 발송되나요",
            "안녕하세요 주말에도 발송하나요?",
            "12시 전에 결제하면 오늘 보내주시나요",
            "이번 주 금요일 휴무인가요?",
            "내일 출고되나요?",
            "토요일에 발송하나요?"
    })
    void answersOnlyGeneralGuidanceQuestions(String text) {
        assertThat(TalkTalkShippingClassifier.classify(text, "typing").answerable()).as(text).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // compound natural language — the second half is an unhandled request, whatever the punctuation
            "몇 시 마감인가요 그리고 설치도 해주시나요|UNHANDLED_REQUEST",
            "휴무인가요, 세탁은 가능한가요|UNHANDLED_REQUEST",
            "오늘 발송되나요 선물 포장도 되나요|UNHANDLED_REQUEST",
            "주말에도 출고하나요 사이즈 추천 부탁드려요|UNHANDLED_REQUEST",
            "오늘 주문제작 출고되나요|UNHANDLED_REQUEST",
            // a specific order, its arrival, stock, money, changes
            "제 주문 오늘 출고되나요?|ORDER_SPECIFIC",
            "어제 결제했는데 발송됐나요?|ORDER_SPECIFIC",
            "송장 번호 알려주세요|ORDER_SPECIFIC",
            "배송 언제 와요?|ARRIVAL",
            "오늘 주문하면 언제 도착해요?|ARRIVAL",
            "재고 있으면 오늘 발송되나요|STOCK",
            "오늘 출고 전에 취소 가능할까요|CANCEL_REFUND_EXCHANGE",
            "금요일 휴무면 주소 변경 되나요|CHANGE_REQUEST",
            "배송비 얼마고 오늘 발송되나요|PRICE",
            "파손돼서 왔는데 휴무인가요|DEFECT",
            "이 상품 좋아요?|NOT_SHIPPING_GUIDANCE",
            // a day the reply does not answer: explicit dates, years, far periods, named holidays
            "2027년 12월 25일 출고하나요?|SPECIFIC_DATE",
            "12월 25일 출고하나요?|SPECIFIC_DATE",
            "12/25 발송되나요|SPECIFIC_DATE",
            "25일에 쉬시나요|SPECIFIC_DATE",
            "다음 주 금요일 휴무인가요|SPECIFIC_DATE",
            "다음 달에도 출고하나요|SPECIFIC_DATE",
            "추석에 쉬시나요|SPECIFIC_DATE",
            "공휴일에도 출고 되나요|SPECIFIC_DATE",
            "모레 발송되나요|SPECIFIC_DATE"
    })
    void handsEverythingElseToAPerson(String text, String reason) {
        TalkTalkShippingClassifier.Result r = TalkTalkShippingClassifier.classify(text, "typing");
        assertThat(r.answerable()).as(text).isFalse();
        assertThat(r.reason()).as(text).isEqualTo(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"button", "sticker", "product", "vphone"})
    void onlyTypedText(String inputType) {
        assertThat(TalkTalkShippingClassifier.classify("오늘 출고되나요?", inputType).reason()).isEqualTo("NOT_TYPED");
        assertThat(TalkTalkShippingClassifier.classify("오늘 출고되나요?", null).reason()).isEqualTo("NOT_TYPED");
    }

    @org.junit.jupiter.api.Test
    void longMessagesGoToAPerson() {
        String longText = "오늘 출고되나요 ".repeat(20);
        assertThat(TalkTalkShippingClassifier.classify(longText, "typing").reason()).isEqualTo("TOO_LONG");
    }
}
