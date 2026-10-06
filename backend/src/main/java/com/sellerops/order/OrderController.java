package com.sellerops.order;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.order.dto.OrderRecordListResponse;
import com.sellerops.order.dto.OrderSummaryResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRecordService orderRecords;

    public OrderController(OrderService orderService, OrderRecordService orderRecords) {
        this.orderService = orderService;
        this.orderRecords = orderRecords;
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
