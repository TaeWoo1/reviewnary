package com.sellerops.order;

/**
 * 주문의 상품 식별자가 <b>어느 endpoint의 어느 필드</b>에서 왔는지 — 닫힌 어휘.
 *
 * <p><b>왜 자유 문자열이 아닌가.</b> provenance의 값은 「어디서 왔는지 적어 두자」는 좋은 의도로 시작해
 * 두 달 뒤 세 가지 표기가 섞인 열이 된다({@code naver-order}, {@code NAVER/query},
 * {@code productOrder.productId}). 그러면 「이 식별자는 믿을 만한 자리에서 왔나」가 문자열 비교 문제가
 * 되고, 그 비교는 틀린다. 닫힌 어휘는 그 질문을 컴파일 시점으로 옮긴다.
 *
 * <p><b>채널이 값의 일부다.</b> {@link #channelCode()}가 있는 이유는
 * {@code ChannelOrderStatusVocabulary}가 (채널, 코드)를 키로 쓰는 것과 같다 — NAVER의 식별자 공간과
 * 쿠팡의 식별자 공간은 다른 공간이고, 한쪽의 출처가 다른 쪽 주문에 적히면 그 뒤의 정확 일치는
 * 「우연히 같은 숫자」를 찾는 일이 된다. {@code ChannelOrderProductBinder}가 저장 전에 채널을 맞춘다.
 *
 * <p><b>필드 경로는 문서에서 옮겨 적는다.</b> 기억으로 적은 경로는 응답이 바뀐 날 조용히 null이 되고,
 * null은 이 설계에서 「채널이 주지 않았다」로 읽힌다 — 즉 버그가 정상 동작으로 보고된다. 그래서 경로가
 * 여기 적혀 있고, 각 줄이 어떤 문서/관측에 기대고 있는지도 적혀 있다.
 */
public enum ChannelProductRefSource {

    /**
     * NAVER 상품주문 상세 조회의 채널상품번호.
     *
     * <p><b>추가 호출이 0이다.</b> 이 저장소는 금액(initialPaymentAmount) 때문에 이미 이 endpoint를
     * 배치로 POST하고 응답의 나머지를 버린다({@code NaverOrdersClient.detailAmounts}). 식별자는 그
     * 버려지던 응답 안에 있다.
     *
     * <p><b>미확인 — 그리고 설계가 그것을 견딘다.</b> vendored 문서
     * ({@code docs/vendor/naver-commerce-api/get-v1-pay-order-seller-product-orders.md})는 응답 하위
     * 구조를 「상세는 OAS 참조」로 생략한다. 공식 문서는
     * {@code https://apicenter.commerce.naver.com/llms/post-v1-pay-order-seller-product-orders-query.md}에
     * 있지만 이 개발 환경에서 그 호스트에 도달할 수 없다. 경로는 커머스API 운영자가 같은 패턴으로 답한
     * 다른 필드({@code data[n].productOrder.logisticsCompanyId}, GitHub discussion #3466)와 사용자
     * 보고(#1637: 「채널 상품 번호(data.productOrder.productId)」)에 기댄다.
     *
     * <p>그래서 이 줄은 <b>틀려도 안전하다</b>: 필드가 없으면 null이고, null은 참조 없음이고, 참조
     * 없음은 무귀속이다. 라이브 한 번이 있는지 없는지를 답하고, 있으면 그 자리에서 연결이 생긴다.
     */
    NAVER_PRODUCT_ORDER_QUERY("NAVER",
            "POST /external/v1/pay-order/seller/product-orders/query",
            "data[].productOrder.productId"),

    /**
     * 쿠팡 ordersheets 목록의 {@code sellerProductId}.
     *
     * <p>{@code channel_products.external_product_id}가 쿠팡에서 담고 있는 값이 바로
     * {@code sellerProductId}이므로({@code COUPANG:SELLER_PRODUCTS:v1}로 수집된 68행, 11자리) 정확
     * 일치의 양쪽이 같은 공간이다.
     *
     * <p><b>미관측.</b> 이 저장소의 ordersheets fixture에는 {@code orderItems[].orderPrice} 하나뿐이고
     * ({@code CoupangApiConnectorTest}), 2026-08-06 라이브 proof는 금액·상태만 확인했다. NAVER와 같은
     * 이유로 틀려도 안전하며, {@code CoupangWireShapeObserver}가 값을 하나도 기록하지 않고 키의
     * 존재·충전율만 답할 수 있다.
     */
    COUPANG_ORDERSHEETS("COUPANG",
            "GET /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets",
            "data[].orderItems[].sellerProductId");

    private final String channelCode;
    private final String endpoint;
    private final String fieldPath;

    ChannelProductRefSource(String channelCode, String endpoint, String fieldPath) {
        this.channelCode = channelCode;
        this.endpoint = endpoint;
        this.fieldPath = fieldPath;
    }

    /** 이 출처가 속한 채널. 다른 채널의 주문에 적히면 저장되지 않는다. */
    public String channelCode() {
        return channelCode;
    }

    public String endpoint() {
        return endpoint;
    }

    /** 문서에서 옮겨 적은 필드 경로. 감사와 wire 관측이 같은 문자열을 본다. */
    public String fieldPath() {
        return fieldPath;
    }

    /**
     * 이 채널의 주문 식별자 출처, 또는 {@code null} — 그 채널의 주문 수집이 상품 식별자를 투영하지
     * 않는다는 뜻이다.
     *
     * <p><b>채널당 하나인 것이 오늘의 사실이고 영원한 규칙은 아니다.</b> 한 채널이 두 endpoint에서
     * 식별자를 얻게 되는 날 이 메서드는 어느 하나를 고를 수 없게 되고, 그때 호출자가 출처를 직접
     * 건네는 쪽으로 바뀌어야 한다 — 그 날 조용히 틀린 하나를 고르지 않도록 여기에 적어 둔다.
     */
    public static ChannelProductRefSource forChannel(String channelCode) {
        if (channelCode == null) {
            return null;
        }
        for (ChannelProductRefSource source : values()) {
            if (source.channelCode.equals(channelCode)) {
                return source;
            }
        }
        return null;
    }
}
