package com.sellerops.order;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.ApiException;
import com.sellerops.coverage.ChannelDataState;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.dto.OrderLineProductView;
import com.sellerops.order.dto.OrderLineView;
import com.sellerops.order.dto.OrderLinkedInquiryView;
import com.sellerops.order.dto.OrderRecordDetailResponse;
import com.sellerops.order.dto.OrderStatusEventView;
import com.sellerops.order.fact.OrderStoreFreshness;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 단위 하나를 여는 읽기 — {@code GET /api/orders/{channelCode}/{accountId}/{parentOrderId}}.
 *
 * <p><b>정체성이 네 조각이고, 셋만 맞으면 열리지 않는다</b>(§4d). org은 인증 context에서 오고, 채널·계정·
 * 주문번호는 주소에서 온다. 계정이 빠졌거나 다른 org의 계정을 가리키면 이 서비스는 다른 계정의 행으로
 * <b>보충하지 않고</b> 그 자리에서 없음을 돌려준다. 주문번호는 채널 사이에서 유일하지 않고 한 org이 같은
 * 채널에 계정을 둘 가질 수 있으므로, 좁히지 못한 조회는 「없음」이 아니라 다른 사람의 주문을 열 수 있는
 * 조회다 — 그래서 fail closed가 기본값이다.
 *
 * <p><b>없는 이유를 구별해 말하지 않는다.</b> 채널 코드가 틀렸는지, 계정이 남의 것인지, 주문이 없는지는
 * 모두 같은 404다. 네 가지 다른 문장은 주소를 바꿔 가며 물어보는 쪽에게 「이 계정은 존재한다」를 알려
 * 주는 신호가 된다.
 *
 * <p><b>읽기만 한다.</b> 채널을 부르지 않고(라이브 조회는 {@code ExactOrderReader}의 일이고 그 경로는
 * 문의 초안이 부른다), 아무것도 쓰지 않으며, 주문 처리(WRITE) 액션을 위한 값은 하나도 내보내지 않는다.
 */
@Service
public class OrderRecordDetailService {

    private final ChannelOrderRepository orders;
    private final ChannelOrderStatusEventRepository events;
    private final ChannelRepository channels;
    private final SellerAccountRepository accounts;
    private final InquiryRepository inquiries;
    private final ProductRepository products;
    private final OrderStoreFreshness freshness;

    public OrderRecordDetailService(ChannelOrderRepository orders,
                                    ChannelOrderStatusEventRepository events,
                                    ChannelRepository channels, SellerAccountRepository accounts,
                                    InquiryRepository inquiries, ProductRepository products,
                                    OrderStoreFreshness freshness) {
        this.orders = orders;
        this.events = events;
        this.channels = channels;
        this.accounts = accounts;
        this.inquiries = inquiries;
        this.products = products;
        this.freshness = freshness;
    }

    /**
     * 주문의 상품 참조 저장소 (Order Context Foundation v1, D1).
     *
     * <p>optional인 것이 정직한 모양이다 — 이 상세는 상품 참조 없이 완전했고, 배선하지 않은 context는
     * 모든 줄의 상품 목록이 비어 있는 제품, 즉 이 arc 이전의 제품이다.
     */
    private ChannelOrderProductRepository orderProducts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOrderProducts(ChannelOrderProductRepository orderProducts) {
        this.orderProducts = orderProducts;
    }

