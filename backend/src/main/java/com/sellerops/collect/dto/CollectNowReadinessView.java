package com.sellerops.collect.dto;

import com.sellerops.collect.CollectNowRouter;
import com.sellerops.localagent.LocalAgentRunState;

/**
 * <b>Whether 지금 수집 can be offered for one account and data type, and what to say instead when it cannot.</b>
 *
 * <p>The screen asks this per row and renders the answer; it never works the answer out. That is the point of
 * the endpoint existing at all — the browser used to decide by looking at a connector capability flag, which
 * is why the two channels whose reviews have no API had no button at all.
 *
 * @param path       the route this row would take. {@link CollectNowRouter.Path#UNSUPPORTED} means there is
 *                   nothing to offer, and the row says so in the product's own words
 * @param localAgent on the screen-read route, the state of the seller's own desk — UNPAIRED / BUSY /
 *                   AUTH_REQUIRED / READY. {@code null} on the API route, where there is no desk involved
 */
public record CollectNowReadinessView(CollectNowRouter.Path path, LocalAgentRunState localAgent) {

    public static CollectNowReadinessView of(CollectNowRouter.Path path, LocalAgentRunState localAgent) {
        return new CollectNowReadinessView(path, localAgent);
    }
}
