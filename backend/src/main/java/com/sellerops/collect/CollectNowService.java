package com.sellerops.collect;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.collect.dto.CollectNowReadinessView;
import com.sellerops.collect.dto.CollectNowView;
import com.sellerops.common.ApiException;
import com.sellerops.connector.DataType;
import com.sellerops.localagent.LocalAgentRunState;
import com.sellerops.localagent.ScreenReadReadinessView;
import com.sellerops.localagent.ScreenReadService;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
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

    public CollectNowService(SellerAccountRepository accounts, ChannelRepository channels,
                             CollectControlService pulls, ScreenReadService screenReads) {
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
            case API -> CollectNowReadinessView.of(CollectNowRouter.Path.API, null);
            case SCREEN_READ -> {
                ScreenReadReadinessView desk =
                        screenReads.readiness(orgId, sellerAccountId, routed.dataType().name());
                yield desk.supported()
                        ? CollectNowReadinessView.of(CollectNowRouter.Path.SCREEN_READ, desk.state())
                        : CollectNowReadinessView.of(CollectNowRouter.Path.UNSUPPORTED, null);
            }
            case UNSUPPORTED -> CollectNowReadinessView.of(CollectNowRouter.Path.UNSUPPORTED, null);
        };
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
