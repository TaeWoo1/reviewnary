package com.sellerops.order;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * 주문 한 행이 가리키는 상품 참조 하나 — 채널이 준 식별자와, 증명되었을 때의 canonical 연결.
 *
 * <p>왜 주문 행의 컬럼이 아니라 이 표인지, 그리고 세 상태(행 없음 / 연결 없음 / 연결됨)가 왜 구별되어야
 * 하는지는 {@code V132__channel_order_product_ref.sql}에 적혀 있다.
 *
 * <p><b>Privacy:</b> 구매자 식별자·수량·금액·옵션·상품명을 담지 않는다. 상품명은 판매자가 언제든 바꾸는
 * 값이고 이미 {@code channel_products.channel_product_name}에 있다 — 여기 복사하면 그 사본이 드리프트한다.
 */
@Getter
@Setter
@Entity
@Table(name = "channel_order_products")
public class ChannelOrderProduct extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "channel_order_id", nullable = false)
    private UUID channelOrderId;

    /** 채널이 준 식별자, 그대로. DB trigger가 수정을 거부한다. */
    @Column(name = "external_product_id", nullable = false)
    private String externalProductId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_source", nullable = false)
    private ChannelProductRefSource refSource;

    /** 정확 일치로만 채워진다. null은 「일치 없음」이고 「상품이 없다」가 아니다. */
    @Column(name = "product_id")
    private UUID productId;

    /**
     * <b>우리가 연결한 시각</b>이다. 채널이 말한 시각이 아니다 (D3) — 이 표에는 채널이 준 시각이
     * 하나도 없고, 그래서 이름에 {@code observed}/{@code bound}만 쓴다.
     */
    @Column(name = "bound_at")
    private Instant boundAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** 식별자는 받았고 canonical 상품에는 아직 닿지 않은 상태. */
    public boolean isUnbound() {
        return productId == null;
    }
}
