package com.sellerops.order;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChannelOrderStatusEventRepository extends JpaRepository<ChannelOrderStatusEvent, UUID> {

    // Scoped by org as well as the order id — the event table carries org_id, so a reader can never
    // reach another org's history even if it somehow held a foreign channel_order_id.
    List<ChannelOrderStatusEvent> findAllByOrgIdAndChannelOrderIdOrderByRecordedAtAsc(
            UUID orgId, UUID channelOrderId);

    long countByOrgIdAndChannelOrderId(UUID orgId, UUID channelOrderId);

    /**
     * 한 결제 단위의 상태 이력 — {@code [fromStatusCode, toStatusCode, observedAt, min(recordedAt),
     * lineCount]}, 기록한 시각 오래된 순.
     *
     * <p><b>같은 전이는 한 줄이다.</b> 열세 줄짜리 주문이 한 번 결제되면 이 표에는 같은 전이가 열세 개
     * 쌓인다. 그대로 그리면 한 번 일어난 일이 열세 번 일어난 것으로 읽히므로, 묶음의 키는 (이전 코드,
     * 다음 코드, 채널이 말한 시각)이고 몇 줄이 그 전이를 겪었는지는 수로 나간다 — 결제 단위의 일부만
     * 움직인 경우에 그 사실이 지워지지 않도록.
     *
     * <p>{@code recordedAt}은 그 묶음에서 가장 이른 것이다. 열세 행의 기록 시각은 수십 밀리초씩 다르고,
     * 그 가운데 아무것이나 고르면 같은 질의가 날마다 다른 시각을 말할 수 있다.
     */
    @Query("select e.fromStatusCode, e.toStatusCode, e.observedAt, min(e.recordedAt), count(e) "
            + "from ChannelOrderStatusEvent e "
            + "where e.orgId = :orgId and e.channelOrderId in :orderIds "
            + "group by e.fromStatusCode, e.toStatusCode, e.observedAt "
            + "order by min(e.recordedAt) asc")
    List<Object[]> historyOfPaymentUnit(@Param("orgId") UUID orgId,
                                        @Param("orderIds") List<UUID> orderIds);
}
