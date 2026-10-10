package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.order.fact.ExactOrderLookupCapability;
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
 * <p><b>This is a nameplate, not a wall.</b> Every claim here is a fact about today's schema, and two of
 * them are expected to change the day a product-owner decision lands (D1: an external product identifier on
 * an order; D2: widening Coupang's status vocabulary). When one does, the assertion that named it fails and
 * the failure points at the audit — "what was written down as absent is now present, read the audit again" —
 * rather than letting a later arc quietly build a timeline on a link somebody half-added.
 *
 * <p><b>Why the entity and not the migration text.</b> A column can arrive in any later migration, so a
 * grep over one file proves nothing about the table. What the runtime actually binds is the entity, so the
 * entity's own {@code @Column} names are the honest place to ask.
 */
class PostPurchaseLinkageFenceTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    @Test
    @DisplayName("an order does not know its product — there is no column for one, in either direction")
    void anOrderHasNoProduct() {
        // The chain would close here and only here: channel_products.external_product_id is already the
        // join key, so one external product identifier on an order is the whole missing link. Whether the
        // channel's response even carries it is external research (no vendored NAVER response schema; no
        // vendored Coupang document at all), which is exactly why this stays an assertion and not a TODO.
        // The positive control. Six assertions about what a table does NOT have are worth nothing if the
        // reflection is reading the wrong class or silently returning an empty list, and an empty list
        // satisfies every doesNotContain in this file.
        assertThat(columnsOf(ChannelOrder.class))
                .as("the reflection is reading channel_orders at all")
                .contains("external_order_id", "parent_order_id", "raw_status_code", "channel_id");
        assertThat(columnsOf(ChannelOrder.class))
                .as("channel_orders gained a product reference — audit §2.1 / decision D1")
                .doesNotContain("product_id", "external_product_id", "channel_product_id",
                        "product_name", "source_product_ref");
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
    @DisplayName("exactly one (channel, code) has a confirmed meaning, and it is NAVER's PAYED")
    void oneConfirmedCode() {
        assertThat(ChannelOrderStatusVocabulary.confirmedKeys())
                .as("the confirmed vocabulary grew — audit §2.3 / decision D2")
                .containsExactly(new ChannelOrderStatusVocabulary.Code("NAVER", "PAYED"));
    }

    @Test
    @DisplayName("no stored status code proves the fulfillment axis — 배송 완료 is unreachable from the table")
    void noStoredCodeProvesFulfillment() {
        // The sharpest sentence in the audit, and the one a reader is most likely to doubt: Coupang's
        // FINAL_DELIVERY sits on 133 stored rows and reads like 배송 완료 in English. It is not confirmed,
        // so it proves nothing — and this asserts that over the WHOLE vocabulary rather than over the one
        // row it happens to hold, so a widening that forgot the fulfillment axis still fails here.
        for (ChannelOrderStatusVocabulary.Code code : ChannelOrderStatusVocabulary.confirmedKeys()) {
            assertThat(ChannelOrderStatusVocabulary.axesFromStored(code.channelCode(), code.rawStatusCode())
                    .fulfillment())
                    .as("%s/%s now proves a fulfillment state — audit §2.3", code.channelCode(),
                            code.rawStatusCode())
                    .isEqualTo(OrderFulfillmentState.UNKNOWN);
        }
        // And the unconfirmed codes this repository actually stores stay unconfirmed, by name.
        for (String coupang : List.of("FINAL_DELIVERY", "DELIVERING", "DEPARTURE", "INSTRUCT", "ACCEPT")) {
            assertThat(ChannelOrderStatusVocabulary.confirmed("COUPANG", coupang))
                    .as("COUPANG/%s was given a meaning — decision D2 is the owner's, and it needs an "
                            + "evidence row", coupang)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the only channel declaring an exact single-order READ is the one with no stored orders")
    void theEmptySetIsStructural() {
        // Cafe24's live read is the ONLY path to a DELIVERED fact (shipping_status = T), and Cafe24 has no
        // rows in channel_orders; the two channels holding all 573 orders declare no exact lookup. The
        // contract side of that is what a test can hold, so it holds that side.
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
