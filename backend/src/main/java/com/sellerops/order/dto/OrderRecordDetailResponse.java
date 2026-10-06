package com.sellerops.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/orders/{channelCode}/{accountId}/{parentOrderId}} — 결제 단위 하나.
 *
 * <p><b>정체성은 네 조각 전부다</b>(§4d): org(인증 context) · 채널 · 계정 · 주문번호. 세 조각만 맞는
 * 요청은 네 번째를 다른 값으로 보충하지 않고 그 자리에서 없음이 된다 — 좁히지 못한 조회는 「없음」이
 * 아니라 <b>다른 사람의 주문</b>을 열 수 있는 조회이기 때문이다.
 *
 * <p><b>세 축은 따로 적는다.</b> 결제가 취소를 증명하지 않고 취소가 배송을 증명하지 않는다. 증명되지
 * 않은 축의 라벨은 {@code null}이고, 화면은 그것을 「확인되지 않음」으로 그린다 — 「취소되지 않음」이
 * 아니다. 저장된 행은 어떤 부정도 증명하지 못한다({@code ChannelOrderStatusVocabulary}).
 *
 * <p><b>주문 처리(WRITE)는 이 계약에 없다.</b> 상태 변경·취소·발송·송장은 판매자센터의 일이고, 이
 * 응답에 그 액션을 위한 필드는 하나도 없다.
 *
 * @param rawStatusCode   줄마다 코드가 다르면 {@code null}이고 {@link #statusVaries}가 참이다 — 한 줄의
 *                        코드를 결제 단위 전체의 상태로 올려 적지 않는다
 * @param readState       {@code ChannelDataState}의 이름 그대로. 「마지막으로 본 시각」과 함께 읽는
 *                        것이고, 판정은 {@code ChannelCoverageService} 한 곳의 것이다
 * @param statusHistory   오래된 순. 채널이 변경 시각을 주지 않으면 {@code observedAt}은 {@code null}
 */
public record OrderRecordDetailResponse(String channelCode,
                                        UUID accountId,
                                        String parentOrderId,
                                        int lineCount,
                                        long totalAmount,
                                        String rawStatusCode,
                                        boolean statusVaries,
                                        String paymentLabelKo,
                                        String cancellationLabelKo,
                                        String fulfillmentLabelKo,
                                        Instant paidAt,
                                        Instant lastSeenAt,
                                        String readState,
                                        List<OrderLineView> lines,
                                        List<OrderStatusEventView> statusHistory,
                                        List<OrderLinkedInquiryView> inquiries) {
}
