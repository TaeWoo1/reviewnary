package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.collect.SyncRunExecutor;
import com.sellerops.connector.ChannelConnectionStatusRepository;
import com.sellerops.connector.ConnectorRegistry;
import com.sellerops.connector.DataType;
import com.sellerops.connector.naver.NaverApiConnector;
import com.sellerops.connector.naver.NaverHttpClient;
import com.sellerops.connector.naver.NaverOrdersClient;
import com.sellerops.connector.naver.NaverTokenClient;
import com.sellerops.credential.ConnectorCredentialRepository;
import com.sellerops.credential.CredentialVault;
import com.sellerops.ingest.IngestionService;
import com.sellerops.ingest.canonical.CanonicalOrder;
import com.sellerops.order.ChannelOrder;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.sync.SyncCursorRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Disposable-Postgres proof for the per-order acquisition foundation. Unlike the H2 suite, this boots
 * the real application context against a throwaway PostgreSQL so <b>real Flyway applies V1..V32</b> and
 * the per-order path is exercised over the production schema (real constraints, indexes, types).
 *
 * <p><b>Opt-in only.</b> Gated by {@code SELLEROPS_PG_PROOF=1}; without it the whole class is skipped,
 * so the normal gate and CI (which have no Postgres) are unaffected. Point it at the disposable DB with
 * {@code SELLEROPS_PG_URL} (default {@code jdbc:postgresql://localhost:55432/sellerops}).
 *
 * <p>Not {@code @Transactional}: rows persist so they can be inspected with {@code psql} afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class ChannelOrderPostgresProofIT {

    // Fixed clock: settles the NAVER order window in one pass and keeps 2026-06-11 inside the 24h
    // window + day-total retention horizon (same rationale as SyncRunExecutorTest.FIXED_CLOCK).
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("SELLEROPS_PG_URL",
                "jdbc:postgresql://localhost:55432/sellerops");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "sellerops");
        registry.add("spring.datasource.password", () -> "sellerops_local_pw");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Clean DB — no demo channels/org, so the proof seeds exactly the parent rows it needs
        // (the real Postgres schema enforces the org/account/channel foreign keys H2 did not).
        registry.add("sellerops.seed.enabled", () -> "false");
        // The catalogue is separate from the fixture since Pilot Runtime Foundation v1 §2; this
        // class owns the channels table, so it suppresses that too.
        registry.add("sellerops.seed.channel-catalogue", () -> "false");
    }

    @Autowired ChannelOrderRepository orders;
    @Autowired ChannelOrderStatusEventRepository statusEvents;
    @Autowired com.sellerops.order.OrderDailySummaryRepository dailySummaries;
    @Autowired ChannelOrderIngestionService orderIngestion;
    @Autowired IngestionService ingestionService;
    @Autowired SellerAccountRepository sellerAccounts;
    @Autowired ChannelRepository channels;
    @Autowired SyncJobRepository syncJobs;
    @Autowired SyncCursorRepository cursors;
    @Autowired ChannelConnectionStatusRepository connectionStatus;
    @Autowired ConnectorCredentialRepository credentials;
    @Autowired com.sellerops.organization.OrganizationRepository organizations;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelOrderProductRepository orderProducts;
    @Autowired com.sellerops.product.ChannelProductRepository channelProducts;
    @Autowired com.sellerops.product.ProductRepository products;
    @Autowired OrderRecordDetailService orderDetail;
    @Autowired com.sellerops.selleraccount.SellerAccountRepository accountsForDetail;

    // 1 — real Flyway applied the whole chain up to V32, and the two tables + identity index exist.
    @Test
    void flywayAppliedV32AndSchemaExists() {
        // Was {@code isEqualTo(32)} when written, when V32 was the top of the chain. An equality here
        // fails on every migration landed since for a reason that has nothing to do with per-order
        // acquisition, so it asserts what it actually means: the whole chain applied, and the version
        // this class proves is in it.
        Integer maxVersion = jdbc.queryForObject(
                "select max(cast(version as integer)) from flyway_schema_history where success", Integer.class);
        assertThat(maxVersion).isGreaterThanOrEqualTo(32);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history "
                + "where success and version = '32'", Integer.class)).isEqualTo(1);

        assertThat(tableExists("channel_orders")).isTrue();
        assertThat(tableExists("channel_order_status_events")).isTrue();
        Integer uniq = jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'uq_channel_orders_identity'", Integer.class);
        assertThat(uniq).isEqualTo(1);
        // The identity unique index is enforced by Postgres, not just the service.
        Integer fkStatusEvents = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where table_name='channel_order_status_events' and constraint_type='FOREIGN KEY'",
                Integer.class);
        assertThat(fkStatusEvents).isGreaterThanOrEqualTo(1);
    }

    // 4/5/8 — synthetic ingest → re-collect (dedup) → status change (event append) → restart (idempotent),
    // over the real Postgres schema.
    @Test
    void ingestRecollectStatusChangeRestart() {
        UUID org = seedOrg();
        Channel ch = seedChannel("T-" + UUID.randomUUID().toString().substring(0, 8));
        UUID channel = ch.getId();
        UUID account = seedAccount(org, ch).getId();
        String po = "PO-" + UUID.randomUUID();

        var first = orderIngestion.ingest(org, channel, account,
                List.of(order(po, "PAYED", 12000, "2026-06-11")));
        assertThat(first.success()).isEqualTo(1);

        var rerun = orderIngestion.ingest(org, channel, account,
                List.of(order(po, "PAYED", 12000, "2026-06-11")));
        assertThat(rerun.skipped()).isEqualTo(1);
        assertThat(orders.findAllByOrgIdAndSellerAccountId(org, account)).hasSize(1);

        var changed = orderIngestion.ingest(org, channel, account,
                List.of(order(po, "DELIVERED", 12000, "2026-06-11")));
        assertThat(changed.success()).isEqualTo(1);
        ChannelOrder row = orders.findByOrgIdAndSellerAccountIdAndExternalOrderId(org, account, po).orElseThrow();
        assertThat(row.getRawStatusCode()).isEqualTo("DELIVERED");
        assertThat(row.getNormalizedStatus()).isEqualTo(NormalizedOrderStatus.UNKNOWN);
        assertThat(statusEvents.countByOrgIdAndChannelOrderId(org, row.getId())).isEqualTo(2);

        // "Restart": a fresh service instance still sees the durable row and stays idempotent.
        ChannelOrderIngestionService afterRestart =
                new ChannelOrderIngestionService(orders, statusEvents, channels, txManager);
        var afterRestartOutcome = afterRestart.ingest(org, channel, account,
                List.of(order(po, "DELIVERED", 12000, "2026-06-11")));
        assertThat(afterRestartOutcome.skipped()).isEqualTo(1);
        assertThat(statusEvents.countByOrgIdAndChannelOrderId(org, row.getId())).isEqualTo(2);
    }

    // 9 — a full NAVER sync (fake HTTP) lands BOTH the daily summary and per-order rows on Postgres, and
    // the per-order aggregate matches order_daily_summaries by construction.
    @Test
    void fullNaverSyncLandsDailyAndPerOrderConsistently() {
        UUID org = seedOrg();
        SellerAccount acc = naverAccount(org);

        String masterKey = Base64.getEncoder().encodeToString(new byte[32]);
        CredentialVault vault = new CredentialVault(credentials, new ObjectMapper(), masterKey, "pg-proof");
        vault.store(org, acc.getId(), "API", "OAUTH2",
                Map.of("client_id", "cid", "client_secret", BCrypt.gensalt()), null, null, null);

        QueueingNaverHttpClient http = new QueueingNaverHttpClient();
        http.responses.add(ok("{\"access_token\":\"tok-1\",\"expires_in\":3000,\"token_type\":\"Bearer\"}"));
        http.responses.add(ok("{\"data\":{\"lastChangeStatuses\":["
                + "{\"productOrderId\":\"PGPO1\",\"orderId\":\"O1\",\"productOrderStatus\":\"PAYED\","
                + "\"lastChangedType\":\"PAYED\",\"lastChangedDate\":\"2026-06-11T22:00:00+09:00\","
                + "\"paymentDate\":\"2026-06-11T22:00:00+09:00\"},"
                + "{\"productOrderId\":\"PGPO2\",\"orderId\":\"O1\",\"productOrderStatus\":\"PAYED\","
                + "\"lastChangedType\":\"PAYED\",\"lastChangedDate\":\"2026-06-11T23:30:00+09:00\","
                + "\"paymentDate\":\"2026-06-11T23:30:00+09:00\"}]}}"));
        http.responses.add(ok("{\"data\":[{\"productOrder\":{\"productOrderId\":\"PGPO1\",\"initialPaymentAmount\":12000}},"
                + "{\"productOrder\":{\"productOrderId\":\"PGPO2\",\"initialPaymentAmount\":8000}}]}"));

        NaverApiConnector naver = new NaverApiConnector(
                new NaverTokenClient(http, FIXED_CLOCK, "https://fake.naver.test"),
                new NaverOrdersClient(http, FIXED_CLOCK, "https://fake.naver.test", 100), vault);
        SyncRunExecutor executor = new SyncRunExecutor(sellerAccounts, channels,
                new ConnectorRegistry(List.of(naver)), ingestionService, orderIngestion,
                syncJobs, cursors, connectionStatus);

        SyncJob job = executor.execute(org, acc.getId(), DataType.ORDER_SUMMARY, "MANUAL");

        assertThat(job.getStatus()).isEqualTo("SUCCESS");
        assertThat(job.getSuccessRows()).isEqualTo(2);
        var perOrder = orders.findAllByOrgIdAndChannelIdAndSummaryDate(org, acc.getChannelId(), LocalDate.parse("2026-06-11"));
        assertThat(perOrder).hasSize(2);
        var daily = dailySummaries.findByOrgIdAndChannelIdAndSummaryDate(org, acc.getChannelId(), LocalDate.parse("2026-06-11")).orElseThrow();
        assertThat((long) perOrder.size()).isEqualTo(daily.getOrderCount());
        assertThat(perOrder.stream().mapToLong(ChannelOrder::getPaymentAmount).sum()).isEqualTo(daily.getSalesAmount());
    }

    // 6 — no buyer-PII column exists on either table, and no persisted value is a raw JSON payload.
    @Test
    void noBuyerPiiColumnsOrRawPayloadInSchema() {
        List<String> cols = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name in ('channel_orders','channel_order_status_events')",
                String.class);
        String[] forbidden = {"buyer", "author", "phone", "tel", "mobile", "address", "addr",
                "recipient", "receiver", "memo", "email", "zipcode", "postcode", "payload", "raw_json", "body"};
        for (String col : cols) {
            for (String bad : forbidden) {
                assertThat(col.toLowerCase()).as("column %s must not be PII/raw-payload", col).doesNotContain(bad);
            }
        }
        // No text/blob free-form column that could hold a raw payload — every non-key text column is a
        // bounded status/id field.
        List<String> textCols = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name='channel_orders' and data_type in ('text','json','jsonb')",
                String.class);
        assertThat(textCols).isEmpty();
    }

    // --- helpers ---

    private boolean tableExists(String name) {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = ?", Integer.class, name);
        return n != null && n == 1;
    }

    // V132 — the order learns its product, and only by an exact match on the channel's own identifier.
    @Test
    void anOrderLearnsItsProductOnlyByAnExactIdentifierMatch() {
        UUID org = seedOrg();
        // COUPANG, not an invented test code: the provenance vocabulary is keyed by channel, so a channel
        // with no ChannelProductRefSource stores no reference at all. Writing this test against
        // "CPROOF-xxxx" first is how that was confirmed — the fence refused, as it should.
        Channel ch = seedChannel("COUPANG");
        UUID channel = ch.getId();
        UUID account = seedAccount(org, ch).getId();
        String po = "PO-" + UUID.randomUUID();
        String listingId = "154785" + (int) (Math.random() * 100000);

        // The order arrives BEFORE the product catalogue, which is the common order of events: the order
        // routine runs hourly and product collection is rarer.
        var first = orderIngestion.ingest(org, channel, account, List.of(order(po, "FINAL_DELIVERY", 12000,
                "2026-06-11", List.of(com.sellerops.ingest.canonical.ChannelProductRef.of(listingId)))));
        assertThat(first.success()).isEqualTo(1);
        ChannelOrder row = orders.findByOrgIdAndSellerAccountIdAndExternalOrderId(org, account, po)
                .orElseThrow();

        List<ChannelOrderProduct> refs = orderProducts.findByChannelOrderId(row.getId());
        assertThat(refs).singleElement().satisfies(ref -> {
            assertThat(ref.getExternalProductId()).isEqualTo(listingId);
            assertThat(ref.isUnbound())
                    .as("식별자는 받았고 일치하는 canonical 상품이 없다 — 「채널이 안 줬다」와 다른 상태다")
                    .isTrue();
        });

        // The product catalogue catches up.
        com.sellerops.product.Product product = new com.sellerops.product.Product();
        product.setOrgId(org);
        product.setName("pg-proof 상품");
        product.setSku("SKU-" + UUID.randomUUID().toString().substring(0, 8));
        product.setStatus("ACTIVE");
        UUID productId = products.save(product).getId();
        com.sellerops.product.ChannelProduct listing = new com.sellerops.product.ChannelProduct();
        listing.setOrgId(org);
        listing.setChannelId(channel);
        listing.setProductId(productId);
        listing.setExternalProductId(listingId);
        channelProducts.save(listing);

        // A re-sync with the same status resolves it — the identifier arriving and the status changing
        // are independent events, so an unchanged re-collection must still be able to bind.
        orderIngestion.ingest(org, channel, account, List.of(order(po, "FINAL_DELIVERY", 12000,
                "2026-06-11", List.of(com.sellerops.ingest.canonical.ChannelProductRef.of(listingId)))));
        ChannelOrderProduct bound = orderProducts.findByChannelOrderId(row.getId()).get(0);
        assertThat(bound.getProductId()).isEqualTo(productId);
        assertThat(bound.getBoundAt()).isNotNull();

        // And the DB refuses to let that binding become a different product — asserted here rather than
        // trusted, because the Java path simply never tries it and a future path might.
        assertThatThrownBy(() -> jdbc.update(
                "update channel_order_products set product_id = ? where id = ?",
                UUID.randomUUID(), bound.getId()))
                .hasMessageContaining("이미 연결된 상품은 바꿀 수 없습니다");
        assertThatThrownBy(() -> jdbc.update(
                "update channel_order_products set external_product_id = '0' where id = ?", bound.getId()))
                .hasMessageContaining("상품 식별자는 수정할 수 없습니다");
    }

    // V132 + D2 — the whole post-purchase context of one real-shaped order, through the canonical read.
    @Test
    void thePostPurchaseContextReadsProductOrderFulfillmentAndProvenance() {
        UUID org = seedOrg();
        Channel ch = seedChannel("COUPANG");
        UUID channel = ch.getId();
        SellerAccount account = seedAccount(org, ch);
        String box = "BOX-" + UUID.randomUUID();
        String listingId = "154216" + (int) (Math.random() * 100000);

        com.sellerops.product.Product product = new com.sellerops.product.Product();
        product.setOrgId(org);
        product.setName("선바로 일체형 전선몰딩");
        product.setSku("SKU-" + UUID.randomUUID().toString().substring(0, 8));
        product.setStatus("ACTIVE");
        UUID productId = products.save(product).getId();
        com.sellerops.product.ChannelProduct listing = new com.sellerops.product.ChannelProduct();
        listing.setOrgId(org);
        listing.setChannelId(channel);
        listing.setProductId(productId);
        listing.setExternalProductId(listingId);
        channelProducts.save(listing);

        orderIngestion.ingest(org, channel, account.getId(), List.of(order(box, "FINAL_DELIVERY", 12000,
                "2026-06-11", List.of(com.sellerops.ingest.canonical.ChannelProductRef.of(listingId)))));

        var detail = orderDetail.detail(org, "COUPANG", account.getId(), "O1");

        // Fulfillment — from the vocabulary widened under D2, on a stored row, with no marketplace call.
        assertThat(detail.fulfillmentLabelKo()).isEqualTo("배송 완료");
        assertThat(detail.paymentLabelKo())
                .as("배송 코드가 결제를 증명하지 않는다 — 세 축이 따로 적히는 것이 이 화면의 계약이다")
                .isNull();
        // Product — bound, named from the canonical product, with its provenance.
        assertThat(detail.lines()).singleElement().satisfies(line -> {
            assertThat(line.confirmedStatusLabelKo()).isEqualTo("배송 완료");
            assertThat(line.products()).singleElement().satisfies(ref -> {
                assertThat(ref.productId()).isEqualTo(productId);
                assertThat(ref.productName()).isEqualTo("선바로 일체형 전선몰딩");
                assertThat(ref.externalProductId()).isEqualTo(listingId);
                assertThat(ref.refSource()).isEqualTo("COUPANG_ORDERSHEETS");
                assertThat(ref.boundAt()).as("우리가 연결한 시각 — 채널이 말한 시각이 아니다 (D3)")
                        .isNotNull();
                assertThat(ref.isBound()).isTrue();
            });
        });
        // Time — the status history carries OUR record time and the channel's, kept apart. The synthetic
        // fixture supplies a statusChangedAt; a real Coupang row has none and this stays null (D3).
        assertThat(detail.statusHistory()).isNotEmpty();
        assertThat(detail.lastSeenAt()).isNotNull();
    }

    private static CanonicalOrder order(String extId, String status, long amount, String date) {
        return order(extId, status, amount, date, List.of());
    }

    private static CanonicalOrder order(String extId, String status, long amount, String date,
                                        List<com.sellerops.ingest.canonical.ChannelProductRef> refs) {
        Instant at = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new CanonicalOrder(extId, "O1", status, amount, LocalDate.parse(date), at, at, 1, refs);
    }

    private SellerAccount naverAccount(UUID org) {
        return seedAccount(org, seedChannel("NAVER"));
    }

    private UUID seedOrg() {
        com.sellerops.organization.Organization o = new com.sellerops.organization.Organization();
        o.setName("pg-proof-org");
        return organizations.save(o).getId();
    }

    /** Reuse-or-create so the proof is re-runnable against the same disposable DB (code is unique). */
    private Channel seedChannel(String code) {
        return channels.findByCode(code).orElseGet(() -> {
            Channel c = new Channel();
            c.setCode(code);
            c.setNameKo(code);
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSupportsInquiry(true);
            c.setSupportsReview(true);
            c.setSupportsOrder(true);
            c.setSupportsSales(true);
            c.setSupportsProduct(true);
            c.setSortOrder(0);
            return channels.save(c);
        });
    }

    private SellerAccount seedAccount(UUID org, Channel ch) {
        SellerAccount acc = new SellerAccount();
        acc.setOrgId(org);
        acc.setChannelId(ch.getId());
        acc.setConnectionStatus(ChannelStatus.CONNECTED);
        acc.setFileUpload(false);
        return sellerAccounts.save(acc);
    }

    private static NaverHttpClient.Response ok(String body) {
        return new NaverHttpClient.Response(200, body, Map.of());
    }

    /** Minimal in-order response queue (mirrors the H2 suite's fake). */
    private static final class QueueingNaverHttpClient implements NaverHttpClient {
        final ArrayDeque<Response> responses = new ArrayDeque<>();

        @Override
        public Response postForm(java.net.URI uri, Map<String, String> form) {
            return next();
        }

        @Override
        public Response get(java.net.URI uri, String bearerToken) {
            return next();
        }

        @Override
        public Response postJson(java.net.URI uri, String bearerToken, String jsonBody) {
            return next();
        }

        private Response next() {
            if (responses.isEmpty()) {
                throw new AssertionError("unexpected HTTP call");
            }
            return responses.pop();
        }
    }
}
