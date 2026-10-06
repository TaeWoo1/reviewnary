package com.sellerops.order.dto;

import java.time.LocalDate;

/**
 * 목록 위에 먼저 오는 것 — <b>무엇을 얼마나 읽었는지</b>.
 *
 * <p><b>숫자보다 범위가 먼저다</b>(§4d). 「343건」은 그것이 어느 기간을 읽은 결과인지 없이는 읽을 수
 * 없는 숫자다. 그래서 건수와 금액은 {@link #periodFrom}·{@link #periodTo}와 함께만 나간다.
 *
 * <p><b>두 가지 건수를 모두 적는다.</b> {@link #paymentUnitCount}는 결제가 몇 번 일어났는지이고
 * {@link #orderLineCount}는 상품주문이 몇 줄인지다. 하나만 적으면 목록의 줄 수와 숫자가 어긋나 보이고,
 * 둘 중 어느 것이 「주문 건수」인지는 화면이 결정할 일이 아니다.
 *
 * <p><b>0은 여기서 「없다」는 뜻이 아니다.</b> 읽지 못한 채널의 0과 읽었더니 없었던 0은 다른 사실이고,
 * 그 구분은 {@link OrderReadStateView}가 가진다 — 이 레코드는 읽은 것을 세기만 한다.
 *
 * @param periodFrom       읽어 둔 주문 가운데 가장 이른 KST 날짜. 보유 행이 없으면 null
 * @param periodTo         가장 늦은 KST 날짜. 보유 행이 없으면 null
 * @param linkedInquiryCount 채널이 주문번호를 함께 보냈고, 그 번호가 실제로 저장된 주문을 가리키는
 *                           문의의 수. 본문에서 번호를 뽑아 세는 일은 없다
 */
public record OrderRecordExtent(long paymentUnitCount,
                                long orderLineCount,
                                long totalAmount,
                                LocalDate periodFrom,
                                LocalDate periodTo,
                                long linkedInquiryCount) {
}
