package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.coverage.ChannelDataState;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryOrderBinding;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.dto.OrderLineView;
import com.sellerops.order.dto.OrderLinkedInquiryView;
import com.sellerops.order.dto.OrderRecordDetailResponse;
import com.sellerops.order.dto.OrderStatusEventView;
import com.sellerops.order.fact.OrderStoreFreshness;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 결제 단위 하나를 여는 길 — 그리고 그 길이 <b>열지 않아야 하는 것</b>.
 *
 * <p>여기서 깨지면 주소를 바꿔 가며 물어보는 쪽에 다른 계정의 주문이 열린다. 같은 주문번호가 두 계정에
 * 있을 수 있고(채널이 번호를 org마다 유일하게 발급하지 않는다), 한 org이 같은 채널에 계정을 둘 가질 수
 * 있으므로, 「채널 + 번호」만 맞춘 조회는 레코드를 하나로 좁히지 못한다 — 좁히지 못한 조회는 「없음」이
 * 아니라 <b>남의 주문</b>이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderRecordDetailServiceTest {

    @Autowired ChannelOrderRepository orders;
    @Autowired ChannelOrderStatusEventRepository events;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired SellerAccountRepository accounts;
    @Autowired InquiryRepository inquiries;
    @Autowired ProductRepository products;

    private UUID org;
    private UUID otherOrg;
    private UUID naverChannel;
    private UUID coupangChannel;
    private UUID naverAccount;
    private UUID secondNaverAccount;
    private UUID coupangAccount;
    private UUID otherOrgAccount;

    /** 판정 자체는 이 테스트의 주제가 아니다 — 응답에 그대로 실려 나가는지만 본다. */
    private final OrderStoreFreshness freshness = (orgId, code, account, rows) ->
            rows > 0 ? ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN : ChannelDataState.ZERO;

    @BeforeEach
    void setUp() {
        org = newOrg("데모 제조사");
        otherOrg = newOrg("다른 상점");
        naverChannel = newChannel("NAVER", "네이버 스마트스토어");
        coupangChannel = newChannel("COUPANG", "쿠팡");
        naverAccount = newAccount(org, naverChannel);
        secondNaverAccount = newAccount(org, naverChannel);
        coupangAccount = newAccount(org, coupangChannel);
        otherOrgAccount = newAccount(otherOrg, naverChannel);
    }

    @Test
    @DisplayName("13줄짜리 결제 단위가 한 레코드로 열린다 — 줄은 줄대로, 합계는 합계대로")
    void thirteenLinesOpenAsOneRecord() {
        for (int i = 0; i < 13; i++) {
            seed(org, naverChannel, naverAccount, "LINE-" + i, "PAY-1", "PAYED", 1000L + i,
                    "2026-09-03T07:24:00Z");
        }

        OrderRecordDetailResponse detail = service().detail(org, "NAVER", naverAccount, "PAY-1");

        assertThat(detail.lineCount()).isEqualTo(13);
        assertThat(detail.totalAmount()).isEqualTo(13 * 1000L + 78L);
        assertThat(detail.lines()).hasSize(13)
                .extracting(OrderLineView::externalOrderId)
                .as("상품주문 번호순 — 서버가 세운 순서를 화면이 다시 세우지 않는다")
                .startsWith("LINE-0", "LINE-1", "LINE-10");
        assertThat(detail.statusVaries()).isFalse();
        assertThat(detail.rawStatusCode()).isEqualTo("PAYED");
        assertThat(detail.paidAt()).isEqualTo(Instant.parse("2026-09-03T07:24:00Z"));
        assertThat(detail.readState()).isEqualTo(ChannelDataState.OBSERVED_FRESHNESS_UNPROVEN.name());
    }

    @Test
    @DisplayName("네이버의 PAYED만 결제 완료로 확인됐고, 취소·배송은 확인되지 않는다")
    void onlyNaverPayedIsConfirmedAndTheOtherTwoAxesStayUnknown() {
        seed(org, naverChannel, naverAccount, "L-1", "PAY-1", "PAYED", 7500L, "2026-09-03T07:24:00Z");

        OrderRecordDetailResponse detail = service().detail(org, "NAVER", naverAccount, "PAY-1");

        assertThat(detail.paymentLabelKo()).isEqualTo("결제 완료");
        assertThat(detail.cancellationLabelKo())
                .as("저장된 행은 「취소되지 않았습니다」를 증명하지 못한다 — null은 「확인되지 않음」이다")
                .isNull();
        assertThat(detail.fulfillmentLabelKo()).isNull();
        assertThat(detail.lines().get(0).confirmedStatusLabelKo()).isEqualTo("결제 완료");
    }

    @Test
    @DisplayName("쿠팡이 보낸 코드는 번역되지 않는다 — 같은 PAYED라도")
    void coupangCodesAreNeverTranslated() {
        seed(org, coupangChannel, coupangAccount, "C-1", "CPAY-1", "DELIVERING", 11000L,
                "2026-08-21T15:53:00Z");
        seed(org, coupangChannel, coupangAccount, "C-2", "CPAY-2", "PAYED", 11000L,
                "2026-08-21T15:53:00Z");

        OrderRecordDetailResponse delivering = service().detail(org, "COUPANG", coupangAccount, "CPAY-1");
        assertThat(delivering.rawStatusCode()).isEqualTo("DELIVERING");
        assertThat(delivering.paymentLabelKo()).isNull();
        assertThat(delivering.cancellationLabelKo()).isNull();
        assertThat(delivering.fulfillmentLabelKo()).isNull();
        assertThat(delivering.lines().get(0).confirmedStatusLabelKo()).isNull();

        OrderRecordDetailResponse payed = service().detail(org, "COUPANG", coupangAccount, "CPAY-2");
        assertThat(payed.paymentLabelKo())
                .as("글자가 같다는 것은 확인이 아니다 — 확인의 단위는 (채널, 코드)다")
                .isNull();
    }

    @Test
    @DisplayName("줄마다 코드가 다르면 결제 단위에는 하나의 상태가 없다")
    void aUnitWhoseLinesDisagreeHasNoSingleStatus() {
        seed(org, naverChannel, naverAccount, "L-1", "PAY-1", "PAYED", 1000L, "2026-09-03T07:24:00Z");
        seed(org, naverChannel, naverAccount, "L-2", "PAY-1", "CANCELED", 1000L, "2026-09-03T07:24:00Z");

        OrderRecordDetailResponse detail = service().detail(org, "NAVER", naverAccount, "PAY-1");

        assertThat(detail.statusVaries()).isTrue();
        assertThat(detail.rawStatusCode()).isNull();
        assertThat(detail.paymentLabelKo())
                .as("한 줄의 코드를 올려 적으면 일부만 취소된 주문이 결제 완료로 읽힌다")
                .isNull();
        assertThat(detail.lines()).extracting(OrderLineView::rawStatusCode)
                .containsExactly("PAYED", "CANCELED");
    }

    @Test
    @DisplayName("같은 전이는 한 줄이고, 채널이 변경 시각을 주지 않으면 그대로 비어 있다")
    void oneTransitionIsOneRowAndAMissingObservedAtStaysMissing() {
        for (int i = 0; i < 13; i++) {
            UUID lineId = seed(org, naverChannel, naverAccount, "LINE-" + i, "PAY-1", "PAYED", 1000L,
                    "2026-09-03T07:24:00Z");
            // 13줄이 한 번에 결제됐다 — 같은 전이 13건, 기록 시각만 밀리초씩 다르다.
            event(org, lineId, null, "PAYED", Instant.parse("2026-09-03T07:24:41Z"),
                    Instant.parse("2026-09-04T12:24:09Z").plusMillis(i * 2L));
        }
        UUID coupangLine = seed(org, coupangChannel, coupangAccount, "C-1", "CPAY-1", "DELIVERING",
                11000L, "2026-08-21T15:53:00Z");
        event(org, coupangLine, null, "ACCEPT", null, Instant.parse("2026-08-22T17:15:00Z"));
        event(org, coupangLine, "ACCEPT", "INSTRUCT", null, Instant.parse("2026-08-23T01:43:00Z"));

        var naver = service().detail(org, "NAVER", naverAccount, "PAY-1").statusHistory();
        assertThat(naver).hasSize(1);
        assertThat(naver.get(0).lineCount())
                .as("한 번 일어난 일이 13번 일어난 것으로 읽히지 않게 — 몇 줄이 겪었는지는 수로 남는다")
                .isEqualTo(13);
        assertThat(naver.get(0).fromStatusCode()).isNull();
        assertThat(naver.get(0).toLabelKo()).isEqualTo("결제 완료");
        assertThat(naver.get(0).observedAt()).isEqualTo(Instant.parse("2026-09-03T07:24:41Z"));
        assertThat(naver.get(0).recordedAt())
                .as("한 묶음의 기록 시각은 그 가운데 가장 이른 것 — 같은 질의가 날마다 다른 값을 말하지 않도록")
                .isEqualTo(Instant.parse("2026-09-04T12:24:09Z"));

        var coupang = service().detail(org, "COUPANG", coupangAccount, "CPAY-1").statusHistory();
        assertThat(coupang).hasSize(2);
        assertThat(coupang).extracting(OrderStatusEventView::toStatusCode)
                .as("오래된 순")
                .containsExactly("ACCEPT", "INSTRUCT");
        assertThat(coupang).allSatisfy(e -> {
            assertThat(e.observedAt())
                    .as("채널이 변경 시각을 주지 않았다 — 기록한 시각을 그 자리에 적지 않는다")
                    .isNull();
            assertThat(e.toLabelKo()).isNull();
        });
    }

    @Test
    @DisplayName("채널이 주문번호를 적어 보낸 문의만 연결된다 — 결제 단위 번호로도, 상품주문 번호로도")
    void onlyAChannelSuppliedReferenceLinksAnInquiry() {
        seed(org, naverChannel, naverAccount, "LINE-A", "PAY-1", "PAYED", 1000L, "2026-09-03T07:24:00Z");
        seed(org, naverChannel, naverAccount, "LINE-B", "PAY-1", "PAYED", 2000L, "2026-09-03T07:24:00Z");
        UUID product = newProduct(org, "선바로 일체형 전선몰딩");
        seedInquiry(org, naverChannel, naverAccount, "LINE-B", InquiryOrderBinding.SOURCE_EXACT, product);
        seedInquiry(org, naverChannel, naverAccount, "PAY-1", InquiryOrderBinding.SOURCE_EXACT, null);
        // 같은 번호를 본문에서 뽑은 것으로 들어온 행 — 울타리 밖이다.
        seedInquiry(org, naverChannel, naverAccount, "LINE-A", null, null);

        var linked = service().detail(org, "NAVER", naverAccount, "PAY-1").inquiries();

        assertThat(linked).hasSize(2)
                .extracting(OrderLinkedInquiryView::sourceOrderRef)
                .containsExactlyInAnyOrder("LINE-B", "PAY-1");
        assertThat(linked).filteredOn(q -> "LINE-B".equals(q.sourceOrderRef()))
                .singleElement()
                .satisfies(q -> {
                    assertThat(q.productName()).isEqualTo("선바로 일체형 전선몰딩");
                    assertThat(q.status()).isEqualTo("UNANSWERED");
                    assertThat(q.channelCode()).isEqualTo("NAVER");
                });
    }

    @Test
    @DisplayName("같은 번호라도 다른 계정의 주문은 열리지 않는다 — 보충 없이 없음")
    void theSameNumberOnAnotherAccountDoesNotOpen() {
        seed(org, naverChannel, secondNaverAccount, "L-1", "SAME-NUMBER", "PAYED", 5000L,
                "2026-09-03T07:24:00Z");

        // 계정이 다르다 — 그 계정의 행으로 보충되지 않는다.
        assertThatThrownBy(() -> service().detail(org, "NAVER", naverAccount, "SAME-NUMBER"))
                .isInstanceOf(ApiException.class);
        // 채널이 다르다 — 번호는 채널 사이에서 유일하지 않다.
        assertThatThrownBy(() -> service().detail(org, "COUPANG", secondNaverAccount, "SAME-NUMBER"))
                .isInstanceOf(ApiException.class);
        // 계정이 그 채널의 것이 아니다 — 두 조건을 따로 통과시키면 여기가 뚫린다.
        assertThatThrownBy(() -> service().detail(org, "COUPANG", naverAccount, "SAME-NUMBER"))
                .isInstanceOf(ApiException.class);
        // 맞는 계정으로는 열린다.
        assertThat(service().detail(org, "NAVER", secondNaverAccount, "SAME-NUMBER").totalAmount())
                .isEqualTo(5000L);
    }

    @Test
    @DisplayName("다른 org의 계정으로는 열리지 않는다")
    void anotherOrgsRecordIsNotReachable() {
        seed(otherOrg, naverChannel, otherOrgAccount, "L-1", "PAY-X", "PAYED", 9000L,
                "2026-09-03T07:24:00Z");

        assertThatThrownBy(() -> service().detail(org, "NAVER", otherOrgAccount, "PAY-X"))
                .as("계정 id를 알아도 org이 다르면 없다")
                .isInstanceOf(ApiException.class);
        assertThat(service().detail(otherOrg, "NAVER", otherOrgAccount, "PAY-X").lineCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("없는 채널 코드·없는 계정·없는 번호는 모두 같은 없음이다")
    void everyMissingPieceIsTheSameAbsence() {
        seed(org, naverChannel, naverAccount, "L-1", "PAY-1", "PAYED", 1000L, "2026-09-03T07:24:00Z");

        assertThatThrownBy(() -> service().detail(org, "GMARKET", naverAccount, "PAY-1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("주문을 찾지 못했습니다.");
        assertThatThrownBy(() -> service().detail(org, "NAVER", UUID.randomUUID(), "PAY-1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("주문을 찾지 못했습니다.");
        assertThatThrownBy(() -> service().detail(org, "NAVER", naverAccount, "NO-SUCH-ORDER"))
                .as("네 가지 다른 문장은 주소를 바꿔 묻는 쪽에 「이 계정은 있다」를 알려 준다")
                .isInstanceOf(ApiException.class)
                .hasMessage("주문을 찾지 못했습니다.");
    }

    private OrderRecordDetailService service() {
        return new OrderRecordDetailService(orders, events, channels, accounts, inquiries, products,
                freshness);
    }

    private UUID newOrg(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    private UUID newChannel(String code, String nameKo) {
        Channel channel = new Channel();
        channel.setCode(code);
        channel.setNameKo(nameKo);
        channel.setStatus(ChannelStatus.CONNECTED);
        return channels.save(channel).getId();
    }

    private UUID newAccount(UUID orgId, UUID channelId) {
        SellerAccount account = new SellerAccount();
        account.setOrgId(orgId);
        account.setChannelId(channelId);
        account.setAlias("테스트 계정");
        account.setConnectionStatus(ChannelStatus.CONNECTED);
        return accounts.save(account).getId();
    }

    private UUID newProduct(UUID orgId, String name) {
        Product product = new Product();
        product.setOrgId(orgId);
        product.setName(name);
        product.setStatus("SELLING");
        return products.save(product).getId();
    }

    private UUID seed(UUID orgId, UUID channelId, UUID accountId, String externalOrderId,
                      String parentOrderId, String rawStatus, long amount, String paidAt) {
        ChannelOrder order = new ChannelOrder();
        order.setOrgId(orgId);
        order.setSellerAccountId(accountId);
        order.setChannelId(channelId);
        order.setExternalOrderId(externalOrderId);
        order.setParentOrderId(parentOrderId);
        order.setRawStatusCode(rawStatus);
        order.setNormalizedStatus(NormalizedOrderStatus.fromRaw(
                channels.findById(channelId).orElseThrow().getCode(), rawStatus));
        order.setPaymentAmount(amount);
        order.setSummaryDate(Instant.parse(paidAt).atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
        order.setPaidAt(Instant.parse(paidAt));
        order.setFirstSeenAt(Instant.parse(paidAt));
        order.setLastSeenAt(Instant.parse(paidAt).plusSeconds(3600));
        return orders.save(order).getId();
    }

    private void event(UUID orgId, UUID channelOrderId, String from, String to, Instant observedAt,
                       Instant recordedAt) {
        ChannelOrderStatusEvent e = new ChannelOrderStatusEvent();
        e.setOrgId(orgId);
        e.setChannelOrderId(channelOrderId);
        e.setFromStatusCode(from);
        e.setToStatusCode(to);
        e.setObservedAt(observedAt);
        e.setRecordedAt(recordedAt);
        events.save(e);
    }

    private void seedInquiry(UUID orgId, UUID channelId, UUID accountId, String orderRef,
                             InquiryOrderBinding binding, UUID productId) {
        Inquiry q = new Inquiry();
        q.setOrgId(orgId);
        q.setChannelId(channelId);
        q.setSellerAccountId(accountId);
        q.setProductId(productId);
        q.setTitle("언제 발송하나요?");
        q.setBody("아직 배송이 안되네요 언제 발송 되나요?");
        q.setStatus("UNANSWERED");
        q.setReceivedAt(Instant.parse("2026-09-04T15:00:00Z"));
        q.setSourceOrderRef(orderRef);
        q.setOrderBinding(binding == null ? null : binding.name());
        inquiries.save(q);
    }
}
