package com.sellerops.order;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ProductChannels;
import com.sellerops.coverage.ChannelDataState;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.dto.OrderReadStateView;
import com.sellerops.order.dto.OrderRecordExtent;
import com.sellerops.order.dto.OrderRecordListResponse;
import com.sellerops.order.dto.OrderRecordRow;
import com.sellerops.order.fact.OrderStoreFreshness;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 기록 목록 — 결제 단위 레코드와, 그 목록을 읽는 데 먼저 필요한 것들(§4d).
 *
 * <p><b>읽기만 한다.</b> 채널을 부르지 않고, 집계 테이블을 다시 쓰지 않으며, 주문을 처리하지 않는다.
 * 입력은 이 백엔드가 이미 들고 있는 {@code channel_orders} 행과 {@code inquiries}의 주문 참조뿐이다.
 *
 * <p><b>집계는 DB가 하고, 고르지 않는 결정은 여기서 한다.</b> 묶기·합계·정렬은 한 번의 group-by 질의이고
 * (343 결제 단위를 행마다 세는 루프로 만들면 목록 한 장이 수백 번의 질의가 된다), 상태가 여러 가지인
 * 결제 단위에서 하나를 <b>고르지 않는</b> 결정과, 확인하지 않은 코드를 <b>번역하지 않는</b> 결정이
 * 이 클래스의 일이다.
 *
 * <p><b>신선도는 기존 계약을 그대로 쓴다.</b> {@link OrderStoreFreshness}가 {@code ORDER_SUMMARY}
 * 지원 → 연결 → 신선도의 순서로 내리는 판정을 여기서 다시 쓰지 않는다. 그 순서가 안전 속성이고, 두
 * 번째 구현은 그 순서를 틀릴 두 번째 기회다.
 */
@Service
public class OrderRecordService {

    /**
     * 한 응답이 들고 나가는 결제 단위의 상한.
     *
     * <p>목록 화면은 아직 커서를 갖고 있지 않고(C slice), 그동안 응답이 무한히 커지는 것보다 상한이
     * 낫다. 잘렸다는 사실은 숨지 않는다 — {@code extent.paymentUnitCount}는 상한과 무관한 전수이므로
     * 줄 수와 다르면 더 있다는 뜻이다.
     */
    static final int MAX_ROWS = 500;

    private final ChannelOrderRepository orders;
    private final ChannelRepository channels;
    private final SellerAccountRepository accounts;
    private final InquiryRepository inquiries;
    private final OrderStoreFreshness freshness;

    public OrderRecordService(ChannelOrderRepository orders, ChannelRepository channels,
                              SellerAccountRepository accounts, InquiryRepository inquiries,
                              OrderStoreFreshness freshness) {
        this.orders = orders;
        this.channels = channels;
        this.accounts = accounts;
        this.inquiries = inquiries;
        this.freshness = freshness;
    }

    /** 이 org의 결제 단위 기록 — 결제 시각 최신순, 읽은 범위와 함께. */
    @Transactional(readOnly = true)
    public OrderRecordListResponse list(UUID orgId) {
        Map<UUID, String> channelCodes = new HashMap<>();
        for (Channel channel : channels.findAll()) {
            channelCodes.put(channel.getId(), channel.getCode());
        }

        List<OrderRecordRow> rows = new ArrayList<>();
        for (Object[] unit : orders.paymentUnitsByPaidAtDesc(orgId, PageRequest.of(0, MAX_ROWS))) {
            rows.add(row(unit, channelCodes));
        }

        OrderRecordExtent extent = extent(orgId);
        // 부분 응답임을 화면이 두 숫자를 비교해 알아내게 두지 않는다. 같은 읽기 트랜잭션 안의 두 수이므로
        // 이 비교는 여기서 한 번만 옳으면 된다.
        boolean hasMore = rows.size() < extent.paymentUnitCount();
        return new OrderRecordListResponse(extent, reads(orgId, channelCodes), rows, hasMore);
    }

