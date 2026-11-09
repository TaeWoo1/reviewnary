package com.sellerops.connector.naver;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.ChannelRepository;
import com.sellerops.community.Cafe24CommunityArticleRepository;
import com.sellerops.connector.FetchPage;
import com.sellerops.ingest.IngestionService;
import com.sellerops.ingest.canonical.CanonicalInquiry;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemAuditRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemWriter;
import com.sellerops.order.OrderDailySummaryRepository;
import com.sellerops.product.ChannelProductRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductService;
import com.sellerops.review.ReviewRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * <b>Does an inquiry answered later on the seller centre ever come back?</b> (2026-11-06 eighteenth review: 「기존 문의 →
 * 다음 수집 구간으로 이동 → 판매자가 답변 → 상태 확인」). The real routine cursor and both real NAVER clients, run in
 * sequence against a fake channel that answers each list request the way its document says it filters, and every page
 * ingested through the real {@link IngestionService}.
 *
 * <p>What the documents settle and what they do not:
 * <ul>
 *   <li>고객 문의 ({@code /v1/pay-user/inquiries}): the window is the inquiry's <b>registration date</b> — the document's
 *       own wording. Simulated as that.</li>
 *   <li>상품 문의 ({@code /v1/contents/qnas}): 「검색 시작 일시」 and nothing about which date. Simulated both ways —
 *       question date and last activity — so the outcome that depends on the unknown is pinned on each side and only
 *       that one question is left for a live read.</li>
 * </ul>
 *
 * <p>The result this pins: the routine window does not re-read the last fortnight. Each run opens where the previous one
 * ended, so an inquiry is read while its date is inside the window being swept — once for 상품 문의, and for 고객 문의
 * until the end of its registration day — and the fortnight is only how far back a cursor that fell behind restarts.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class NaverLateAnswerReReadTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired ReviewRepository reviews;
    @Autowired InquiryRepository inquiries;
    @Autowired OrderDailySummaryRepository orders;
    @Autowired ProductRepository products;
    @Autowired ChannelProductRepository channelProducts;
    @Autowired Cafe24CommunityArticleRepository communityArticles;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquiryWorkItemAuditRepository audits;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ChannelRepository channels;

    private IngestionService ingest;
    private final UUID org = UUID.randomUUID();
    private final UUID channel = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ingest = new IngestionService(reviews, inquiries, orders, new ProductService(products), communityArticles,
                channels, new InquiryWorkItemWriter(inquiries, workItems, audits, txManager), channelProducts);
    }

    /** Which date the fake 상품 문의 resource filters its window by — the one thing its document does not say. */
    enum QnaBasis { QUESTION_DATE, LAST_ACTIVITY }

    /** A NAVER that keeps inquiries and answers list requests by its documented filters. */
    static final class FakeNaver implements NaverHttpClient {
        record Row(long no, Instant registered, String answer, Instant answeredAt) {
        }

        final Map<Long, Row> customer = new LinkedHashMap<>();
        final Map<Long, Row> qna = new LinkedHashMap<>();
        final QnaBasis basis;
        final List<String> asked = new ArrayList<>();

        FakeNaver(QnaBasis basis) {
            this.basis = basis;
        }

        void customerWrote(long no, String kst) {
            customer.put(no, new Row(no, kst(kst), null, null));
        }

        void questionWrote(long no, String kst) {
            qna.put(no, new Row(no, kst(kst), null, null));
        }

        /** The seller answers on the seller centre — SellerOps is not told. */
        void sellerAnswered(Map<Long, Row> board, long no, String text, String kst) {
            Row r = board.get(no);
            board.put(no, new Row(no, r.registered(), text, kst(kst)));
        }

        @Override
        public Response get(URI uri, String bearerToken) {
            Map<String, String> q = query(uri);
            asked.add(uri.getPath() + " " + q);
            StringBuilder rows = new StringBuilder();
            if (uri.getPath().endsWith(NaverCustomerInquiriesClient.PATH)) {
                LocalDate start = LocalDate.parse(q.get("startSearchDate"));
                LocalDate end = LocalDate.parse(q.get("endSearchDate"));
                for (Row r : customer.values()) {
                    LocalDate d = r.registered().atZone(KST).toLocalDate();
                    if (!d.isBefore(start) && !d.isAfter(end)) {
                        append(rows, "{\"inquiryNo\":" + r.no() + ",\"title\":\"배송\",\"inquiryContent\":\"언제 오나요\","
                                + "\"inquiryRegistrationDateTime\":\"" + iso(r.registered()) + "\","
                                + (r.answer() == null ? "\"answered\":false" : "\"answered\":true,\"answerContent\":\""
                                        + r.answer() + "\",\"answerRegistrationDateTime\":\"" + iso(r.answeredAt()) + "\"")
                                + "}");
                    }
                }
                return new Response(200, "{\"last\":true,\"content\":[" + rows + "]}", Map.of());
            }
            Instant from = OffsetDateTime.parse(q.get("fromDate")).toInstant();
            Instant to = OffsetDateTime.parse(q.get("toDate")).toInstant();
            for (Row r : qna.values()) {
                Instant at = basis == QnaBasis.LAST_ACTIVITY && r.answeredAt() != null ? r.answeredAt() : r.registered();
                if (!at.isBefore(from) && !at.isAfter(to)) {
                    append(rows, "{\"questionId\":" + r.no() + ",\"createDate\":\"" + iso(r.registered())
                            + "\",\"question\":\"사이즈 문의\","
                            + (r.answer() == null ? "\"answered\":false" : "\"answered\":true,\"answer\":\"" + r.answer() + "\"")
                            + "}");
                }
            }
            return new Response(200, "{\"last\":true,\"contents\":[" + rows + "]}", Map.of());
        }

        @Override
        public Response postForm(URI uri, Map<String, String> form) {
            throw new AssertionError("no token request in this test");
        }

        @Override
        public Response postJson(URI uri, String bearerToken, String jsonBody) {
            throw new AssertionError("reads only");
        }

        private static void append(StringBuilder b, String row) {
            b.append(b.length() == 0 ? "" : ",").append(row);
        }

        private static Map<String, String> query(URI uri) {
            Map<String, String> out = new HashMap<>();
            for (String kv : uri.getRawQuery().split("&")) {
                String[] p = kv.split("=", 2);
                out.put(p[0], URLDecoder.decode(p[1], StandardCharsets.UTF_8));
            }
            return out;
        }

        private static String iso(Instant i) {
            return i.atZone(KST).toOffsetDateTime().toString();
        }
    }

    /** A clock the test moves between runs. */
    static final class Wall extends Clock {
        Instant now;

        Wall(String kst) {
            now = kst(kst);
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static Instant kst(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(KST).toInstant();
    }

    /** One routine collection: page until the collector says done, ingest every page, keep the cursor. */
    private String collect(NaverInquiryCollector collector, String cursor) {
        FetchPage page;
        int guard = 0;
        do {
            page = collector.fetchInquiryPage("tok", cursor);
            List<CanonicalInquiry> rows = new ArrayList<>();
            page.records().forEach(r -> rows.add((CanonicalInquiry) r));
            ingest.ingestInquiries(org, channel, account, rows);
            cursor = page.nextCursorValue();
        } while (page.hasMore() && ++guard < 10);
        return cursor;
    }

    private Inquiry stored(String externalId) {
        return inquiries.findByOrgIdAndChannelIdAndExternalId(org, channel, externalId).orElseThrow();
    }

    private InquiryWorkItemPhase phase(String externalId) {
        UUID id = stored(externalId).getId();
        return workItems.findAll().stream().filter(w -> id.equals(w.getInquiryId())).map(InquiryWorkItem::getPhase)
                .findFirst().orElse(null);
    }

    private record Run(FakeNaver naver, Wall wall, NaverInquiryCollector collector) {
    }

    private static Run setUpRun(QnaBasis basis) {
        FakeNaver naver = new FakeNaver(basis);
        Wall wall = new Wall("2026-10-01T12:00:00");
        return new Run(naver, wall, new NaverInquiryCollector(new NaverProductQnaClient(naver, "https://fake.naver.test"),
                new NaverCustomerInquiriesClient(naver, "https://fake.naver.test"), wall));
    }

    @Test
    @DisplayName("고객 문의: an inquiry answered on the seller centre after its registration day has left the window is "
            + "never read again — it stays 미답변 with an open item; one answered on its own day, or new, is read")
    void aCustomerInquiryAnsweredOnALaterDayIsNotReRead() {
        Run run = setUpRun(QnaBasis.QUESTION_DATE);
        FakeNaver naver = run.naver();
        naver.customerWrote(501, "2026-10-01T10:00:00");     // answered three days later
        naver.customerWrote(502, "2026-10-01T10:30:00");     // answered the same evening

        String cursor = collect(run.collector(), null);       // 10-01 12:00
        assertThat(phase("naver-payinq:501")).isEqualTo(InquiryWorkItemPhase.OPEN);
        assertThat(phase("naver-payinq:502")).isEqualTo(InquiryWorkItemPhase.OPEN);

        naver.sellerAnswered(naver.customer, 502, "오늘 출고됩니다.", "2026-10-01T18:00:00");
        run.wall().now = kst("2026-10-01T20:00:00");
        cursor = collect(run.collector(), cursor);            // same day: the window still opens on 10-01
        assertThat(stored("naver-payinq:502").getStatus()).as("answered on its own day: read").isEqualTo("ANSWERED");
        assertThat(phase("naver-payinq:502")).isEqualTo(InquiryWorkItemPhase.COMPLETED);

        run.wall().now = kst("2026-10-02T12:00:00");
        cursor = collect(run.collector(), cursor);            // the window moves to 10-01..10-02
        run.wall().now = kst("2026-10-03T12:00:00");
        cursor = collect(run.collector(), cursor);            // and to 10-02..10-03 — 10-01 is behind it now

        naver.sellerAnswered(naver.customer, 501, "내일 출고됩니다.", "2026-10-04T09:00:00");
        naver.customerWrote(503, "2026-10-04T10:00:00");
        naver.sellerAnswered(naver.customer, 503, "확인했습니다.", "2026-10-04T11:00:00");
        run.wall().now = kst("2026-10-04T12:00:00");
        collect(run.collector(), cursor);

        assertThat(stored("naver-payinq:503").getStatus()).as("a new one answered before we read it").isEqualTo("ANSWERED");
        assertThat(stored("naver-payinq:501").getStatus())
                .as("answered on the seller centre three days later — inside the fortnight, but never asked about again")
                .isEqualTo("UNANSWERED");
        assertThat(phase("naver-payinq:501")).isEqualTo(InquiryWorkItemPhase.OPEN);
        assertThat(naver.asked).as("the last run asked about 10-03..10-04 only")
                .last().asString().contains("startSearchDate=2026-10-03");
    }

    @Test
    @DisplayName("상품 문의, if NAVER windows by the question's date: any answer written after the first read is never seen")
    void aProductQuestionAnsweredAfterTheFirstReadIsNotReReadOnQuestionDate() {
        Run run = setUpRun(QnaBasis.QUESTION_DATE);
        run.naver().questionWrote(601, "2026-10-01T10:00:00");
        String cursor = collect(run.collector(), null);
        run.naver().sellerAnswered(run.naver().qna, 601, "M 사이즈 추천드려요.", "2026-10-01T12:30:00");
        run.wall().now = kst("2026-10-01T13:00:00");
        collect(run.collector(), cursor);                     // half an hour later: the window opens at 12:00
        assertThat(stored("naver-qna:601").getStatus()).isEqualTo("UNANSWERED");
        assertThat(phase("naver-qna:601")).isEqualTo(InquiryWorkItemPhase.OPEN);
    }

    @Test
    @DisplayName("상품 문의, if NAVER windows by last activity: the answer brings the question back and closes it")
    void aProductQuestionAnsweredLaterIsReReadOnLastActivity() {
        Run run = setUpRun(QnaBasis.LAST_ACTIVITY);
        run.naver().questionWrote(601, "2026-10-01T10:00:00");
        String cursor = collect(run.collector(), null);
        run.naver().sellerAnswered(run.naver().qna, 601, "M 사이즈 추천드려요.", "2026-10-04T09:00:00");
        run.wall().now = kst("2026-10-04T12:00:00");
        collect(run.collector(), cursor);
        assertThat(stored("naver-qna:601").getStatus()).isEqualTo("ANSWERED");
        assertThat(phase("naver-qna:601")).isEqualTo(InquiryWorkItemPhase.COMPLETED);
    }
}
