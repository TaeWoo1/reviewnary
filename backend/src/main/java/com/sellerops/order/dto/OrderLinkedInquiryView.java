package com.sellerops.order.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 이 주문을 <b>채널이 지목한</b> 문의 하나.
 *
 * <p><b>본문에서 주문번호를 뽑아 잇지 않는다.</b> 연결의 근거는 {@code inquiries.source_order_ref}와
 * {@code order_binding = SOURCE_EXACT}뿐이다 — 채널이 그 문의에 그 주문번호를 붙여 보낸 경우. 고객이
 * 본문에 적은 숫자를 주문번호로 읽으면 남의 주문을 여는 길이 생기고, 그 길은 되돌릴 수 없다.
 *
 * <p>같은 울타리를 {@code InquiryOrderFactReader}와 목록의 {@code linkedInquiryCount}가 쓴다. 세 곳이
 * 같은 조건이어야 「문의가 가리킨 주문 3」과 상세에서 실제로 열리는 관계가 같은 것을 뜻한다.
 *
 * @param sourceOrderRef 채널이 적어 보낸 번호 그대로 — 결제 단위를 가리킬 수도, 상품주문 한 줄을 가리킬
 *                       수도 있다. 화면은 이것으로 어느 줄이 지목됐는지 표시한다
 * @param productName    문의에 붙은 상품의 이름, 붙지 않았거나 이름을 확인할 수 없으면 {@code null}
 */
public record OrderLinkedInquiryView(UUID inquiryId,
                                     String channelCode,
                                     String title,
                                     String body,
                                     String status,
                                     Instant receivedAt,
                                     String sourceOrderRef,
                                     UUID productId,
                                     String productName) {
}
