package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.coverage.ChannelDataState;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryOrderBinding;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.order.dto.OrderReadStateView;
import com.sellerops.order.dto.OrderRecordListResponse;
import com.sellerops.order.dto.OrderRecordRow;
import com.sellerops.order.fact.OrderStoreFreshness;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 한 줄이 하나의 결제 단위라는 것 — 그리고 그 줄이 말하지 않는 것들.
 *
 * <p>여기서 깨지면 목록은 13줄짜리 주문 하나를 13건으로 세고, 확인하지 않은 영어 토큰을 우리 말로
 * 번역하며, 수집이 멈춘 채널을 「주문 0건」으로 그린다. 세 가지 모두 화면의 흠이 아니라 판매자에게
 * 잘못된 숫자를 보여 주는 일이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderRecordServiceTest {

    @Autowired ChannelOrderRepository orders;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired SellerAccountRepository accounts;
    @Autowired InquiryRepository inquiries;

    private UUID org;
    private UUID otherOrg;
    private UUID naverChannel;
    private UUID coupangChannel;
    private UUID naverAccount;
    private UUID coupangAccount;
    private UUID esmChannel;
    private UUID esmAccount;
    private UUID otherOrgAccount;
    private String naverCode;
    private String coupangCode;

    /** 판정은 이 테스트의 주제가 아니다 — 전달되는지만 본다. */
    private OrderStoreFreshness freshness = (orgId, code, account, rows) ->
            rows > 0 ? ChannelDataState.OBSERVED_FRESH : ChannelDataState.ZERO;

    @BeforeEach
    void setUp() {
        org = newOrg("데모 제조사");
        otherOrg = newOrg("다른 상점");
        // 제품이 보여 주는 코드 그대로 — 가시성 규칙(ProductChannels)이 이 테스트의 대상이다.
        naverCode = "NAVER";
        coupangCode = "COUPANG";
        naverChannel = newChannel(naverCode, "네이버");
        coupangChannel = newChannel(coupangCode, "쿠팡");
        esmChannel = newChannel("GMARKET", "지마켓");
        naverAccount = newAccount(org, naverChannel);
        coupangAccount = newAccount(org, coupangChannel);
        esmAccount = newAccount(org, esmChannel);
        otherOrgAccount = newAccount(otherOrg, naverChannel);
    }

    @Test
    @DisplayName("같은 결제 단위의 여러 줄은 한 줄로 집계된다 — 건수도 금액도")
    void linesOfOnePaymentUnitBecomeOneRow() {
        for (int i = 0; i < 13; i++) {
            seed(org, naverChannel, naverAccount, "LINE-" + i, "PAY-1", "PAYED", 1000L + i,
                    "2026-09-03T01:00:00Z");
        }

        OrderRecordListResponse response = service().list(org);

        assertThat(response.rows()).hasSize(1);
        OrderRecordRow row = response.rows().get(0);
        assertThat(row.parentOrderId()).isEqualTo("PAY-1");
        assertThat(row.lineCount()).isEqualTo(13);
        assertThat(row.totalAmount())
                .as("13줄의 합 — 결제 단위의 금액은 그 안의 상품주문 금액의 합이다")
                .isEqualTo(13 * 1000L + 78L);
        assertThat(row.channelCode()).isEqualTo(naverCode);
        assertThat(row.accountId()).isEqualTo(naverAccount);
        assertThat(response.extent().paymentUnitCount()).isEqualTo(1);
        assertThat(response.extent().orderLineCount())
                .as("결제 몇 번과 상품주문 몇 줄은 다른 수이고, 둘 다 적는다")
                .isEqualTo(13);
        assertThat(response.extent().totalAmount()).isEqualTo(13 * 1000L + 78L);
    }

    @Test
    @DisplayName("같은 주문번호라도 채널이 다르면 다른 레코드다")
    void identityIncludesTheChannel() {
        seed(org, naverChannel, naverAccount, "N-1", "SAME-NUMBER", "PAYED", 5000L,
                "2026-09-03T01:00:00Z");
        seed(org, coupangChannel, coupangAccount, "C-1", "SAME-NUMBER", "ACCEPT", 7000L,
                "2026-09-02T01:00:00Z");

        OrderRecordListResponse response = service().list(org);

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows()).extracting(OrderRecordRow::channelCode)
                .containsExactly(naverCode, coupangCode);
        assertThat(response.rows()).allSatisfy(row -> assertThat(row.lineCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("정렬은 서버의 것이다 — 결제 시각 최신순")
    void orderedByPaidAtDescending() {
        seed(org, naverChannel, naverAccount, "L-OLD", "PAY-OLD", "PAYED", 1000L,
                "2026-08-01T01:00:00Z");
        seed(org, naverChannel, naverAccount, "L-NEW", "PAY-NEW", "PAYED", 1000L,
                "2026-09-30T01:00:00Z");
        seed(org, naverChannel, naverAccount, "L-MID", "PAY-MID", "PAYED", 1000L,
                "2026-09-01T01:00:00Z");

        List<OrderRecordRow> rows = service().list(org).rows();

        assertThat(rows).extracting(OrderRecordRow::parentOrderId)
                .containsExactly("PAY-NEW", "PAY-MID", "PAY-OLD");
    }

    @Test
    @DisplayName("뜻을 확인한 코드에만 우리 말이 붙는다 — 쿠팡 여섯 중 넷")
    void onlyConfirmedCodesAreTranslated() {
        // 2026-10-10(Order Context Foundation v1, D2)에 이 테스트가 다시 쓰였다. 그 전에는 「쿠팡 raw
        // 코드는 번역되지 않는다」를 고정했고, 어휘표가 관측과 계약을 둘 다 얻는 날 깨지도록 되어
        // 있었다 — 그리고 깨졌다. 규율은 그대로다: 번역되는 것은 확인된 것뿐이고, 확인되지 않은 둘은
        // 여전히 raw로 남는다. 어느 코드가 왜 확인되었는지는 ChannelOrderStatusVocabularyTest의 것이고,
        // 여기서는 목록 화면이 그 표를 지나간다는 것만 본다.
        seed(org, naverChannel, naverAccount, "N-1", "PAY-N", "PAYED", 1000L, "2026-09-30T01:00:00Z");
        Map<String, String> coupang = new LinkedHashMap<>();
        coupang.put("ACCEPT", "결제 완료");
        coupang.put("INSTRUCT", "발송 준비 중");
        coupang.put("DELIVERING", "배송 중");
        coupang.put("FINAL_DELIVERY", "배송 완료");
        coupang.put("DEPARTURE", null);
        coupang.put("NONE_TRACKING", null);
        List<String> codes = List.copyOf(coupang.keySet());
        for (int i = 0; i < codes.size(); i++) {
            seed(org, coupangChannel, coupangAccount, "C-" + i, "PAY-C-" + i, codes.get(i), 1000L,
                    "2026-09-0" + (i + 1) + "T01:00:00Z");
        }

        List<OrderRecordRow> rows = service().list(org).rows();

        OrderRecordRow naver = rows.stream().filter(r -> "PAY-N".equals(r.parentOrderId())).findFirst()
                .orElseThrow();
        assertThat(naver.rawStatusCode()).isEqualTo("PAYED");
        assertThat(naver.confirmedStatusLabelKo()).isEqualTo("결제 완료");
        for (OrderRecordRow row : rows.stream().filter(r -> coupangCode.equals(r.channelCode())).toList()) {
            assertThat(row.confirmedStatusLabelKo())
                    .as("%s — 확인되지 않은 코드는 번역되지 않고 화면이 raw 값을 그대로 쓴다",
                            row.rawStatusCode())
                    .isEqualTo(coupang.get(row.rawStatusCode()));
            assertThat(row.rawStatusCode()).isIn(codes);
            assertThat(row.statusVaries()).isFalse();
        }
    }

    @Test
    @DisplayName("줄마다 상태가 다른 결제 단위는 하나의 상태를 갖지 않는다")
    void aMixedPaymentUnitHasNoSingleStatus() {
        seed(org, coupangChannel, coupangAccount, "C-1", "PAY-MIX", "DELIVERING", 1000L,
                "2026-09-03T01:00:00Z");
        seed(org, coupangChannel, coupangAccount, "C-2", "PAY-MIX", "FINAL_DELIVERY", 2000L,
                "2026-09-03T01:00:00Z");

        OrderRecordRow row = service().list(org).rows().get(0);

        assertThat(row.lineCount()).isEqualTo(2);
        assertThat(row.statusVaries()).isTrue();
        assertThat(row.rawStatusCode())
                .as("한 줄의 코드를 올려 적으면 일부만 취소된 주문이 취소되지 않은 주문으로 읽힌다")
                .isNull();
        assertThat(row.confirmedStatusLabelKo()).isNull();
    }

    @Test
    @DisplayName("연결된 문의는 그 번호가 실제로 저장된 주문을 가리킬 때만 세어진다")
    void onlyInquiriesPointingAtAStoredOrderCount() {
        seed(org, naverChannel, naverAccount, "LINE-A", "PAY-1", "PAYED", 1000L, "2026-09-03T01:00:00Z");
        // 저장된 주문을 가리키는 것 — 줄 번호로, 그리고 결제 단위 번호로. 채널이 둘 다 보낸다.
        seedInquiry(org, naverChannel, naverAccount, "LINE-A", InquiryOrderBinding.SOURCE_EXACT);
        seedInquiry(org, naverChannel, naverAccount, "PAY-1", InquiryOrderBinding.SOURCE_EXACT);
        // 가리키는 주문이 저장소에 없다 — 목록의 어느 줄에도 걸리지 않는 연결이다.
        seedInquiry(org, naverChannel, naverAccount, "NOT-STORED", InquiryOrderBinding.SOURCE_EXACT);
        // 번호가 없다.
        seedInquiry(org, naverChannel, naverAccount, null, null);
        // 번호는 있지만 채널이 그렇게 말한 것이 아니다.
        seedInquiry(org, naverChannel, naverAccount, "LINE-A", null);

        assertThat(service().list(org).extent().linkedInquiryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 org의 주문은 줄로도 숫자로도 새지 않는다")
    void orgIsolation() {
        seed(org, naverChannel, naverAccount, "MINE", "PAY-MINE", "PAYED", 1000L, "2026-09-03T01:00:00Z");
        seed(otherOrg, naverChannel, otherOrgAccount, "THEIRS", "PAY-THEIRS", "PAYED", 999_000L,
                "2026-09-30T01:00:00Z");
        seedInquiry(otherOrg, naverChannel, otherOrgAccount, "THEIRS", InquiryOrderBinding.SOURCE_EXACT);

        OrderRecordListResponse response = service().list(org);

        assertThat(response.rows()).extracting(OrderRecordRow::parentOrderId)
                .containsExactly("PAY-MINE");
        assertThat(response.extent().paymentUnitCount()).isEqualTo(1);
        assertThat(response.extent().orderLineCount()).isEqualTo(1);
        assertThat(response.extent().totalAmount()).isEqualTo(1000L);
        assertThat(response.extent().linkedInquiryCount()).isZero();
        assertThat(response.reads()).extracting(OrderReadStateView::accountId)
                .containsExactlyInAnyOrder(naverAccount, coupangAccount);
    }

    @Test
    @DisplayName("읽은 기간은 보유한 행의 기간이고, 한 줄도 없으면 null이다")
    void theExtentCarriesTheRangeItRead() {
        OrderRecordListResponse empty = service().list(org);
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.extent().paymentUnitCount()).isZero();
        assertThat(empty.extent().totalAmount()).isZero();
        assertThat(empty.extent().periodFrom()).isNull();
        assertThat(empty.extent().periodTo()).isNull();

        seed(org, naverChannel, naverAccount, "L-1", "PAY-1", "PAYED", 1000L, "2026-08-01T01:00:00Z");
        seed(org, naverChannel, naverAccount, "L-2", "PAY-2", "PAYED", 1000L, "2026-09-30T01:00:00Z");

        OrderRecordListResponse response = service().list(org);
        assertThat(response.extent().periodFrom()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(response.extent().periodTo()).isEqualTo(LocalDate.parse("2026-09-30"));
    }

    @Test
    @DisplayName("0건과 「읽지 못함」은 섞이지 않는다 — 행이 없는 연결도 줄로 선다")
    void zeroAndUnreadAreDifferentRows() {
        seed(org, naverChannel, naverAccount, "L-1", "PAY-1", "PAYED", 1000L, "2026-09-03T01:00:00Z");
        freshness = (orgId, code, account, rows) -> code.equals(coupangCode)
                ? ChannelDataState.BLOCKED
                : ChannelDataState.OBSERVED_FRESH;

        List<OrderReadStateView> reads = service().list(org).reads();

        assertThat(reads).hasSize(2);
        OrderReadStateView naver = reads.stream().filter(r -> naverCode.equals(r.channelCode()))
                .findFirst().orElseThrow();
        OrderReadStateView coupang = reads.stream().filter(r -> coupangCode.equals(r.channelCode()))
                .findFirst().orElseThrow();
        assertThat(naver.state()).isEqualTo("OBSERVED_FRESH");
        assertThat(naver.orderLineCount()).isEqualTo(1);
        assertThat(naver.lastSeenAt()).isNotNull();
        assertThat(coupang.state())
                .as("주문 0건이 아니라 「읽지 못했다」 — 판정은 기존 ChannelCoverageService 계약의 것이다")
                .isEqualTo("BLOCKED");
        assertThat(coupang.orderLineCount()).isZero();
        assertThat(coupang.lastSeenAt()).isNull();
    }

    @Test
    @DisplayName("같은 PAYED라도 쿠팡이 보낸 것은 번역되지 않는다")
    void payedFromAnotherChannelIsNotTranslated() {
        seed(org, naverChannel, naverAccount, "N-1", "PAY-N", "PAYED", 1000L, "2026-09-03T01:00:00Z");
        seed(org, coupangChannel, coupangAccount, "C-1", "PAY-C", "PAYED", 1000L,
                "2026-09-02T01:00:00Z");

        List<OrderRecordRow> rows = service().list(org).rows();

        OrderRecordRow naver = rows.stream().filter(r -> naverCode.equals(r.channelCode()))
                .findFirst().orElseThrow();
        OrderRecordRow coupang = rows.stream().filter(r -> coupangCode.equals(r.channelCode()))
                .findFirst().orElseThrow();
        assertThat(naver.confirmedStatusLabelKo()).isEqualTo("결제 완료");
        assertThat(coupang.rawStatusCode()).isEqualTo("PAYED");
        assertThat(coupang.confirmedStatusLabelKo())
                .as("뜻을 확인한 것은 NAVER의 PAYED 하나이고, 글자가 같다는 것은 확인이 아니다")
                .isNull();
    }

    @Test
    @DisplayName("부분 응답은 스스로 그렇다고 말한다 — 화면이 두 숫자를 비교해 알아내지 않는다")
    void aTruncatedResponseSaysSo() {
        OrderRecordListResponse small = service().list(org);
        assertThat(small.hasMore()).as("한 줄도 없을 때 더 있다고 말하지 않는다").isFalse();

        seed(org, naverChannel, naverAccount, "L-1", "PAY-1", "PAYED", 1000L, "2026-09-03T01:00:00Z");
        assertThat(service().list(org).hasMore()).isFalse();

        List<ChannelOrder> many = new java.util.ArrayList<>();
        for (int i = 0; i < OrderRecordService.MAX_ROWS; i++) {
            many.add(orderOf(org, naverChannel, naverAccount, "B-" + i, "PAY-B-" + i, "PAYED", 100L,
                    "2026-09-04T01:00:00Z"));
        }
        orders.saveAll(many);

        OrderRecordListResponse response = service().list(org);
        assertThat(response.rows()).hasSize(OrderRecordService.MAX_ROWS);
        assertThat(response.extent().paymentUnitCount())
                .as("머리 숫자는 상한과 무관한 전수다")
                .isEqualTo(OrderRecordService.MAX_ROWS + 1L);
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    @DisplayName("제품이 보여 주지 않는 채널은 행을 들고 있을 때만 읽기 줄로 선다")
    void aNonProductChannelAppearsOnlyWhenItHoldsRows() {
        seed(org, naverChannel, naverAccount, "L-1", "PAY-1", "PAYED", 1000L, "2026-09-03T01:00:00Z");

        assertThat(service().list(org).reads()).extracting(OrderReadStateView::channelCode)
                .as("연결할 수도 없는 채널의 0건을 판매자에게 읽히게 하지 않는다")
                .containsExactly(naverCode, coupangCode);

        // 업로드나 과거 연결로 행이 들어온 채널은 다르다 — 빼면 extent가 센 행의 일부가 어느 읽기에도
        // 속하지 않는다.
        seed(org, esmChannel, esmAccount, "G-1", "PAY-G", "PAYED", 2000L, "2026-09-04T01:00:00Z");

        OrderRecordListResponse response = service().list(org);
        assertThat(response.reads()).extracting(OrderReadStateView::channelCode)
                .as("제품이 채널을 부르는 순서가 먼저, 끼어든 채널은 뒤")
                .containsExactly("NAVER", "COUPANG", "GMARKET");
        assertThat(response.reads().stream().filter(r -> "GMARKET".equals(r.channelCode()))
                .findFirst().orElseThrow().orderLineCount()).isEqualTo(1);
        assertThat(response.extent().orderLineCount()).isEqualTo(2);
    }

    private OrderRecordService service() {
        return new OrderRecordService(orders, channels, accounts, inquiries, freshness);
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

    private void seed(UUID orgId, UUID channelId, UUID accountId, String externalOrderId,
                      String parentOrderId, String rawStatus, long amount, String paidAt) {
        orders.save(orderOf(orgId, channelId, accountId, externalOrderId, parentOrderId, rawStatus,
                amount, paidAt));
    }

    private ChannelOrder orderOf(UUID orgId, UUID channelId, UUID accountId, String externalOrderId,
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
        order.setSummaryDate(Instant.parse(paidAt).atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toLocalDate());
        order.setPaidAt(Instant.parse(paidAt));
        order.setFirstSeenAt(Instant.parse(paidAt));
        order.setLastSeenAt(Instant.parse(paidAt).plusSeconds(3600));
        return order;
    }

    private void seedInquiry(UUID orgId, UUID channelId, UUID accountId, String orderRef,
                             InquiryOrderBinding binding) {
        Inquiry q = new Inquiry();
        q.setOrgId(orgId);
        q.setChannelId(channelId);
        q.setSellerAccountId(accountId);
        q.setTitle("주문 문의");
        q.setBody("확인 부탁드립니다.");
        q.setStatus("UNANSWERED");
        q.setReceivedAt(Instant.parse("2026-09-04T00:00:00Z"));
        q.setSourceOrderRef(orderRef);
        q.setOrderBinding(binding == null ? null : binding.name());
        inquiries.save(q);
    }
}
