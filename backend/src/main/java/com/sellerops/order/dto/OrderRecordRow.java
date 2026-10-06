package com.sellerops.order.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 주문 기록 한 줄 — <b>하나의 결제 단위</b>(docs/product_assembly_ia_v1.md §4d).
 *
 * <p><b>한 줄은 상품주문이 아니다.</b> {@code channel_orders}의 한 행은 상품주문(네이버
 * {@code productOrderId}) 하나이고, 고객이 한 번 결제한 것은 그 행 여럿이다. 행을 그대로 줄로 세우면
 * 13줄짜리 주문 하나가 목록에서 13건으로 읽히고, 그 숫자는 「오늘 주문 몇 건」이라는 질문에 거짓으로
 * 답한다. 그래서 이 레코드의 identity는 {@code channel + seller account + parentOrderId}이고,
 * {@link #lineCount}가 그 안에 몇 줄이 들어 있는지 말한다.
 *
 * <p><b>identity에 채널이 들어가는 이유.</b> 주문번호는 채널 사이에서 유일하지 않다. 번호만으로 줄을
 * 찾거나 길을 내면 다른 채널의 주문이 같은 줄로 읽힐 수 있다.
 *
 * <p><b>상태는 두 칸이다 — 채널이 보낸 값과, 우리가 확인한 뜻.</b>
 * {@link #confirmedStatusLabelKo}는 {@code ChannelOrderStatusVocabulary}가 뜻을 확인한 코드에만
 * 붙고, 그 외에는 {@code null}이다. 화면은 null일 때 {@link #rawStatusCode}를 그대로 보여 준다 —
 * 번역은 이 백엔드에서만 일어나고, 확인하지 않은 코드는 번역되지 않는다.
 *
 * <p><b>줄마다 상태가 다른 결제 단위는 하나의 상태를 갖지 않는다.</b> 그럴 때
 * {@link #rawStatusCode}는 {@code null}이고 {@link #statusVaries}가 참이다 — 한 줄의 코드를 골라
 * 결제 단위 전체의 상태로 적으면, 일부만 취소된 주문이 취소되지 않은 주문으로 읽힌다.
 *
 * @param accountId   어느 연결이 읽어 온 주문인가. 같은 채널에 계정이 둘일 수 있고, identity의 일부다
 * @param paidAt      채널이 말한 결제 시각. 목록의 정렬 축이다
 * @param lastSeenAt  SellerOps가 이 주문을 마지막으로 본 시각 — 값이 아니라 읽기의 사실이다
 */
public record OrderRecordRow(String channelCode,
                             UUID accountId,
                             String parentOrderId,
                             int lineCount,
                             long totalAmount,
                             String rawStatusCode,
                             boolean statusVaries,
                             String confirmedStatusLabelKo,
                             Instant paidAt,
                             Instant lastSeenAt) {
}
