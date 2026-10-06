package com.sellerops.order.dto;

import java.util.List;

/**
 * {@code GET /api/orders} — 결제 단위 기록 목록.
 *
 * <p>세 부분이고, 읽는 순서가 그 순서다: 무엇을 얼마나 읽었는지({@link #extent}), 그 읽기를 믿어도
 * 되는지({@link #reads}), 그리고 레코드({@link #rows}).
 *
 * <p><b>정렬은 서버의 것이다.</b> {@link #rows}는 결제 시각 최신순으로 이미 서 있다. 화면이 다시 줄을
 * 세우면 서버가 고른 기준과 화면이 보여 주는 순서가 어긋나고, 상품 목록에서 끝낸 그 결함이 여기서
 * 되살아난다.
 *
 * <p><b>{@link #rows}는 잘릴 수 있고, 잘렸다는 사실은 {@link #hasMore}가 말한다.</b> 한 번의 응답이
 * 들고 나가는 줄 수에는 상한이 있고({@code OrderRecordService.MAX_ROWS}), {@link #extent}의 건수는
 * 상한과 무관한 전수다. 두 수를 비교해 「더 있다」를 <b>추론하게 두지 않는다</b> — 그 추론을 잊은 화면은
 * 500줄을 전부라고 그리고, 「343건」 같은 머리 숫자와 목록이 어긋난 이유를 아무도 알 수 없다.
 *
 * @param hasMore 이 응답이 부분 응답인가. 참이면 {@link #extent}의 건수가 전수이고 {@link #rows}는
 *                그 앞부분(결제 시각 최신순)이다. 숫자가 틀린 것이 아니다
 */
public record OrderRecordListResponse(OrderRecordExtent extent,
                                      List<OrderReadStateView> reads,
                                      List<OrderRecordRow> rows,
                                      boolean hasMore) {
}
