package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.order.fact.ExactOrderLookupCapability;
import com.sellerops.order.fact.OrderCancellationState;
import com.sellerops.order.fact.OrderFulfillmentState;
import com.sellerops.review.Review;
import jakarta.persistence.Column;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>What a post-purchase timeline cannot be joined on today</b> — the six absences
 * {@code docs/post_purchase_timeline_audit_v1.md} measured, asserted so they cannot rot quietly.
 *
 * <p><b>This is a nameplate, not a wall — and it has already done its job once.</b> On 2026-10-10 the two
 * decisions it was waiting on were approved (Order Context Foundation v1: D1, a product identifier on an
 * order; D2, widening Coupang's status vocabulary), the two assertions naming them failed, and the failures
 * pointed here. They are rewritten below to the truth after those decisions, and the four absences that did
 * not move are asserted unchanged. That is the whole value of having written the absences down: the arc that
 * closed two of them could not do it quietly.
 *
 * <p><b>Why the entity and not the migration text.</b> A column can arrive in any later migration, so a
 * grep over one file proves nothing about the table. What the runtime actually binds is the entity, so the
 * entity's own {@code @Column} names are the honest place to ask.
 */
class PostPurchaseLinkageFenceTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    @Test
    @DisplayName("an order's product reference lives on a line, never lifted onto the order row")
    void theProductReferenceIsOnTheLineNotTheOrder() {
        // The positive control. Assertions about what a table does NOT have are worth nothing if the
        // reflection is reading the wrong class or silently returning an empty list, and an empty list
        // satisfies every doesNotContain in this file.
        assertThat(columnsOf(ChannelOrder.class))
                .as("the reflection is reading channel_orders at all")
                .contains("external_order_id", "parent_order_id", "raw_status_code", "channel_id");

        // D1 landed, and it landed in a CHILD table rather than as a column here — because the contents of
        // one channel_orders row differ by channel. A NAVER row is one product order; a Coupang row is one
        // shipment box whose orderItems may name several products, and a single column would make one
        // product of a two-product box into the product of the whole order. This assertion is what keeps
        // the convenient column from being added later, on the day somebody only has NAVER in mind.
        assertThat(columnsOf(ChannelOrder.class))
                .as("channel_orders gained a product column — a line's value may not be lifted to the "
                        + "payment unit; the reference belongs in channel_order_products")
                .doesNotContain("product_id", "external_product_id", "channel_product_id",
                        "product_name", "source_product_ref");

        // And the child table carries the identifier, its provenance, the canonical binding and the time
        // WE bound it — four columns, no fifth that the channel did not give.
        assertThat(columnsOf(ChannelOrderProduct.class))
                .contains("external_product_id", "ref_source", "product_id", "bound_at",
                        "channel_order_id");
        assertThat(columnsOf(ChannelOrderProduct.class))
                .as("a quantity, a price or a product name here would be a copy of something the channel "
                        + "owns, and the copy is what drifts")
                .doesNotContain("quantity", "product_name", "external_product_name", "order_price",
                        "option_code");
    }

    @Test
    @DisplayName("an order does not know its buyer, and a review does not know its order or its buyer")
    void noBuyerAndNoOrderOnAReview() {
        // The buyer's absence is V32's deliberate privacy minimization, not a gap. It is asserted beside the
        // order link because together they are what makes same-person reasoning unreachable rather than
        // merely forbidden: there is no material to infer from.
        assertThat(columnsOf(Review.class))
                .as("the reflection is reading reviews at all")
                .contains("product_id", "received_at", "source_product_ref", "reply_state");
        List<String> buyerish = List.of("buyer_id", "customer_id", "customer_name", "buyer_name",
                "orderer", "receiver", "phone", "address");
        assertThat(columnsOf(ChannelOrder.class))
                .as("channel_orders gained a buyer field — V32 privacy minimization")
                .doesNotContainAnyElementsOf(buyerish);
        assertThat(columnsOf(Review.class))
                .as("reviews gained a buyer field")
                .doesNotContainAnyElementsOf(buyerish);
        assertThat(columnsOf(Review.class))
                .as("reviews gained an order reference — audit §2.2")
                .doesNotContain("order_id", "external_order_id", "source_order_ref", "parent_order_id",
                        "order_binding", "channel_order_id");
    }

    @Test
    @DisplayName("배송 완료 is reachable for exactly one channel, and NAVER is not it")
    void deliveredIsReachableForCoupangAndNotForNaver() {
        // The audit's sharpest sentence was "the set of orders for which this product can establish
        // 배송 완료 is structurally empty". D2 ended that for Coupang — with two pieces of evidence, the
        // live observation and the platform's own deliveryStatus table — and this is the one assertion
        // that records which channel it ended for. The per-code reasoning lives in
        // ChannelOrderStatusVocabularyTest; what is audit-level is the shape of the answer.
        assertThat(ChannelOrderStatusVocabulary.axesFromStored("COUPANG", "FINAL_DELIVERY").fulfillment())
                .isEqualTo(OrderFulfillmentState.DELIVERED);

        // NAVER still cannot say it. Its change feed is requested at lastChangedType=PAYED and no other
        // NAVER code has a meaning here; D4 (widening that scope after a live proof) is still open, and
        // the research found something worse than a missing scope — sellers are asking NAVER for a
        // delivery-completed field, and DISPATCHED is 발송 처리, not arrival.
        for (String naver : List.of("PAYED", "DISPATCHED", "PURCHASE_DECIDED", "DELIVERED")) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored("NAVER", naver).fulfillment())
                    .as("NAVER/%s proves a fulfillment state — D4 is still open, and 발송 처리 is not "
                            + "배송 완료", naver)
                    .isEqualTo(OrderFulfillmentState.UNKNOWN);
        }

        // No stored code proves a NEGATIVE on any axis, and that did not move. An order cancelled after
        // our last read looks exactly like one never cancelled.
        for (ChannelOrderStatusVocabulary.Code code : ChannelOrderStatusVocabulary.confirmedKeys()) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored(code.channelCode(), code.rawStatusCode())
                    .cancellation())
                    .as("%s/%s now proves something about cancellation", code.channelCode(),
                            code.rawStatusCode())
                    .isEqualTo(OrderCancellationState.UNKNOWN);
        }
    }

    @Test
    @DisplayName("Coupang's vendored exact-lookup contract is not a declared capability — no reader, no claim")
    void aVendoredContractIsNotYetACapability() {
        // The research behind D2 turned up more than a status table: Coupang publishes
        // GET .../ordersheets/{shipmentBoxId}/history, an exact read by an identifier this repository
        // already stores, whose every entry carries the channel's own updatedAt — the one thing the audit said
        // no Coupang fact has. The contract is vendored. The capability is NOT declared, because declaring
        // it without a reader advertises a lookup that silently never happens, and building the reader is
        // an N-calls-per-N-orders decision that belongs to the product owner.
        assertThat(Files.exists(Paths.get("../docs/vendor/coupang-openapi/get-ordersheet-history.md")))
                .as("the vendored Coupang delivery-status contract — ChannelOrderStatusVocabulary's "
                        + "evidence depends on this file")
                .isTrue();
        assertThat(ExactOrderLookupCapability.declaredChannels())
                .as("a channel gained an exact single-order READ — audit §2.3")
                .containsExactly("CAFE24");
        assertThat(ExactOrderLookupCapability.isAvailable("NAVER")).isFalse();
        assertThat(ExactOrderLookupCapability.isAvailable("COUPANG")).isFalse();
    }

    @Test
    @DisplayName("there is no return, exchange or claim record anywhere in the schema")
    void noClaimRecord() throws IOException {
        List<String> tables = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MIGRATIONS)) {
            for (Path sql : walk.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String text = Files.readString(sql).toLowerCase(Locale.ROOT);
                for (String word : List.of("order_returns", "order_claims", "channel_order_claims",
                        "return_requests", "exchange_requests", "order_cancellations")) {
                    if (text.contains("create table " + word) || text.contains("create table if not exists " + word)) {
                        tables.add(word + " (" + sql.getFileName() + ")");
                    }
                }
            }
        }
        // 반품 appears in this repository only as a TOPIC word — an exchange/refund policy a seller writes,
        // read by the inquiry draft and the knowledge check. It has never been an order-side record, and a
        // timeline that drew a 반품 step would be drawing one from the policy text.
        assertThat(tables).as("a claim record now exists — audit §2.5, and the timeline can gain a step")
                .isEmpty();
    }

    /** Every {@code @Column} name on an entity, including inherited ones. */
    private static List<String> columnsOf(Class<?> entity) {
        List<String> out = new ArrayList<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Column column = f.getAnnotation(Column.class);
                out.add(column != null && !column.name().isBlank()
                        ? column.name().toLowerCase(Locale.ROOT)
                        // No @Column(name=…) means Hibernate derives it; snake_case it the same way so a
                        // field added without an explicit name is still checked.
                        : f.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
