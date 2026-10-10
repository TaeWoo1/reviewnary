package com.sellerops.connector.coupang;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The observer's whole licence is that its output contains no data — so that is what this file spends
 * most of its assertions on.
 *
 * <p>The fixture deliberately carries the kinds of value that must never escape: a product title, a
 * price, a seller SKU, a description, an identifier. If any of them can be found in the summary, the
 * observer is not safe to run against a real catalogue and not safe to quote in an evidence document.
 */
class CoupangWireShapeObserverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BODY = """
            {"code":200,"nextToken":"TOKEN-77","data":[
              {"sellerProductId":13571113,"sellerProductName":"전선몰딩 1호 3m",
               "statusName":"승인완료","brand":"몰딩공방","displayCategoryCode":63915,
               "items":[
                 {"vendorItemId":88991122,"itemName":"화이트 3m","externalVendorSku":"MLD-777",
                  "salePrice":12900,"attributes":[{"attributeTypeName":"길이","attributeValueName":"3m"}]},
                 {"vendorItemId":88991133,"itemName":"블랙 3m","externalVendorSku":null,
                  "salePrice":13900,"attributes":[]}],
               "contents":[{"contentDetails":[{"content":"상세페이지 본문입니다"}]}]}]}
            """;

    private CoupangWireShapeObserver observed() throws Exception {
        CoupangWireShapeObserver observer = new CoupangWireShapeObserver();
        observer.observe("list", MAPPER.readTree(BODY));
        return observer;
    }

    @Test
    void noValueFromTheBodyAppearsAnywhereInTheSummary() throws Exception {
        String summary = String.join("\n", observed().summaryLines());

        assertThat(summary)
                .doesNotContain("전선몰딩")          // a product title
                .doesNotContain("몰딩공방")          // a brand value
                .doesNotContain("화이트")            // an option name
                .doesNotContain("MLD-777")           // the seller's own SKU
                .doesNotContain("12900")             // a price
                .doesNotContain("13571113")          // a product identifier
                .doesNotContain("88991122")          // an option identifier
                .doesNotContain("상세페이지 본문")    // description content
                .doesNotContain("TOKEN-77")          // even the paging token
                .doesNotContain("승인완료")          // a status value
                .doesNotContain("3m");               // an attribute value
    }

    @Test
    void keyNamesAndTheirFillCountsAreRecorded() throws Exception {
        List<String> lines = observed().summaryLines();

        assertThat(lines).anySatisfy(line ->
                assertThat(line).contains("$.data[].sellerProductId").contains("present=1/1").contains("nonNull=1"));
        // Two items, one with a vendor SKU and one with an explicit null — present twice, filled once.
        assertThat(lines).anySatisfy(line ->
                assertThat(line).contains("$.data[].items[].externalVendorSku")
                        .contains("present=2/2").contains("nonNull=1"));
    }

    @Test
    void arrayCardinalityIsCountedNotSampled() throws Exception {
        assertThat(observed().summaryLines()).anySatisfy(line ->
                assertThat(line).contains("array $.data[].items")
                        .contains("occurrences=1").contains("elements=2"));
    }

    @Test
    void aKeyThatNeverAppearsIsReportedAsAbsentRatherThanOmitted() throws Exception {
        List<String> watched = observed().watchedKeyLines();

        // The four negative claims the mapper makes, and the identifiers the proof set out to settle.
        assertThat(watched).contains("watched manufacture = ABSENT");
        assertThat(watched).contains("watched images = ABSENT");
        assertThat(watched).contains("watched notices = ABSENT");
        assertThat(watched).contains("watched productId = ABSENT");
        assertThat(watched).anySatisfy(line -> assertThat(line).startsWith("watched contents = list "));
        assertThat(watched).anySatisfy(line -> assertThat(line).startsWith("watched vendorItemId = list "));
    }

    @Test
    void everyWatchedKeyGetsALineWhetherOrNotItWasSeen() throws Exception {
        assertThat(observed().watchedKeyLines()).hasSize(CoupangWireShapeObserver.WATCHED_KEYS.size());
    }

    @Test
    void anEmptyObserverSaysSoRatherThanReportingAnEmptySchema() {
        assertThat(new CoupangWireShapeObserver().isEmpty()).isTrue();
    }

    // --- the ORDER observation (Order Context Foundation v1 §8.1) ------------------------------

    /**
     * An ordersheets body shaped the way the official contract describes it, with every value made
     * distinctive so a leak cannot hide — a buyer name and a receiver block are included deliberately,
     * because the one thing that must never reach an evidence document is exactly what Coupang puts
     * beside the fields this observation is for.
     */
    private static final String ORDER_BODY = """
            {"code":200,"message":"OK","nextToken":"ORDTOK-99","data":[
              {"shipmentBoxId":778899112233,"orderId":445566778899,"status":"FINAL_DELIVERY",
               "orderedAt":"2026-08-05T10:00:00+09:00","paidAt":"2026-08-05T10:01:00+09:00",
               "orderer":{"name":"홍길동","email":"gildong@example.com"},
               "receiver":{"name":"성춘향","addr1":"서울시 중구 세종대로 110"},
               "orderItems":[
                 {"orderPrice":12900,"sellerProductId":"15478536542","vendorItemId":"88991122",
                  "vendorItemName":"선바로 일체형 전선몰딩 화이트 3m","sellerProductName":"전선몰딩"},
                 {"orderPrice":3000,"sellerProductId":null,"vendorItemId":"88991133",
                  "vendorItemName":"","sellerProductName":"몰딩공방 스페셜"}]}]}
            """;

    private static CoupangWireShapeObserver observedOrder() throws Exception {
        CoupangWireShapeObserver observer =
                new CoupangWireShapeObserver(CoupangWireShapeObserver.ORDER_WATCHED_KEYS);
        observer.observe("ordersheets", MAPPER.readTree(ORDER_BODY));
        return observer;
    }

    @Test
    void noValueFromAnOrderBodyAppearsAnywhereInTheSummary() throws Exception {
        String summary = String.join("\n", observedOrder().summaryLines());

        assertThat(summary)
                // Buyer PII above all. This body carries it because real ones do.
                .doesNotContain("홍길동")
                .doesNotContain("성춘향")
                .doesNotContain("gildong@example.com")
                .doesNotContain("세종대로")
                // And then every other value, identifiers included.
                .doesNotContain("778899112233")      // the shipment box id
                .doesNotContain("445566778899")      // the order id
                .doesNotContain("15478536542")       // the product identifier this run is for
                .doesNotContain("88991122")          // a vendor item id
                .doesNotContain("전선몰딩")           // a product title
                .doesNotContain("몰딩공방")           // another title
                .doesNotContain("12900")             // a price
                .doesNotContain("FINAL_DELIVERY")    // a status value
                .doesNotContain("2026-08-05")        // a timestamp value
                .doesNotContain("ORDTOK-99");        // even the paging token
    }

    @Test
    void theOrderObservationAnswersWhetherALineNamesAProductAndHowOften() throws Exception {
        List<String> watched = observedOrder().watchedKeyLines();

        // The question the approved run exists to answer: is sellerProductId there, and filled? Present
        // on both lines, non-null on one — which is the distinction a plain row count could not make.
        assertThat(watched).anySatisfy(line -> assertThat(line)
                .startsWith("watched sellerProductId = ")
                .contains("present=2/2")
                .contains("nonNull=1"));
        // An empty string is not filled, so a key that is always blank reads as present-and-empty rather
        // than as an identifier we failed to use.
        assertThat(watched).anySatisfy(line -> assertThat(line)
                .startsWith("watched vendorItemName = ")
                .contains("nonNull=1"));
        // And the second question: no delivery timestamp is in this response, which is why the per-order
        // history endpoint (and its cost) is the only route to one. An ABSENT line is a measurement.
        assertThat(watched).contains("watched deliveredDate = ABSENT");
        assertThat(watched).contains("watched inTransitDateTime = ABSENT");
        assertThat(watched).contains("watched invoiceNumber = ABSENT");
        assertThat(watched).contains("watched deliveryCompanyName = ABSENT");
    }

    @Test
    void theTwoWatchListsAreSeparateSoNeitherReportsTheOtherSurfacesAbsences() throws Exception {
        assertThat(observedOrder().watchedKeyLines())
                .hasSize(CoupangWireShapeObserver.ORDER_WATCHED_KEYS.size());
        // A product-catalogue key is not reported as ABSENT by an order observation: a dozen confident
        // ABSENT lines about fields nobody expected here would bury the four that matter.
        assertThat(String.join("\n", observedOrder().watchedKeyLines()))
                .doesNotContain("displayCategoryCode").doesNotContain("notices").doesNotContain("images");
        // And the default constructor still watches the product list, byte for byte as before.
        assertThat(observed().watchedKeyLines()).hasSize(CoupangWireShapeObserver.WATCHED_KEYS.size());
    }

    @Test
    void bothWireShapeFlagsDefaultToOffInTheConfiguration() throws Exception {
        // The one property of this instrumentation that a reader cannot check by reading the observer:
        // whether anybody is running it. A default flipped to true in a later edit would arm a second
        // parse of every page on every deployment, for an observation nobody asked for — and the two
        // flags are separate so that turning on the one you need does not arm the other.
        String config = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src/main/java/com/sellerops/connector/coupang/CoupangConnectorConfiguration.java"));
        assertThat(config).contains("${sellerops.connector.coupang.product-wire-shape:false}");
        assertThat(config).contains("${sellerops.connector.coupang.order-wire-shape:false}");
        assertThat(config).doesNotContain("wire-shape:true");
    }
}
