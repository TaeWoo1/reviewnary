package com.sellerops.order.dto;

import java.time.Instant;

/**
 * 상태 이력 한 줄 — 이 <b>결제 단위</b>가 겪은 전이 하나.
 *
 * <p><b>왜 줄마다가 아니라 결제 단위인가.</b> 열세 줄짜리 주문이 한 번 결제되면 {@code
 * channel_order_status_events}에는 같은 전이가 열세 개 쌓인다. 그것을 그대로 열세 줄로 그리면 한 번
 * 일어난 일이 열세 번 일어난 것처럼 읽힌다. 그래서 같은 전이(이전 코드 · 다음 코드 · 채널이 말한 시각)는
 * 한 줄이고, {@link #lineCount}가 그 전이를 겪은 줄 수를 말한다 — 결제 단위의 일부만 움직였을 때 그
 * 사실이 사라지지 않도록.
 *
 * <p><b>{@link #observedAt}이 {@code null}이면 그대로 {@code null}이다.</b> 채널이 변경 시각을 주지
 * 않은 것이고(쿠팡이 그렇다), 우리가 기록한 시각을 그 자리에 적으면 「채널이 이때 바꿨다」는 말이 된다.
 * 화면은 「—」로 그린다.
 *
 * @param recordedAt SellerOps가 이 전이를 기록한 시각. 한 전이에 행이 여럿일 때는 그 가운데 가장 이른 것
 * @param fromLabelKo 뜻을 확인한 코드에만 붙는 우리 말. 첫 관측이면 이전 코드 자체가 없다
 */
public record OrderStatusEventView(String fromStatusCode,
                                   String fromLabelKo,
                                   String toStatusCode,
                                   String toLabelKo,
                                   Instant observedAt,
                                   Instant recordedAt,
                                   int lineCount) {
}
