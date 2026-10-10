package com.sellerops.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelOrderProductRepository extends JpaRepository<ChannelOrderProduct, UUID> {

    Optional<ChannelOrderProduct> findByChannelOrderIdAndExternalProductId(
            UUID channelOrderId, String externalProductId);

    List<ChannelOrderProduct> findByChannelOrderId(UUID channelOrderId);

    /** 상세 화면 한 번에 결제 단위의 모든 줄의 참조를 — 줄마다 한 번 읽으면 줄 수만큼 쿼리가 된다. */
    List<ChannelOrderProduct> findByOrgIdAndChannelOrderIdIn(UUID orgId, Collection<UUID> channelOrderIds);

    /**
     * 아직 연결되지 않은 참조들 — 상품이 나중에 수집되면 그때 연결하기 위해.
     *
     * <p>해소는 다시 돌릴 수 있어야 한다. 주문이 상품보다 먼저 수집되는 것은 사고가 아니라 흔한
     * 순서이고(주문 routine은 60분, 상품 수집은 그보다 드물다), 그때 한 번 실패한 연결이 영구적이면
     * 그 주문은 상품을 영원히 모른다.
     */
    List<ChannelOrderProduct> findByOrgIdAndProductIdIsNull(UUID orgId);
}
