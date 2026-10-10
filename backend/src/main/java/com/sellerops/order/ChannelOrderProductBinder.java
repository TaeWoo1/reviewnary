package com.sellerops.order;

import com.sellerops.ingest.canonical.ChannelProductRef;
import com.sellerops.product.ChannelProduct;
import com.sellerops.product.ChannelProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문의 상품 참조를 보관하고, <b>증명될 때만</b> canonical 상품에 연결한다.
 *
 * <p><b>연결의 유일한 근거는 정확 일치다.</b> {@code channel_products (channel_id, external_product_id)}
 * — {@link ChannelProductRef}가 문의 귀속에서 이미 선언해 둔 규칙 그대로다. 이름으로 다시 시도하지
 * 않고, placeholder 상품을 만들지 않고, 후보가 여럿이면 첫 후보를 고르지 않는다(정확 일치의 양쪽이
 * unique index이므로 후보는 0개 또는 1개다). 동명이인 상품이 canonical Demo Org에 실제로 있고, 이름은
 * 판매자가 언제든 바꾸는 값이다.
 *
 * <p><b>이 클래스는 상품을 만들지도 바꾸지도 않는다.</b> {@link ChannelProductRepository}는 조회에만
 * 쓰이고, 저장은 {@link ChannelOrderProductRepository} 하나로만 간다 —
 * {@code OrderContextFenceTest}가 source 위에서 그것을 단정한다. 주문 수집이 상품 카탈로그를 쓰기
 * 시작하면 판매자가 등록하지 않은 상품이 주문에서 태어난다.
 *
 * <p><b>해소는 다시 돌릴 수 있다.</b> 주문이 상품보다 먼저 수집되는 것은 사고가 아니라 흔한 순서다
 * (주문 routine은 60분, 상품 수집은 그보다 드물다). 그래서 식별자는 연결 없이도 저장되고,
 * {@link #bindPending}이 나중에 같은 정확 일치를 다시 시도한다. 반대로 <b>한 번 연결된 것은 다시
 * 쓰이지 않는다</b> — DB trigger가 거부하고, 이 클래스도 시도하지 않는다.
 */
@Service
public class ChannelOrderProductBinder {

    private static final Logger log = LoggerFactory.getLogger(ChannelOrderProductBinder.class);

    private final ChannelOrderProductRepository refs;
    private final ChannelProductRepository listings;
    private final ChannelOrderRepository orders;

    public ChannelOrderProductBinder(ChannelOrderProductRepository refs, ChannelProductRepository listings,
                                     ChannelOrderRepository orders) {
        this.refs = refs;
        this.listings = listings;
        this.orders = orders;
    }

    /** 한 주문에 대해 기록된 결과 — 보고용 수치이고, 셋이 서로 다른 작업으로 이어진다. */
    public record Recorded(int stored, int bound, int unbound) {

        static final Recorded NONE = new Recorded(0, 0, 0);
    }

    /**
     * 채널이 이 주문에 준 상품 식별자들을 보관하고, 가능한 것을 연결한다.
     *
     * @param channelCode 주문이 속한 채널. {@code source}의 채널과 다르면 <b>아무것도 저장하지 않는다</b>
     *     — 식별자 공간이 다르면 그 뒤의 정확 일치는 「우연히 같은 숫자」를 찾는 일이 된다
     * @param productRefs 채널이 준 참조들. 식별자가 없는 ref({@link ChannelProductRef#absent()})는
     *     모순이 아니라 「이 source는 식별자로 귀속하는데 이 행에는 식별자가 없다」이고, 답은 무귀속이다
     */
    @Transactional
    public Recorded record(UUID orgId, UUID channelId, String channelCode, UUID channelOrderId,
                           List<ChannelProductRef> productRefs, ChannelProductRefSource source) {
        if (productRefs == null || productRefs.isEmpty() || source == null) {
            return Recorded.NONE;
        }
        if (channelCode == null || !channelCode.equals(source.channelCode())) {
            // 조용히 지나가지 않는다. 이것은 데이터의 모양이 아니라 배선 실수이고, 배선 실수는 한 번
            // 일어나면 그 채널의 모든 주문에서 일어난다.
            log.warn("상품 참조 출처의 채널이 주문의 채널과 다릅니다 — 저장하지 않습니다 (주문 채널={}, 출처={})",
                    channelCode, source);
            return Recorded.NONE;
        }
        // 같은 묶음에 같은 상품이 두 줄로 들어오는 것은 정상이다(수량 분리). 참조는 하나다.
        Set<String> distinct = new LinkedHashSet<>();
        for (ChannelProductRef ref : productRefs) {
            if (ref != null && ref.hasIdentifier()) {
                distinct.add(ref.externalProductId());
            }
        }
        if (distinct.isEmpty()) {
            return Recorded.NONE;
        }
        Instant now = Instant.now();
        int stored = 0;
        int bound = 0;
        int unbound = 0;
        for (String externalProductId : distinct) {
            ChannelOrderProduct row = refs
                    .findByChannelOrderIdAndExternalProductId(channelOrderId, externalProductId)
                    .orElse(null);
            if (row == null) {
                row = new ChannelOrderProduct();
                row.setOrgId(orgId);
                row.setChannelOrderId(channelOrderId);
                row.setExternalProductId(externalProductId);
                row.setRefSource(source);
                row.setFirstSeenAt(now);
                stored++;
            }
            row.setLastSeenAt(now);
            if (row.isUnbound()) {
                UUID productId = resolve(channelId, externalProductId);
                if (productId != null) {
                    row.setProductId(productId);
                    row.setBoundAt(now);
                }
            }
            refs.save(row);
            if (row.isUnbound()) {
                unbound++;
            } else {
                bound++;
            }
        }
        return new Recorded(stored, bound, unbound);
    }

    /**
     * 아직 연결되지 않은 참조를 다시 해소한다 — 상품이 주문보다 늦게 수집된 경우.
     *
     * <p>조직 단위이고 멱등이다. 연결된 행은 건드리지 않으므로 trigger와 다툴 일이 없다.
     *
     * @return 이번에 새로 연결된 참조의 수
     */
    @Transactional
    public int bindPending(UUID orgId) {
        List<ChannelOrderProduct> pending = refs.findByOrgIdAndProductIdIsNull(orgId);
        if (pending.isEmpty()) {
            return 0;
        }
        // 주문의 채널을 알아야 정확 일치를 할 수 있다. 참조는 주문에 매달려 있으므로 주문을 한 번 읽는다.
        Instant now = Instant.now();
        int newlyBound = 0;
        List<ChannelOrderProduct> changed = new ArrayList<>();
        for (ChannelOrderProduct row : pending) {
            UUID channelId = channelOf(row);
            if (channelId == null) {
                continue;
            }
            UUID productId = resolve(channelId, row.getExternalProductId());
            if (productId != null) {
                row.setProductId(productId);
                row.setBoundAt(now);
                changed.add(row);
                newlyBound++;
            }
        }
        if (!changed.isEmpty()) {
            refs.saveAll(changed);
        }
        return newlyBound;
    }

    /** 주문의 채널. 주문 저장소는 조회에만 쓰이고 이 클래스는 주문을 저장하지 않는다. */
    private UUID channelOf(ChannelOrderProduct row) {
        return orders.findById(row.getChannelOrderId()).map(ChannelOrder::getChannelId).orElse(null);
    }

    /**
     * 정확 일치 하나, 또는 null.
     *
     * <p>{@code ChannelProduct}에는 {@code RealDataOnly} 필터가 걸려 있다 — 합성(DEMO_SEED) 상품이
     * 실제 주문의 상품으로 답하지 않는다. 그 필터를 여기서 끄지 않는 것이 이 메서드의 절반이다.
     */
    private UUID resolve(UUID channelId, String externalProductId) {
        Optional<ChannelProduct> listing =
                listings.findByChannelIdAndExternalProductId(channelId, externalProductId);
        return listing.map(ChannelProduct::getProductId).orElse(null);
    }
}