    /**
     * 이 결제 단위의 모든 줄에 대한 상품 참조 — 줄 id로 묶어서 <b>쿼리 두 번</b>.
     *
     * <p>줄마다 읽으면 열세 줄짜리 주문이 열세 번(+이름 열세 번) 읽는다. 상태 이력과 문의가 같은 이유로
     * 이미 배치로 읽고 있고, 세 번째가 혼자 다른 규칙을 쓸 이유가 없다.
     */
    private Map<UUID, List<OrderLineProductView>> productsOf(UUID orgId, List<ChannelOrder> lines) {
        if (orderProducts == null) {
            return Map.of();
        }
        List<ChannelOrderProduct> refs = orderProducts.findByOrgIdAndChannelOrderIdIn(
                orgId, lines.stream().map(ChannelOrder::getId).toList());
        if (refs.isEmpty()) {
            return Map.of();
        }
        List<UUID> bound = refs.stream().map(ChannelOrderProduct::getProductId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (!bound.isEmpty()) {
            // 이름은 canonical 상품의 것이다. org를 조건에 두는 것은 이 service의 다른 읽기와 같은
            // 규칙이고, 연결이 다른 org의 상품을 가리킬 수 없다는 두 번째 울타리다.
            for (Product product : products.findAllByOrgIdAndIdIn(orgId, bound)) {
                names.put(product.getId(), product.getName());
            }
        }
        Map<UUID, List<OrderLineProductView>> out = new HashMap<>();
        for (ChannelOrderProduct ref : refs) {
            out.computeIfAbsent(ref.getChannelOrderId(), k -> new ArrayList<>())
                    .add(new OrderLineProductView(ref.getExternalProductId(), ref.getProductId(),
                            ref.getProductId() == null ? null : names.get(ref.getProductId()),
                            ref.getRefSource().name(), ref.getBoundAt()));
        }
        return out;
    }

    /** 이 org의, 이 채널의, 이 계정의, 이 번호의 결제 단위. 넷 중 하나라도 어긋나면 404. */
    @Transactional(readOnly = true)
    public OrderRecordDetailResponse detail(UUID orgId, String channelCode, UUID accountId,
                                            String parentOrderId) {
        Channel channel = channels.findByCode(channelCode).orElseThrow(OrderRecordDetailService::absent);
        SellerAccount account = accounts.findByIdAndOrgId(accountId, orgId)
                .orElseThrow(OrderRecordDetailService::absent);
        // 계정이 그 채널의 것인지까지 본다. 두 조건을 따로 통과시키면 「내 계정 id + 남의 채널 코드」가
        // 묶음 키를 통과해 다른 채널의 같은 번호를 열 수 있다.
        if (!channel.getId().equals(account.getChannelId())) {
            throw absent();
        }
        List<ChannelOrder> lines = orders.findPaymentUnit(orgId, channel.getId(), accountId, parentOrderId);
        if (lines.isEmpty()) {
            throw absent();
        }

        long total = 0;
        Instant paidAt = null;
        Instant lastSeenAt = null;
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        List<OrderLineView> lineViews = new ArrayList<>(lines.size());
        Map<UUID, List<OrderLineProductView>> lineProducts = productsOf(orgId, lines);
        for (ChannelOrder line : lines) {
            total += line.getPaymentAmount();
            paidAt = later(paidAt, line.getPaidAt());
            lastSeenAt = later(lastSeenAt, line.getLastSeenAt());
            codes.add(line.getRawStatusCode());
            lineViews.add(new OrderLineView(line.getExternalOrderId(), line.getPaymentAmount(),
                    line.getRawStatusCode(),
                    ChannelOrderStatusVocabulary.labelKo(channelCode, line.getRawStatusCode()),
                    line.getPaidAt(),
                    lineProducts.getOrDefault(line.getId(), List.of())));
        }
        // 줄마다 코드가 다르면 결제 단위에는 하나의 상태가 없다 — 한 줄의 코드를 올려 적으면 일부만
        // 취소된 주문이 취소되지 않은 주문으로 읽힌다. 목록이 같은 규칙을 쓴다.
        boolean statusVaries = codes.size() > 1;
        String rawStatusCode = statusVaries ? null : codes.iterator().next();
        ChannelOrderStatusVocabulary.Axes axes =
                ChannelOrderStatusVocabulary.axesFromStored(channelCode, rawStatusCode);

        long held = orders.countByOrgIdAndSellerAccountId(orgId, accountId);
        ChannelDataState state = freshness.perOrderState(orgId, channelCode, accountId, held);

        return new OrderRecordDetailResponse(channelCode, accountId, parentOrderId,
                lines.size(), total, rawStatusCode, statusVaries,
                axes.payment().labelKo(), axes.cancellation().labelKo(), axes.fulfillment().labelKo(),
                paidAt, lastSeenAt, state.name(),
                lineViews, history(orgId, channelCode, lines), linked(orgId, accountId, parentOrderId, lines));
    }

    /** 이 결제 단위가 겪은 전이들 — 같은 전이는 한 줄, 오래된 순. */
    private List<OrderStatusEventView> history(UUID orgId, String channelCode, List<ChannelOrder> lines) {
        List<UUID> ids = lines.stream().map(ChannelOrder::getId).toList();
        List<OrderStatusEventView> out = new ArrayList<>();
        for (Object[] row : events.historyOfPaymentUnit(orgId, ids)) {
            String from = (String) row[0];
            String to = (String) row[1];
            out.add(new OrderStatusEventView(from,
                    ChannelOrderStatusVocabulary.labelKo(channelCode, from), to,
                    ChannelOrderStatusVocabulary.labelKo(channelCode, to),
                    // 채널이 변경 시각을 주지 않았으면 그대로 null이다. 기록한 시각을 그 자리에 적는
                    // 것은 「채널이 이때 바꿨다」는 말을 지어내는 것이다.
                    (Instant) row[2], (Instant) row[3], ((Number) row[4]).intValue()));
        }
        return out;
    }

    /** 채널이 이 주문의 번호를 적어 보낸 문의들. 본문에서 번호를 뽑는 경로는 없다. */
    private List<OrderLinkedInquiryView> linked(UUID orgId, UUID accountId, String parentOrderId,
                                                List<ChannelOrder> lines) {
        // 채널은 주문을 두 이름으로 부른다 — 결제 단위 번호와 상품주문 번호. 둘 다 물어야 한쪽으로 적어
        // 보낸 문의가 조용히 사라지지 않는다.
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        refs.add(parentOrderId);
        for (ChannelOrder line : lines) {
            refs.add(line.getExternalOrderId());
        }
        List<Inquiry> found = inquiries.findLinkedToOrderRefs(orgId, accountId, List.copyOf(refs));
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> channelCodes = new HashMap<>();
        Map<UUID, String> productNames = new HashMap<>();
        List<OrderLinkedInquiryView> out = new ArrayList<>(found.size());
        for (Inquiry inquiry : found) {
            String code = channelCodes.computeIfAbsent(inquiry.getChannelId(),
                    id -> channels.findById(id).map(Channel::getCode).orElse(null));
            UUID productId = inquiry.getProductId();
            String productName = productId == null ? null
                    : productNames.computeIfAbsent(productId,
                            id -> products.findById(id).map(Product::getName).orElse(null));
            out.add(new OrderLinkedInquiryView(inquiry.getId(), code, inquiry.getTitle(),
                    inquiry.getBody(), inquiry.getStatus(), inquiry.getReceivedAt(),
                    inquiry.getSourceOrderRef(), productId, productName));
        }
        return out;
    }

    private static Instant later(Instant held, Instant candidate) {
        if (candidate == null) {
            return held;
        }
        return held == null || candidate.isAfter(held) ? candidate : held;
    }

    /** 네 가지 어긋남이 모두 같은 문장이다 — 다른 문장은 주소를 바꿔 묻는 쪽에게 답을 준다. */
    private static ApiException absent() {
        return ApiException.notFound("주문을 찾지 못했습니다.");
    }
}
