package com.sellerops.order;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.order.dto.OrderRecordDetailResponse;
import com.sellerops.order.dto.OrderRecordListResponse;
import com.sellerops.order.dto.OrderSummaryResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRecordService orderRecords;
    private final OrderRecordDetailService orderDetail;

    public OrderController(OrderService orderService, OrderRecordService orderRecords,
                           OrderRecordDetailService orderDetail) {
        this.orderService = orderService;
        this.orderRecords = orderRecords;
        this.orderDetail = orderDetail;
    }

    /**
     * 결제 단위 기록 목록 — 한 줄이 하나의 결제 단위(§4d).
     *
     * <p>필터 없이 이 org이 읽어 둔 것을 결제 시각 최신순으로 돌려준다. 기간·채널로 좁히는 일은
     * 매출 집계({@code /summary})가 하던 것이고, 기록 목록은 「무엇을 읽어 두었는가」를 먼저 답한다 —
     * 응답의 {@code extent}와 {@code reads}가 그것이다.
     */
    @GetMapping
    public OrderRecordListResponse records(@AuthenticationPrincipal AuthPrincipal principal) {
        return orderRecords.list(principal.orgId());
    }

    /**
     * 결제 단위 하나 — 정체성 네 조각(§4d): org은 인증 context, 나머지 셋은 주소.
     *
     * <p>주소에 계정이 들어 있는 것이 이 endpoint의 안전 속성이다. 주문번호는 채널 사이에서 유일하지
     * 않고 한 org이 같은 채널에 계정을 둘 가질 수 있으므로, 번호만으로도 {@code channel + 번호}만으로도
     * 레코드가 하나로 좁혀지지 않는다. 좁히지 못한 조회는 「없음」이 아니라 다른 사람의 주문을 열 수 있는
     * 조회이므로, 넷 중 하나라도 어긋나면 보충 없이 404다.
     */
    @GetMapping("/{channelCode}/{accountId}/{parentOrderId}")
    public OrderRecordDetailResponse detail(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable String channelCode,
                                            @PathVariable UUID accountId,
                                            @PathVariable String parentOrderId) {
        return orderDetail.detail(principal.orgId(), channelCode, accountId, parentOrderId);
    }

    /** Order/sales summary. {@code from}/{@code to} (ISO date) and {@code channelId}
     *  are optional; default is the last 7 days, all channels. Unparseable params
     *  or an invalid range yield 400. */
    @GetMapping("/summary")
    public OrderSummaryResponse summary(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID channelId) {
        return orderService.summary(principal.orgId(), from, to, channelId);
    }
}
