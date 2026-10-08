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
 * @param coverageThrough the last day up to which this channel's reviews have been read with nothing missing,
 *                   or null when no period evidence exists. <b>A third fact, not a restatement of the second:</b>
 *                   the 2026-10-08 NAVER read succeeded and covered 10-02 … 10-08, so 마지막 성공 was 10-08 while
 *                   this stayed 09-02. Only on the API route is it null by construction — a pull asks the channel
 *                   for the whole store and leaves no period for a seller to be missing
 * @param coverageGapDays how many days between that boundary and today nothing has read. Null with the boundary
 * @param pausedCatchUp whether a catch-up for this row is waiting on a sign-in, right now.
 *
 *     <p><b>Why a screen needs this.</b> {@code localAgent} and {@code latestAttemptOutcome} are about the
 *     LAST ATTEMPT, so {@code AUTH_REQUIRED} stays true after the seller has signed in — the product never
 *     asked «are you signed in now», and on 2026-10-09 that left a seller pressing 「판매자센터 로그인」 a
 *     second time, being told 「로그인 확인됨」, and nothing continuing. The intent they were trying to resume
 *     is a row in this database, and this is that row being visible. It says nothing about the marketplace
 *     session, which is deliberately stored nowhere ({@code SignInEndpoint}).
 */
public record CollectNowReadinessView(CollectNowRouter.Path path, LocalAgentRunState localAgent,
                                      java.time.Instant lastSuccessAt,
                                      com.sellerops.coverage.AcquisitionAttemptOutcome latestAttemptOutcome,
                                      java.time.LocalDate coverageThrough, Long coverageGapDays,
                                      boolean pausedCatchUp) {

    /** The shape for a row with no history to report — the API route, and anything unsupported. */
    public static CollectNowReadinessView of(CollectNowRouter.Path path, LocalAgentRunState localAgent) {
        return new CollectNowReadinessView(path, localAgent, null, null, null, null, false);
    }

    /** The shape before coverage existed — kept so a caller that knows only the two acquisition facts still compiles. */
    public static CollectNowReadinessView of(CollectNowRouter.Path path, LocalAgentRunState localAgent,
                                             java.time.Instant lastSuccessAt,
                                             com.sellerops.coverage.AcquisitionAttemptOutcome latestAttemptOutcome) {
        return new CollectNowReadinessView(path, localAgent, lastSuccessAt, latestAttemptOutcome, null, null, false);
    }
}
