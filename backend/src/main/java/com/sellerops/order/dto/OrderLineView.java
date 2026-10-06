package com.sellerops.order.dto;

import java.time.Instant;

/**
 * 결제 단위 안의 <b>상품주문 한 줄</b> — {@code channel_orders}의 한 행 그대로.
 *
 * <p><b>칸은 채널이 준 것뿐이다</b>(§4d). 수량·옵션·상품 연결·할인·세금·배송비는 이 저장소가 읽지 않으므로
 * 빈 열로 만들지 않는다 — 빈 열은 「그 값이 없다」로 읽히고, 사실은 「우리가 읽지 않는다」이다.
 *
 * <p><b>줄마다 다를 수 있는 것만 줄에 적는다.</b> 열세 줄이 모두 같은 코드·같은 결제 시각이면 그것은 줄의
 * 속성이 아니라 결제 단위의 속성이므로, 화면은 그 값을 섹션 머리로 올리고 열을 지운다. 그 판단을 하려면
 * 줄마다의 값이 필요하므로 여기에 그대로 담아 보낸다.
 *
 * @param confirmedStatusLabelKo 뜻을 확인한 (채널, 코드)에만 붙는 우리 말. 아니면 {@code null}이고
 *                               화면은 {@link #rawStatusCode}를 그대로 보여 준다
 */
public record OrderLineView(String externalOrderId,
                            long paymentAmount,
                            String rawStatusCode,
                            String confirmedStatusLabelKo,
                            Instant paidAt) {
}
