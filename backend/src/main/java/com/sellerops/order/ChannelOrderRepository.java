package com.sellerops.order;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelOrderRepository extends JpaRepository<ChannelOrder, UUID> {

    /** Identity lookup — scoped by org AND account so a row can never be matched across the boundary. */
    Optional<ChannelOrder> findByOrgIdAndSellerAccountIdAndExternalOrderId(
            UUID orgId, UUID sellerAccountId, String externalOrderId);

    boolean existsByOrgIdAndSellerAccountIdAndExternalOrderId(
            UUID orgId, UUID sellerAccountId, String externalOrderId);

    List<ChannelOrder> findAllByOrgIdAndSellerAccountId(UUID orgId, UUID sellerAccountId);

    List<ChannelOrder> findAllByOrgIdAndChannelIdAndSummaryDate(
            UUID orgId, UUID channelId, LocalDate summaryDate);

    /**
     * The exact order this inquiry's reference names, matched on EITHER identifier space.
     *
     * <p>Two spaces because channels name orders twice. NAVER's inquiry resource returns a required
     * {@code orderId} (the payment unit, stored here as {@code parentOrderId}) and an optional
     * {@code productOrderIdList} (the per-line unit, stored as {@code externalOrderId}); Cafe24's
     * board article carries only the payment-unit {@code order_id}. A join written against one column
     * silently answers "not found" for every source that names the other.
     *
     * <p>Ordered so a payment-unit reference that spans several lines resolves the same way twice —
     * the caller refuses a multi-row match rather than picking from it, and a stable order is what
     * makes that refusal reproducible rather than racy.
     */
    @org.springframework.data.jpa.repository.Query(
            "select o from ChannelOrder o where o.orgId = :orgId and o.sellerAccountId = :accountId "
            + "and (o.externalOrderId = :ref or o.parentOrderId = :ref) "
            + "order by o.externalOrderId asc")
    List<ChannelOrder> findAllByReference(
            @org.springframework.data.repository.query.Param("orgId") UUID orgId,
            @org.springframework.data.repository.query.Param("accountId") UUID accountId,
            @org.springframework.data.repository.query.Param("ref") String reference);

    /**
     * 결제 단위 하나가 한 줄 — {@code [channelId, accountId, parentOrderId, lineCount, totalAmount,
     * paidAt, lastSeenAt, distinctStatusCount, anyStatusCode, summaryDate]}, 결제 시각 최신순.
     *
     * <p><b>묶음의 키가 세 개인 이유.</b> 주문번호는 채널 사이에서 유일하지 않고, 한 org이 같은 채널에
     * 계정을 둘 가질 수 있다. 번호만으로 묶으면 다른 채널의 결제가 한 줄로 합쳐진다.
     *
     * <p><b>{@code coalesce}가 있는 이유.</b> {@code parent_order_id}는 nullable이다 — 채널이 결제
     * 단위를 주지 않은 행은 그 줄 자체가 결제 단위이고, null끼리 묶으면 서로 무관한 주문 전부가 한
     * 줄로 합쳐진다.
     *
     * <p><b>상태는 「몇 가지인가」와 함께 나온다.</b> 줄마다 다른 코드를 가진 결제 단위에서 하나를 골라
     * 전체의 상태로 적으면 일부만 취소된 주문이 취소되지 않은 주문으로 읽힌다. 세는 일은 DB가 하고,
     * 고르지 않는 결정은 서비스가 한다.
     */
    @org.springframework.data.jpa.repository.Query(
            "select o.channelId, o.sellerAccountId, coalesce(o.parentOrderId, o.externalOrderId), "
            + "count(o), sum(o.paymentAmount), max(o.paidAt), max(o.lastSeenAt), "
            + "count(distinct o.rawStatusCode), min(o.rawStatusCode), max(o.summaryDate) "
            + "from ChannelOrder o where o.orgId = :orgId "
            + "group by o.channelId, o.sellerAccountId, coalesce(o.parentOrderId, o.externalOrderId) "
            + "order by max(o.paidAt) desc nulls last, "
            + "coalesce(o.parentOrderId, o.externalOrderId) desc")
    List<Object[]> paymentUnitsByPaidAtDesc(
            @org.springframework.data.repository.query.Param("orgId") UUID orgId,
            org.springframework.data.domain.Pageable page);

    /** 보유 전수 — {@code [orderLineCount, totalAmount, min(summaryDate), max(summaryDate)]}. 행이 없으면 모두 0/null. */
    @org.springframework.data.jpa.repository.Query(
            "select count(o), coalesce(sum(o.paymentAmount), 0), min(o.summaryDate), max(o.summaryDate) "
            + "from ChannelOrder o where o.orgId = :orgId")
    List<Object[]> extentOf(@org.springframework.data.repository.query.Param("orgId") UUID orgId);

    /**
     * 결제 단위의 전수. {@link #paymentUnitsByPaidAtDesc}의 상한과 무관해야 하므로 따로 센다 —
     * 목록이 잘렸을 때 위에 적히는 건수까지 잘리면, 「더 있다」가 「숫자가 틀렸다」로 읽힌다.
     */
    @org.springframework.data.jpa.repository.Query(value =
            "select count(*) from (select 1 from channel_orders where org_id = :orgId "
            + "group by channel_id, seller_account_id, "
            + "coalesce(parent_order_id, external_order_id)) u", nativeQuery = true)
    long countPaymentUnits(@org.springframework.data.repository.query.Param("orgId") UUID orgId);

    /**
     * 연결 하나당 보유 행 수와 마지막으로 본 시각 — {@code [channelId, accountId, count, max(lastSeenAt)]}.
     *
     * <p>신선도 판정({@code OrderStoreFreshness})의 입력이다. 행이 없는 연결은 여기 나오지 않고, 그
     * 0은 호출자가 채운다 — 「읽지 않았다」와 「없었다」를 세는 쪽이 구분할 수 없기 때문이다.
     */
    @org.springframework.data.jpa.repository.Query(
            "select o.channelId, o.sellerAccountId, count(o), max(o.lastSeenAt) from ChannelOrder o "
            + "where o.orgId = :orgId group by o.channelId, o.sellerAccountId")
    List<Object[]> countLinesByConnection(
            @org.springframework.data.repository.query.Param("orgId") UUID orgId);

    /** Per-channel order counts and the newest summary date — {@code [channelId, count, max(summaryDate)]}. */
    @org.springframework.data.jpa.repository.Query(
            "select o.channelId, count(o), max(o.summaryDate) from ChannelOrder o "
            + "where o.orgId = :orgId group by o.channelId")
    List<Object[]> countByChannel(@org.springframework.data.repository.query.Param("orgId") UUID orgId);
}
