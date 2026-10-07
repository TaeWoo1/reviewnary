package com.sellerops.collect;

import com.sellerops.connector.ChannelApiGapRegistry;
import com.sellerops.connector.DataType;
import com.sellerops.connector.UnsupportedScope;
import com.sellerops.responsibility.aside.AsideRecipe;

/**
 * <b>Which way SellerOps collects one kind of data for one channel — decided here, once, for everyone.</b>
 *
 * <p>The seller presses 지금 수집. They are not told, and must not have to know, that their Cafe24 리뷰 arrive
 * through an official API while their NAVER 리뷰 are read off a screen by a program on their own Mac. Before
 * this class the knowledge lived in the browser: the 지금 수집하기 button called the pull-connector endpoint
 * unconditionally, and for the two channels whose reviews have no API the button was simply not drawn. The
 * seller's conclusion was the honest reading of what they saw — 「이 제품은 네이버 리뷰를 못 가져온다」 — while a
 * published recipe, a bound workflow and a proven read sat on the other side of a door with no handle.
 *
 * <p><b>Why the decision is a marketplace fact and not a deployment one.</b> The obvious implementation —
 * «API if the resolved connector supports the type, else the screen» — makes the answer depend on which flags
 * this process booted with. A NAVER 문의 would then arrive by API on one environment and off a screen on
 * another, for the same seller and the same data, with nothing on screen saying which. So the first question is
 * the one that cannot change between environments: does the channel publish an official API for this data type
 * at all? {@link ChannelApiGapRegistry} is where that is already written down, channel by channel, with its
 * evidence — 쿠팡 리뷰 API 없음 (catalogue counted 2026-08-14), 네이버 리뷰 API 없음 (official maintainer,
 * 2024-08-30).
 *
 * <ul>
 *   <li><b>The channel publishes an API</b> → {@link Path#API}. The existing pull run, unchanged, including the
 *   way it fails closed when this connection is not configured for it. A connection problem must read as a
 *   connection problem; silently reading the data off a screen instead would hide it.</li>
 *   <li><b>The channel publishes none, and a screen read is published</b> → {@link Path#SCREEN_READ}: the local
 *   agent's one-shot operator dispatch.</li>
 *   <li><b>Neither</b> → {@link Path#UNSUPPORTED}, said plainly rather than attempted.</li>
 * </ul>
 *
 * <p><b>What this deliberately never chooses.</b> The legacy NAVER 리뷰 Excel export. It is a real path and it
 * brought the demo organisation's whole review corpus, but it needs the seller to stand at their marketplace
 * and download a file — it is a guided action a person starts, not a route a button may silently take. It stays
 * where it is, reachable by its own entry point, and no fallback here leads to it.
 */
public final class CollectNowRouter {

    /** The canonical route for one channel × data type. */
    public enum Path {
        /** The channel's official API, through the pull connector — {@code POST /sync}'s existing run. */
        API,
        /** The seller's own local agent, reading their own channel screen once. */
        SCREEN_READ,
        /** No route exists. Not a failure to report as one: an answer. */
        UNSUPPORTED,
    }

    private CollectNowRouter() {
    }

    /**
     * The canonical route, from the channel code and the data type and nothing else.
     *
     * <p>Static and pure on purpose: the routing table is a contract a test can state in full, and a decision
     * that reads no flag, no row and no clock cannot drift between two environments.
     */
    public static Path pathFor(String channelCode, DataType dataType) {
        if (channelCode == null || dataType == null) {
            return Path.UNSUPPORTED;
        }
        if (channelPublishesApi(channelCode, dataType)) {
            return Path.API;
        }
        return AsideRecipe.forScreenRead(channelCode, dataType).isPresent()
                ? Path.SCREEN_READ
                : Path.UNSUPPORTED;
    }

    /**
     * Whether the marketplace itself offers a read API for this data type.
     *
     * <p>Asked as «has anyone written down that it does NOT», which is the shape the registry has. Its own
     * javadoc is explicit that an empty answer means nobody audited the channel rather than that the channel
     * publishes everything — and for this decision that default is the safe one: an unaudited channel keeps the
     * API route, where an unconfigured connector fails closed with a message about the connection, instead of
     * being quietly handed to a browser on the seller's desk.
     */
    private static boolean channelPublishesApi(String channelCode, DataType dataType) {
        String gap = dataType.name() + "_API";
        return ChannelApiGapRegistry.gapsFor(channelCode).stream()
                .map(UnsupportedScope::code)
                .noneMatch(gap::equals);
    }
}
