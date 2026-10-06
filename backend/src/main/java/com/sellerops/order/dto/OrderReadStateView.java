package com.sellerops.order.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 한 연결의 주문을 <b>지금 상태로 말해도 되는가</b> — 연결 하나당 한 줄.
 *
 * <p><b>왜 목록 응답이 이것을 함께 보내는가.</b> 「주문 0건」은 두 가지 서로 다른 사실의 같은 모양이다:
 * 읽었고 없었다({@code ZERO}), 또는 읽지 못했다({@code NOT_CONNECTED} · {@code BLOCKED} ·
 * {@code OBSERVED_FRESHNESS_UNPROVEN}). 둘을 섞은 화면은 수집이 멈춘 날 「주문 0건 · 매출 0원」을
 * 조용히 그리고, 그것이 지금 라이브 화면의 결함이다.
 *
 * <p><b>판정은 여기서 다시 하지 않는다.</b> {@code ChannelCoverageService.perOrderState}가 하는 것을
 * 그대로 옮겨 적는다 — 지원 → 연결 → 신선도의 순서가 안전 속성이고, 두 번째 구현은 그 순서를 틀릴 두
 * 번째 기회다({@code OrderStoreFreshness}).
 *
 * @param state          {@code ChannelDataState}의 이름 그대로. 화면이 읽는 어휘는 하나다
 * @param orderLineCount 이 연결이 보유한 상품주문 행 수 — 판정의 입력이기도 하다
 * @param lastSeenAt     이 연결의 주문을 마지막으로 본 시각, 보유 행이 없으면 null
 */
public record OrderReadStateView(String channelCode,
                                 UUID accountId,
                                 String state,
                                 long orderLineCount,
                                 Instant lastSeenAt) {
}
