package com.sellerops.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.collect.CollectNowRouter.Path;
import com.sellerops.connector.DataType;
import com.sellerops.responsibility.aside.AsideRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The routing table, written out.</b>
 *
 * <p>A seller presses 지금 수집 and does not know — must not need to know — that their Cafe24 리뷰 come through
 * an official API while their NAVER 리뷰 are read off a screen by a program on their own Mac. This is the table
 * that decides, and it is stated here in full because the whole value of moving the decision out of the browser
 * is that there is now one answer per channel × data type instead of one per screen.
 *
 * <p>These tests call a static method and touch nothing: no flag, no row, no clock, no connector. That is the
 * property being pinned as much as the table itself — a route that depended on how this process booted would
 * send the same seller's 문의 through an API on one environment and a browser on another, with nothing on screen
 * saying which.
 */
class CollectNowRoutingTest {

    @Test
    @DisplayName("리뷰: NAVER and Coupang are read from the seller's screen, Cafe24 by its official API")
    void reviewsGoWhereTheChannelLeavesThemReachable() {
        // Neither marketplace publishes a seller review API — NAVER said so itself (2024-08-30), and Coupang's
        // catalogue was counted in full (2026-08-14). The screen read is not a workaround for a missing
        // integration; it IS the channel's path, and the seller's press is what opens it.
        assertThat(CollectNowRouter.pathFor("NAVER", DataType.REVIEW)).isEqualTo(Path.SCREEN_READ);
        assertThat(CollectNowRouter.pathFor("COUPANG", DataType.REVIEW)).isEqualTo(Path.SCREEN_READ);
        assertThat(CollectNowRouter.pathFor("CAFE24", DataType.REVIEW)).isEqualTo(Path.API);
    }

    @Test
    @DisplayName("문의: all three channels publish an official API, so all three use it")
    void inquiriesGoThroughTheApiEverywhere() {
        assertThat(CollectNowRouter.pathFor("NAVER", DataType.INQUIRY)).isEqualTo(Path.API);
        assertThat(CollectNowRouter.pathFor("COUPANG", DataType.INQUIRY)).isEqualTo(Path.API);
        assertThat(CollectNowRouter.pathFor("CAFE24", DataType.INQUIRY)).isEqualTo(Path.API);
    }

    @Test
    @DisplayName("a published screen read never outranks a published API — NAVER 문의 is the case that proves it")
    void theApiWinsWhereverItExists() {
        // NAVER is the only channel with BOTH: an official 상품문의 read resource and a screen-read recipe
        // (NAVER_PRODUCT_INQUIRY_OBSERVE_V1, for the unattended lane). If the router preferred whatever it
        // found first, this pair would silently become a browser read on every environment where the inquiry
        // flags happen to be off — the same data, a different acquisition, and nothing on screen to say so.
        assertThat(AsideRecipe.forScreenRead("NAVER", DataType.INQUIRY))
                .contains(AsideRecipe.NAVER_PRODUCT_INQUIRY_OBSERVE_V1);
        assertThat(CollectNowRouter.pathFor("NAVER", DataType.INQUIRY)).isEqualTo(Path.API);
    }

    @Test
    @DisplayName("주문·매출 and 상품 are API everywhere: the button's existing behaviour is unchanged")
    void theRoutesThatAlreadyWorkedStillGoWhereTheyWent() {
        for (String channel : new String[] {"NAVER", "COUPANG", "CAFE24"}) {
            assertThat(CollectNowRouter.pathFor(channel, DataType.ORDER_SUMMARY)).as("%s", channel)
                    .isEqualTo(Path.API);
            assertThat(CollectNowRouter.pathFor(channel, DataType.PRODUCT)).as("%s", channel)
                    .isEqualTo(Path.API);
        }
    }

    @Test
    @DisplayName("no route is invented for a channel that has neither — it says so")
    void nothingIsInventedWhereNothingExists() {
        assertThat(CollectNowRouter.pathFor(null, DataType.REVIEW)).isEqualTo(Path.UNSUPPORTED);
        assertThat(CollectNowRouter.pathFor("NAVER", null)).isEqualTo(Path.UNSUPPORTED);
        // An unknown channel keeps the API route rather than being handed to a browser: an unconfigured
        // connector fails closed with a message about the connection, which is the honest answer for a channel
        // nobody has audited. See CollectNowRouter#channelPublishesApi.
        assertThat(CollectNowRouter.pathFor("GMARKET", DataType.REVIEW)).isEqualTo(Path.API);
    }

    @Test
    @DisplayName("the legacy NAVER Excel export is not a route this table can choose")
    void theExportIsNeverChosenForTheSeller() {
        // It is a real path and it carried the demo organisation's whole review corpus, but it needs the seller
        // standing at their marketplace downloading a file. That is a guided action a person starts, not a route
        // a button may silently take — so the only two values this table ever yields for a collectable pair are
        // the API and the screen read.
        for (String channel : new String[] {"NAVER", "COUPANG", "CAFE24"}) {
            for (DataType type : DataType.values()) {
                assertThat(CollectNowRouter.pathFor(channel, type))
                        .as("%s/%s", channel, type)
                        .isIn(Path.API, Path.SCREEN_READ);
            }
        }
    }
}
