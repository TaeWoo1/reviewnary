package com.sellerops.order.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 이 주문 줄이 가리키는 상품 하나 — 채널이 준 식별자와, <b>증명되었을 때의</b> canonical 연결.
 *
 * <p><b>세 상태가 서로 구별되는 것이 이 record의 존재 이유다.</b>
 *
 * <ul>
 *   <li>이 줄의 목록이 <b>비어 있다</b> — 채널이 이 주문에 상품 식별자를 주지 않았다.</li>
 *   <li>항목이 있고 {@link #productId}가 {@code null} — 식별자는 받았고 일치하는 canonical 상품이 없다.
 *       판매자가 상품을 아직 수집하지 않았거나, 그 상품이 이 채널에 없다.</li>
 *   <li>{@link #productId}가 있다 — 증명된 연결. {@code channel_products (channel_id,
 *       external_product_id)} 정확 일치 하나.</li>
 * </ul>
 *
 * <p>셋을 하나로 접으면 「우리가 못 읽었다」와 「채널이 안 줬다」와 「연결이 아직 안 됐다」가 같은
 * 빈칸이 되고, 셋은 서로 다른 작업으로 이어진다 — 수집 범위를 넓히는 일, 아무것도 하지 않는 일,
 * 상품을 수집하는 일.
 *
 * <p><b>이름은 canonical 상품의 것이다.</b> 채널이 준 상품명을 여기 적지 않는다 — 판매자가 언제든
 * 바꾸는 값이고 이미 {@code channel_products.channel_product_name}에 있다. 연결되지 않은 참조에는
 * 이름이 없고, 그것이 「무엇인지 모른다」의 정확한 표현이다.
 *
 * @param refSource 식별자가 어느 endpoint의 어느 필드에서 왔는지 — {@code ChannelProductRefSource}의 이름
 * @param boundAt   <b>우리가 연결한 시각.</b> 채널이 말한 시각이 아니다 — 이 record에 채널이 준 시각은
 *                  하나도 없다 (D3)
 */
public record OrderLineProductView(String externalProductId,
                                   UUID productId,
                                   String productName,
                                   String refSource,
                                   Instant boundAt) {

    public boolean isBound() {
        return productId != null;
    }
}