    /** 한 결제 단위 — 상태 두 칸의 규칙이 여기 있다. */
    private static OrderRecordRow row(Object[] unit, Map<UUID, String> channelCodes) {
        UUID channelId = (UUID) unit[0];
        String channelCode = channelCodes.get(channelId);
        long distinctStatuses = ((Number) unit[7]).longValue();
        boolean statusVaries = distinctStatuses > 1;
        // 줄마다 코드가 다르면 결제 단위에는 하나의 상태가 없다. 한 줄의 코드를 올려 적는 것은
        // 일부만 취소된 주문을 취소되지 않은 주문으로 읽히게 하는 길이다.
        String rawStatusCode = statusVaries ? null : (String) unit[8];
        return new OrderRecordRow(channelCode, (UUID) unit[1], (String) unit[2],
                ((Number) unit[3]).intValue(), ((Number) unit[4]).longValue(),
                rawStatusCode, statusVaries,
                // 번역은 뜻을 확인한 (채널, 코드)에만 붙는다. 확인하지 않은 쿠팡 코드는 null로 나가고,
                // 화면이 채널이 보낸 값을 그대로 보여 준다 — 어휘표는 B1의 한 곳뿐이다.
                ChannelOrderStatusVocabulary.labelKo(channelCode, rawStatusCode),
                (Instant) unit[5], (Instant) unit[6]);
    }

    /** 읽어 둔 것의 크기와 기간. 상한과 무관한 전수이고, 행이 없으면 0과 null이다. */
    private OrderRecordExtent extent(UUID orgId) {
        List<Object[]> held = orders.extentOf(orgId);
        Object[] totals = held.isEmpty() ? new Object[] {0L, 0L, null, null} : held.get(0);
        return new OrderRecordExtent(orders.countPaymentUnits(orgId),
                ((Number) totals[0]).longValue(), ((Number) totals[1]).longValue(),
                (LocalDate) totals[2], (LocalDate) totals[3],
                inquiries.countLinkedToStoredOrders(orgId));
    }

    /**
     * 연결 하나당 한 줄의 읽기 상태.
     *
     * <p><b>행이 없는 연결도 줄로 선다.</b> 그 0이 「읽었더니 없었다」인지 「읽지 못했다」인지는 보유
     * 행 수가 아니라 판정이 말한다. 줄을 빼면 화면은 그 채널을 세지 않은 채로 0을 그린다.
     *
     * <p><b>다만 제품이 보여 주지 않는 채널은, 행을 들고 있을 때만 선다.</b> 두 질문이 다르기 때문이다
     * ({@code OrgChannelVisibility}): 연결할 수 있는 채널은 {@code ProductChannels}의 셋이고, 이 org이
     * 실제로 주문 행을 들고 있는 채널은 그와 다를 수 있다(업로드·과거 연결). 보유 행이 0인 비공개 채널을
     * 줄로 세우면 판매자가 연결할 수도 없는 채널의 상태를 읽게 되고, 반대로 행을 들고 있는 채널을 빼면
     * {@code extent}가 센 행의 일부가 어느 읽기에도 속하지 않게 된다.
     */
    private List<OrderReadStateView> reads(UUID orgId, Map<UUID, String> channelCodes) {
        Map<UUID, long[]> lines = new HashMap<>();
        Map<UUID, Instant> seen = new HashMap<>();
        for (Object[] row : orders.countLinesByConnection(orgId)) {
            UUID accountId = (UUID) row[1];
            lines.put(accountId, new long[] {((Number) row[2]).longValue()});
            seen.put(accountId, (Instant) row[3]);
        }

        List<OrderReadStateView> out = new ArrayList<>();
        for (SellerAccount account : accounts.findAllByOrgId(orgId)) {
            String code = channelCodes.get(account.getChannelId());
            long held = lines.containsKey(account.getId()) ? lines.get(account.getId())[0] : 0L;
            if (held == 0 && !ProductChannels.isVisible(code)) {
                continue;
            }
            ChannelDataState state = freshness.perOrderState(orgId, code, account.getId(), held);
            out.add(new OrderReadStateView(code, account.getId(), state.name(), held,
                    seen.get(account.getId())));
        }
        // 제품이 채널을 부르는 순서가 화면의 순서다. 행을 들고 있어서 끼어든 채널이 그 순서를 앞지르지
        // 않도록 뒤에 붙인다 — OrgChannelVisibility가 같은 이유로 같은 규칙을 쓴다.
        out.sort(Comparator.comparingInt((OrderReadStateView r) -> {
            int at = ProductChannels.VISIBLE_CODES.indexOf(r.channelCode());
            return at < 0 ? ProductChannels.VISIBLE_CODES.size() : at;
        }).thenComparing(OrderReadStateView::channelCode,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.accountId().toString()));
        return out;
    }
}
