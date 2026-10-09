package com.sellerops.collect;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.collect.dto.CollectNowReadinessView;
import com.sellerops.collect.dto.ReviewCatchUpPlanView;
import com.sellerops.coverage.AcquisitionHistory;
import com.sellerops.coverage.ReviewCatchUpPlan;
import com.sellerops.coverage.ReviewCoverage;
import com.sellerops.coverage.ReviewCoverageCursor;
import com.sellerops.collect.dto.CollectNowView;
import com.sellerops.common.ApiException;
import com.sellerops.connector.DataType;
import com.sellerops.localagent.LocalAgentRunState;
import com.sellerops.localagent.ScreenReadReadinessView;
import com.sellerops.localagent.ScreenReadService;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * <b>지금 수집 — one door, whatever this channel's reviews actually travel through.</b>
 *
 * <p>The product's promise is that a seller presses once and SellerOps uses the right route. Before this
 * service the promise was kept by the browser, which called the pull-connector endpoint for every row and
 * hid the button on the rows where that would not work — so on NAVER and Coupang 리뷰, the two channels with
 * no review API, the product looked like it could not collect reviews at all while a proven screen read sat
 * one unreachable call away.
 *
 * <p><b>What moved, and what deliberately did not.</b> The decision moved to {@link CollectNowRouter}, which
 * reads facts this repository already holds. The two executions did not move at all: an API row runs the same
 * {@link CollectControlService#manualSync} it always ran, and a screen-read row runs the same
 * {@link ScreenReadService} dispatch the local-agent substrate already published. Nothing here is a third way
 * of collecting anything.
 *
 * <p><b>The authorisation is the press.</b> An authenticated seller, their own organisation, an account that
 * organisation owns, a data type, and a route the server chose. No scheduler, no Self-Pilot, no responsibility
 * activation, no deployment allow-list, and no typed phrase — the §3 approval ceremony in
 * {@code docs/sellerops_live_approval_contract.md} governs an assistant pointing this product at a live
 * marketplace to measure something, and it was never the contract for a seller using the product they bought.
 */
@Service
public class CollectNowService {

    /** What a client branches on when there is no route at all. A 400 with a sentence, and this code. */
    public static final String UNSUPPORTED_CODE = "ACQUISITION_UNSUPPORTED";

    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;
    private final CollectControlService pulls;
    private final ScreenReadService screenReads;
    /**
     * The two acquisition facts this row shows beside its button. Optional so a context assembled without it
     * answers exactly as it did before — a row then simply says less, never something untrue.
     */
    private final AcquisitionHistory history;
    /**
     * Where this channel's reviews are read through. Optional for the same reason as above — and when it is
     * absent the row says nothing about a boundary rather than claiming there is none.
     */
    private final ReviewCoverageCursor coverage;
    /**
     * The catch-up intents, read only to answer «is one of them waiting on a sign-in». Optional for the same
     * reason the two above are: a deployment without the lane has none, and that is not a failure.
     */
    private final com.sellerops.coverage.catchup.ReviewCatchUpRunRepository catchUpRuns;
    /**
     * The seller's automatic-check setting, read only to answer «is this row waiting for a sign-in». Optional
     * for the same reason the two above are: a deployment without the lane has none, and that is not a failure.
     */
    private com.sellerops.autocheck.ReviewAutoCheckService autoCheck;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAutoCheck(com.sellerops.autocheck.ReviewAutoCheckService autoCheck) {
        this.autoCheck = autoCheck;
    }

    public CollectNowService(SellerAccountRepository accounts, ChannelRepository channels,
                             CollectControlService pulls, ScreenReadService screenReads) {
        this(accounts, channels, pulls, screenReads, null, null);
    }

    public CollectNowService(SellerAccountRepository accounts, ChannelRepository channels,
                             CollectControlService pulls, ScreenReadService screenReads,
                             AcquisitionHistory history) {
        this(accounts, channels, pulls, screenReads, history, null, null);
    }

    /** The shape before a paused catch-up was visible here. */
    public CollectNowService(SellerAccountRepository accounts, ChannelRepository channels,
                             CollectControlService pulls, ScreenReadService screenReads,
                             AcquisitionHistory history, ReviewCoverageCursor coverage) {
        this(accounts, channels, pulls, screenReads, history, coverage, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CollectNowService(SellerAccountRepository accounts, ChannelRepository channels,
                             CollectControlService pulls, ScreenReadService screenReads,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             AcquisitionHistory history,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             ReviewCoverageCursor coverage,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             com.sellerops.coverage.catchup.ReviewCatchUpRunRepository catchUpRuns) {
        this.history = history;
        this.coverage = coverage;
        this.catchUpRuns = catchUpRuns;
        this.accounts = accounts;
        this.channels = channels;
        this.pulls = pulls;
        this.screenReads = screenReads;
    }

    /**
     * Collect this data type for this account, by whichever route is canonical for its channel.
     *
     * @param requestId the client's own id for this press. On the screen-read route it is what makes a
     *                  double-click, a retried fetch or a reloaded page converge on one job instead of two.
     */
    public CollectNowView collectNow(UUID orgId, UUID sellerAccountId, String dataTypeRaw, String requestId) {
        Routed routed = route(orgId, sellerAccountId, dataTypeRaw);
        return switch (routed.path()) {
            case API -> CollectNowView.ofPull(routed.dataType().name(),
                    pulls.manualSync(orgId, sellerAccountId, routed.dataType().name()));
            case SCREEN_READ -> CollectNowView.ofScreenRead(routed.dataType().name(),
                    screenReads.start(orgId, sellerAccountId, routed.dataType().name(), requestId));
            case UNSUPPORTED -> throw unsupported(routed.channel());
        };
    }

    /**
     * <b>판매자가 로그인했다 — 그것을 기다리던 일만 이어간다.</b>
     *
     * <p>Empty means nothing was waiting, which is the normal answer and is not an error: signing in is not an
     * instruction to collect. Only a run that stopped at the sign-in wall continues, and it continues as the
     * run it already was — a walk the automatic check started stays that walk, and one a press started stays
     * that one. Which it is, the backend knows; the client is not asked to decide.
     *
     * <p>Nothing about the marketplace session is stored by this call. It reads a row in this database that says
     * 「이 의도는 로그인을 기다리고 있다」 and transitions it; the signed-in-ness itself remains, by product
     * decision, something this product never records.
     */
    public java.util.Optional<com.sellerops.localagent.ScreenReadView> resumeAfterSignIn(
            UUID orgId, UUID sellerAccountId, String dataTypeRaw) {
        Routed routed = route(orgId, sellerAccountId, dataTypeRaw);
        if (routed.path() != CollectNowRouter.Path.SCREEN_READ) {
            // An API row has no walk and no sign-in wall; there is nothing a sign-in could continue.
            return java.util.Optional.empty();
        }
        return screenReads.resumeAfterSignIn(orgId, sellerAccountId, routed.dataType().name());
    }

    /**
     * What this row can offer, without collecting anything.
     *
     * <p>On the screen-read route the desk state comes from {@link ScreenReadService#readiness}, which is the
     * one place that knows it. A route the screen read does not actually publish is reported as UNSUPPORTED
     * here rather than as a READY button that would 400 on press — the router and the recipe table agree
     * today, and if they ever stopped agreeing the honest answer is the narrower one.
     */
    public CollectNowReadinessView readiness(UUID orgId, UUID sellerAccountId, String dataTypeRaw) {
        Routed routed = route(orgId, sellerAccountId, dataTypeRaw);
        return switch (routed.path()) {
            case API -> withHistory(CollectNowRouter.Path.API, null, orgId, sellerAccountId, routed);
            case SCREEN_READ -> {
                ScreenReadReadinessView desk =
                        screenReads.readiness(orgId, sellerAccountId, routed.dataType().name());
                yield desk.supported()
                        ? withHistory(CollectNowRouter.Path.SCREEN_READ, desk.state(), orgId, sellerAccountId, routed)
                        : CollectNowReadinessView.of(CollectNowRouter.Path.UNSUPPORTED, null);
            }
            case UNSUPPORTED -> CollectNowReadinessView.of(CollectNowRouter.Path.UNSUPPORTED, null);
        };
    }

    /**
     * The answer, plus what this channel × type holds — so one screen can say 「마지막 성공 수집 9월 2일」 and
     * 「최근 수집 시 로그인이 필요했습니다」 together instead of losing the first to the second.
     */
    private CollectNowReadinessView withHistory(CollectNowRouter.Path path, LocalAgentRunState desk,
                                                UUID orgId, UUID sellerAccountId, Routed routed) {
        String dataType = routed.dataType().name();
        // <b>멈춘 의도는 acquisition history와 무관하다.</b> history가 없는 배포에서도 「로그인을 기다리는
        // catch-up이 있다」는 사실은 참일 수 있고, 그 사실이 없으면 판매자는 로그인한 뒤에도 이어갈 길이 없다.
        boolean paused = pausedCatchUp(sellerAccountId, dataType);
        boolean pausedSignIn = paused || autoCheckParkedOnSignIn(sellerAccountId);
        if (history == null) {
            return new CollectNowReadinessView(path, desk, null, null, null, null, paused, pausedSignIn);
        }
        UUID channelId = routed.channel().getId();
        ReviewCoverage held = reviewCoverage(orgId, channelId, dataType);
        return new CollectNowReadinessView(path, desk,
                history.lastSuccessAt(orgId, channelId, dataType),
                history.latestAttempt(orgId, channelId, dataType).outcome(),
                held.coverageThrough(), gapDays(held), paused, pausedSignIn);
    }

    /**
     * <b>The dry plan for this row — read-only.</b>
     *
     * <p>Only reviews have a boundary to plan against: an API pull asks the channel for everything newer than
     * what it holds and the channel answers for the whole store, so there is no period for a seller to be
     * missing. Asking for a plan on any other data type is therefore answered with an empty one rather than a
     * fabricated gap.
     */
    public ReviewCatchUpPlanView catchUpPlan(UUID orgId, UUID sellerAccountId, String dataTypeRaw) {
        Routed routed = route(orgId, sellerAccountId, dataTypeRaw);
        ReviewCoverage held = reviewCoverage(orgId, routed.channel().getId(), routed.dataType().name());
        ReviewCatchUpPlan plan = ReviewCatchUpPlan.from(held, today(), ReviewCatchUpPlan.Limits.OPERATOR_PRESS);
        // <b>Whether a past period can be read at all is a property of the carrier, not of the plan.</b> Until
        // 2026-10-08 no lane could select one — the screen read opened a route and read whatever period the
        // page was showing — and a plan that did not say so would have read as a schedule. The screen-read
        // route now has READ navigation for this list, so the answer is the route: a period this product can
        // go and look at, or a row whose data arrives by API and has no period to be missing.
        //
        // It is a statement about this deployment, not about the seller's desk. A helper bundled before the
        // navigation says so at run time by settling the window as «this desk could not do it» — there is no
        // way to ask it from here, and guessing would make this field the least reliable thing on the screen.
        return ReviewCatchUpPlanView.of(held, plan, routed.path() == CollectNowRouter.Path.SCREEN_READ);
    }

    /**
     * <b>Is a catch-up for this row waiting on a sign-in, right now?</b>
     *
     * <p>Not «did the last attempt meet a sign-in wall» — that is {@code latestAttemptOutcome}, it is about the
     * past, and it stays true after the seller has signed in. On 2026-10-09 a seller signed in, was told
     * 「로그인 확인됨」, and nothing continued: the thing they were resuming was a row in this database and the
     * screen had no way to see it. This is that row.
     */
    private boolean pausedCatchUp(UUID sellerAccountId, String dataType) {
        if (catchUpRuns == null) {
            return false;
        }
        return catchUpRuns.findFirstBySellerAccountIdAndDataTypeAndStateIn(sellerAccountId, dataType,
                java.util.EnumSet.of(com.sellerops.coverage.catchup.ReviewCatchUpState.PAUSED_AUTH)).isPresent();
    }

    /**
     * Whether this account's automatic check is parked on a sign-in wall.
     *
     * <p>The widest true statement of 「이 줄이 로그인을 기다린다」, and the one a paused walk cannot make: once
     * history is closed the check reads a single day with no walk behind it, so the row that holds the wall is
     * the setting's. Optional dependency — a deployment without the lane answers false, which is correct there.
     */
    private boolean autoCheckParkedOnSignIn(UUID sellerAccountId) {
        if (autoCheck == null) {
            return false;
        }
        return autoCheck.find(sellerAccountId)
                .map(row -> row.on()
                        && row.getPausedReason() == com.sellerops.autocheck.AutoCheckPause.PAUSED_AUTH)
                .orElse(false);
    }

    private ReviewCoverage reviewCoverage(UUID orgId, UUID channelId, String dataType) {
        if (coverage == null || !"REVIEW".equals(dataType)) {
            return ReviewCoverage.NONE;
        }
        return coverage.of(orgId, channelId, today());
    }

    private static LocalDate today() {
        return LocalDate.now(ReviewCoverageCursor.KST);
    }

    /** Days between the boundary and today that nothing has read. Null when there is no boundary to count from. */
    private static Long gapDays(ReviewCoverage held) {
        if (held.coverageThrough() == null) {
            return null;
        }
        return held.gaps().stream().mapToLong(com.sellerops.coverage.CoveredWindow::days).sum();
    }

    /** The account, its channel, the parsed data type and the route — resolved once, the same way for both calls. */
    private Routed route(UUID orgId, UUID sellerAccountId, String dataTypeRaw) {
        SellerAccount account = accounts.findByIdAndOrgId(sellerAccountId, orgId)
                // Org scoping at the query boundary: another organisation's account reads as absent, which is
                // the only thing a caller may learn about it.
                .orElseThrow(() -> ApiException.notFound("판매 계정을 찾을 수 없습니다."));
        Channel channel = channels.findById(account.getChannelId())
                .orElseThrow(() -> ApiException.notFound("채널을 찾을 수 없습니다."));
        DataType dataType = parse(dataTypeRaw);
        return new Routed(channel, dataType, CollectNowRouter.pathFor(channel.getCode(), dataType));
    }

    private record Routed(Channel channel, DataType dataType, CollectNowRouter.Path path) {
    }

    private ApiException unsupported(Channel channel) {
        return new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, UNSUPPORTED_CODE,
                channel.getNameKo() + "에서 이 자료를 가져오는 경로가 아직 없습니다.");
    }

    private DataType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("가져올 자료 종류가 필요합니다.");
        }
        try {
            return DataType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("가져올 수 있는 자료 종류가 아닙니다.");
        }
    }
}
