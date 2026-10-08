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
 * @param lastSuccessAt when this account's data type was last actually read. <b>Here so that one screen can
 *                   hold both sentences at once</b> — 「마지막 성공 수집 9월 2일」 beside 「최근 수집 시 로그인이
 *                   필요했습니다」. The row already makes this call; a second request for the second fact would
 *                   be two answers that can arrive out of order and disagree
 * @param latestAttemptOutcome how the most recent attempt ended, or null when none is on record. <b>About the
 *                   last attempt, not about now:</b> AUTH_REQUIRED here means 「그때 로그인이 필요했다」, and a
 *                   screen that renders it as 「지금 로그인 필요」 is claiming a live check nobody made
 */
public record CollectNowReadinessView(CollectNowRouter.Path path, LocalAgentRunState localAgent,
                                      java.time.Instant lastSuccessAt,
                                      com.sellerops.coverage.AcquisitionAttemptOutcome latestAttemptOutcome) {

    /** The shape for a row with no history to report — the API route, and anything unsupported. */
    public static CollectNowReadinessView of(CollectNowRouter.Path path, LocalAgentRunState localAgent) {
        return new CollectNowReadinessView(path, localAgent, null, null);
    }
}
