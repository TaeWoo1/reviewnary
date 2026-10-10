package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sellerops.ingest.canonical.ChannelProductRef;
import com.sellerops.product.ChannelProduct;
import com.sellerops.product.ChannelProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 주문이 상품을 알게 되는 유일한 길 — 그리고 그 길 밖의 모든 길이 막혀 있다는 것.
 *
 * <p>이 파일의 테스트는 두 종류다. 하나는 「정확 일치가 작동한다」이고, 나머지 전부는 <b>「다른 방법으로는
 * 연결되지 않는다」</b>다 — 이름, 부분 일치, 다른 채널의 같은 숫자, 후보 중 첫 번째. 연결이 틀리면 판매자가
 * 보는 주문의 상품이 다른 상품이 되고, 그 뒤에 붙는 모든 판단(반복 문제, 개선 제안, 결과 측정)이 그
 * 틀린 상품을 향한다.
 */
class ChannelOrderProductBinderTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private final ChannelOrderProductRepository refs = mock(ChannelOrderProductRepository.class);
    private final ChannelProductRepository listings = mock(ChannelProductRepository.class);
    private final ChannelOrderRepository orders = mock(ChannelOrderRepository.class);
    private final ChannelOrderProductBinder binder =
            new ChannelOrderProductBinder(refs, listings, orders);

    private final List<ChannelOrderProduct> saved = new ArrayList<>();

    @BeforeEach
    void wire() {
        when(refs.findByChannelOrderIdAndExternalProductId(any(), any())).thenReturn(Optional.empty());
        when(refs.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(listings.findByChannelIdAndExternalProductId(any(), any())).thenReturn(Optional.empty());
    }

    private void listingExists(String externalProductId, UUID productId) {
        ChannelProduct listing = new ChannelProduct();
        listing.setProductId(productId);
        when(listings.findByChannelIdAndExternalProductId(CHANNEL, externalProductId))
                .thenReturn(Optional.of(listing));
    }

    @Test
    @DisplayName("정확 일치 하나가 연결을 만들고, 연결 시각이 함께 적힌다")
    void anExactMatchBinds() {
        listingExists("15478536542", PRODUCT);

        ChannelOrderProductBinder.Recorded recorded = binder.record(ORG, CHANNEL, "COUPANG", ORDER,
                List.of(ChannelProductRef.of("15478536542")), ChannelProductRefSource.COUPANG_ORDERSHEETS);

        assertThat(recorded).isEqualTo(new ChannelOrderProductBinder.Recorded(1, 1, 0));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getExternalProductId()).isEqualTo("15478536542");
            assertThat(row.getProductId()).isEqualTo(PRODUCT);
            assertThat(row.getRefSource()).isEqualTo(ChannelProductRefSource.COUPANG_ORDERSHEETS);
            assertThat(row.getBoundAt())
                    .as("연결과 그 시각은 떼어 놓을 수 없다 — 언제 믿기 시작했는지 말할 수 없는 연결은 "
                            + "나중에 되짚을 수 없다")
                    .isNotNull();
            assertThat(row.isUnbound()).isFalse();
        });
    }

    @Test
    @DisplayName("일치하는 상품이 없으면 식별자만 남고 연결되지 않는다 — 그리고 그것이 「없음」과 다르다")
    void noMatchStoresTheIdentifierUnbound() {
        ChannelOrderProductBinder.Recorded recorded = binder.record(ORG, CHANNEL, "COUPANG", ORDER,
                List.of(ChannelProductRef.of("99999999999")), ChannelProductRefSource.COUPANG_ORDERSHEETS);

        // 세 상태가 구별된다: 행 없음(채널이 안 줬다) / 연결 없음(일치가 없다) / 연결됨.
        assertThat(recorded).isEqualTo(new ChannelOrderProductBinder.Recorded(1, 0, 1));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getExternalProductId()).isEqualTo("99999999999");
            assertThat(row.isUnbound()).isTrue();
            assertThat(row.getBoundAt()).isNull();
        });
    }

    @Test
    @DisplayName("채널이 식별자를 주지 않으면 아무 행도 생기지 않는다")
    void anAbsentIdentifierStoresNothing() {
        assertThat(binder.record(ORG, CHANNEL, "COUPANG", ORDER, List.of(ChannelProductRef.absent()),
                ChannelProductRefSource.COUPANG_ORDERSHEETS))
                .isEqualTo(new ChannelOrderProductBinder.Recorded(0, 0, 0));
        assertThat(binder.record(ORG, CHANNEL, "COUPANG", ORDER, List.of(),
                ChannelProductRefSource.COUPANG_ORDERSHEETS))
                .isEqualTo(new ChannelOrderProductBinder.Recorded(0, 0, 0));
        assertThat(binder.record(ORG, CHANNEL, "COUPANG", ORDER, null,
                ChannelProductRefSource.COUPANG_ORDERSHEETS))
                .isEqualTo(new ChannelOrderProductBinder.Recorded(0, 0, 0));
        // 빈 행은 「우리가 못 읽었다」로도 「연결이 아직 안 됐다」로도 읽힐 수 있다. 그래서 만들지 않는다.
        verifyNoInteractions(listings);
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("출처의 채널이 주문의 채널과 다르면 아무것도 저장하지 않는다")
    void aSourceFromAnotherChannelIsRefused() {
        listingExists("15478536542", PRODUCT);

        // NAVER 주문에 쿠팡 출처를 적으면, 그 뒤의 정확 일치는 「우연히 같은 숫자」를 찾는 일이 된다 —
        // 두 채널의 상품번호는 같은 11자리 공간을 쓴다.
        assertThat(binder.record(ORG, CHANNEL, "NAVER", ORDER,
                List.of(ChannelProductRef.of("15478536542")), ChannelProductRefSource.COUPANG_ORDERSHEETS))
                .isEqualTo(new ChannelOrderProductBinder.Recorded(0, 0, 0));
        assertThat(saved).isEmpty();
        assertThat(binder.record(ORG, CHANNEL, null, ORDER,
                List.of(ChannelProductRef.of("15478536542")), ChannelProductRefSource.COUPANG_ORDERSHEETS))
                .as("채널을 이름으로 부를 수 없으면 출처도 맞출 수 없다")
                .isEqualTo(new ChannelOrderProductBinder.Recorded(0, 0, 0));
        assertThat(binder.record(ORG, CHANNEL, "COUPANG", ORDER,
                List.of(ChannelProductRef.of("15478536542")), null))
                .isEqualTo(new ChannelOrderProductBinder.Recorded(0, 0, 0));
    }

    @Test
    @DisplayName("한 묶음에 같은 상품이 두 줄로 들어와도 참조는 하나다")
    void duplicatesInOneBoxCollapse() {
        listingExists("15478536542", PRODUCT);

        // 수량 분리는 정상이다. 이 표는 수량을 세지 않으므로 같은 상품의 두 줄은 한 참조다.
        ChannelOrderProductBinder.Recorded recorded = binder.record(ORG, CHANNEL, "COUPANG", ORDER,
                List.of(ChannelProductRef.of("15478536542"), ChannelProductRef.of("15478536542")),
                ChannelProductRefSource.COUPANG_ORDERSHEETS);

        assertThat(recorded.stored()).isEqualTo(1);
        assertThat(saved).hasSize(1);
    }

    @Test
    @DisplayName("한 묶음의 서로 다른 상품은 각자 한 줄이다 — 하나가 그 주문의 상품이 되지 않는다")
    void severalProductsInOneBoxEachGetARow() {
        listingExists("15478536542", PRODUCT);
        UUID second = UUID.randomUUID();
        listingExists("15421607591", second);

        ChannelOrderProductBinder.Recorded recorded = binder.record(ORG, CHANNEL, "COUPANG", ORDER,
                List.of(ChannelProductRef.of("15478536542"), ChannelProductRef.of("15421607591")),
                ChannelProductRefSource.COUPANG_ORDERSHEETS);

        assertThat(recorded).isEqualTo(new ChannelOrderProductBinder.Recorded(2, 2, 0));
        assertThat(saved).extracting(ChannelOrderProduct::getProductId)
                .containsExactlyInAnyOrder(PRODUCT, second);
    }

    @Test
    @DisplayName("이름으로도, 비슷한 식별자로도 연결되지 않는다")
    void nothingButAnExactIdentifierBinds() {
        listingExists("15478536542", PRODUCT);

        for (String near : List.of("1547853654", "154785365421", " 15478536542", "15478536542 ",
                "선바로 일체형 전선몰딩")) {
            saved.clear();
            binder.record(ORG, CHANNEL, "COUPANG", ORDER, List.of(ChannelProductRef.of(near)),
                    ChannelProductRefSource.COUPANG_ORDERSHEETS);
            assertThat(saved).singleElement().satisfies(row -> assertThat(row.isUnbound())
                    .as("%s — 이름은 판매자가 언제든 바꾸는 값이고, 동명이인 상품이 실제로 있다", near)
                    .isTrue());
        }
        // 그리고 상품 카탈로그에 아무것도 쓰지 않는다 — 주문 수집이 상품을 만들기 시작하면 판매자가
        // 등록하지 않은 상품이 주문에서 태어난다.
        verify(listings, never()).save(any());
        verify(listings, never()).saveAll(any());
    }

    @Test
    @DisplayName("상품이 주문보다 늦게 수집되면 그때 연결된다 — 해소는 다시 돌릴 수 있다")
    void pendingReferencesResolveLater() {
        ChannelOrderProduct pending = new ChannelOrderProduct();
        pending.setOrgId(ORG);
        pending.setChannelOrderId(ORDER);
        pending.setExternalProductId("15478536542");
        pending.setRefSource(ChannelProductRefSource.COUPANG_ORDERSHEETS);
        when(refs.findByOrgIdAndProductIdIsNull(ORG)).thenReturn(List.of(pending));
        ChannelOrder order = new ChannelOrder();
        order.setChannelId(CHANNEL);
        when(orders.findById(ORDER)).thenReturn(Optional.of(order));

        // 상품이 아직 없다.
        assertThat(binder.bindPending(ORG)).isZero();
        assertThat(pending.isUnbound()).isTrue();

        // 상품 수집이 돌았다.
        listingExists("15478536542", PRODUCT);
        assertThat(binder.bindPending(ORG)).isEqualTo(1);
        assertThat(pending.getProductId()).isEqualTo(PRODUCT);
        assertThat(pending.getBoundAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChannelOrderProduct>> captor = ArgumentCaptor.forClass(List.class);
        verify(refs).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(pending);
    }

    @Test
    @DisplayName("이미 연결된 참조는 다시 해소하지 않는다")
    void aBoundReferenceIsNeverRebound() {
        ChannelOrderProduct bound = new ChannelOrderProduct();
        bound.setOrgId(ORG);
        bound.setChannelOrderId(ORDER);
        bound.setExternalProductId("15478536542");
        bound.setRefSource(ChannelProductRefSource.COUPANG_ORDERSHEETS);
        bound.setProductId(PRODUCT);
        bound.setBoundAt(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        when(refs.findByChannelOrderIdAndExternalProductId(ORDER, "15478536542"))
                .thenReturn(Optional.of(bound));
        // 같은 식별자가 이제 다른 상품을 가리킨다 — 상품이 재등록됐거나 카탈로그가 바뀐 경우.
        listingExists("15478536542", UUID.randomUUID());

        binder.record(ORG, CHANNEL, "COUPANG", ORDER, List.of(ChannelProductRef.of("15478536542")),
                ChannelProductRefSource.COUPANG_ORDERSHEETS);

        assertThat(bound.getProductId())
                .as("판매자가 본 적 있는 주문의 상품이 조용히 달라지지 않는다 — DB trigger도 거부한다")
                .isEqualTo(PRODUCT);
        assertThat(bound.getBoundAt()).isEqualTo(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        // 재동기화에서 lastSeenAt은 움직인다 — 그 참조를 여전히 보고 있다는 사실이 정보다.
        assertThat(bound.getLastSeenAt()).isNotNull();
        verify(listings, never()).findByChannelIdAndExternalProductId(eq(CHANNEL), eq("15478536542"));
    }
}
