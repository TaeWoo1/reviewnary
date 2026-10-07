package com.sellerops.localagent;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.ApiException;
import com.sellerops.connector.DataType;
import com.sellerops.responsibility.aside.AsideDispatch;
import com.sellerops.responsibility.aside.AsideHelperDevices;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>지금 수집 — the seller asks their own local agent to read one of their own channel screens, once.</b>
 *
 * <p>This is the operator lane of the local-agent substrate. It adds no runtime, no workflow and no recipe: it
 * is a door onto the dispatch primitive that was previously reachable only from a responsibility run. The read
 * that happens is byte-for-byte the read that lane performs — same published recipe, same bound workflow on the
 * helper, same store fence, same canonical ingest.
 *
 * <p><b>The authorisation is the press, and that is a product decision, not a shortcut.</b> An authenticated
 * seller, their own organisation, an account that organisation owns, a read-only recipe the server chose for
 * that account's channel, and a bound of one page. Nothing else is required and nothing else may be asked for:
 * no scheduler, no Self-Pilot, no responsibility activation, no deployment allow-list, and — explicitly — no
 * typed phrase. A product button that demanded a ceremony would be training sellers to perform one.
 *
 * <p>The assistant-driven proof-run ceremony in {@code docs/sellerops_live_approval_contract.md} §3 is
 * untouched and separate. It governs a developer pointing this product at a live marketplace to measure
 * something; it was never the contract for a seller using the product they bought.
 *
 * <p><b>What the client cannot say.</b> Not a recipe name, not a URL, not a device, not a page count, not an
 * organisation. It names an account it owns and a kind of data; everything else is derived here.
 */
@Service
public class ScreenReadService {

    private final ScheduledAsideJobService jobs;
    private final AsideHelperDevices devices;
    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;

    public ScreenReadService(ScheduledAsideJobService jobs, AsideHelperDevices devices,
                             SellerAccountRepository accounts, ChannelRepository channels) {
        this.jobs = jobs;
        this.devices = devices;
        this.accounts = accounts;
        this.channels = channels;
    }

    /**
     * Start one read, or re-find the one this request already started.
     *
     * @param requestId the client's own id for this press, which is what makes a double-click, a retried
     *                  fetch or a reloaded page converge on one job instead of queueing two. Optional: without
     *                  one the one-live-job-per-device rule still refuses a second, which is safe but reads as
     *                  a conflict rather than as «that is already running».
     */
    @Transactional
    public ScreenReadView start(UUID orgId, UUID sellerAccountId, String dataTypeRaw, String requestId) {
        SellerAccount account = accounts.findByIdAndOrgId(sellerAccountId, orgId)
                .orElseThrow(() -> ApiException.notFound("판매 계정을 찾을 수 없습니다."));
        Channel channel = channels.findById(account.getChannelId())
                .orElseThrow(() -> ApiException.notFound("채널을 찾을 수 없습니다."));
        DataType dataType = parse(dataTypeRaw);
        AsideRecipe recipe = AsideRecipe.forScreenRead(channel.getCode(), dataType)
                // Not «unknown data type»: this channel's screen is not one the product knows how to read for
                // that kind of data, which is a capability fact and is said as one.
                .orElseThrow(() -> ApiException.badRequest(
                        channel.getNameKo() + " 화면에서 이 자료를 읽는 방법은 아직 없습니다."));
        AsideDispatch dispatch = AsideDispatch.operator(orgId, account.getId(), recipe,
                clientJobId(recipe, account.getId(), requestId));
        ScheduledAsideJob job = jobs.dispatch(dispatch);
        return ScreenReadView.of(job, true);
    }

    /** Where one read got to. Org-scoped: another organisation's job reads as absent, not as forbidden. */
    @Transactional(readOnly = true)
    public ScreenReadView status(UUID orgId, UUID jobId) {
        ScheduledAsideJob job = jobs.byId(jobId)
                .filter(j -> orgId.equals(j.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("해당 확인 작업을 찾을 수 없습니다."));
        return ScreenReadView.of(job, true);
    }

    /**
     * Whether a read can be started for this account at all, without starting one.
     *
     * <p>The screen asks before drawing a 지금 수집 it cannot honour, and the four answers are four different
     * next moves for the seller — which is why they are four words and not one boolean:
     *
     * <ul>
     *   <li>{@code supported == false} — this channel's screen is not one the product reads for this kind of
     *   data. A capability fact; the state beside it means nothing.</li>
     *   <li>{@link LocalAgentRunState#UNPAIRED} — link the helper once, on 연결.</li>
     *   <li>{@link LocalAgentRunState#BUSY} — wait; the desk is already reading.</li>
     *   <li>{@link LocalAgentRunState#AUTH_REQUIRED} — sign in at the channel's own seller centre, in their own
     *   browser. Derived from the last finished read, never from inspecting a marketplace session.</li>
     *   <li>{@link LocalAgentRunState#READY} — press it.</li>
     * </ul>
     */
    @Transactional
    public ScreenReadReadinessView readiness(UUID orgId, UUID sellerAccountId, String dataTypeRaw) {
        SellerAccount account = accounts.findByIdAndOrgId(sellerAccountId, orgId)
                .orElseThrow(() -> ApiException.notFound("판매 계정을 찾을 수 없습니다."));
        Channel channel = channels.findById(account.getChannelId())
                .orElseThrow(() -> ApiException.notFound("채널을 찾을 수 없습니다."));
        Optional<AsideRecipe> recipe = AsideRecipe.forScreenRead(channel.getCode(), parse(dataTypeRaw));
        boolean linked = devices.linked(orgId).isPresent();
        return new ScreenReadReadinessView(recipe.isPresent(),
                LocalAgentRunState.desk(linked, linked && jobs.busy(orgId), authExpired(orgId, account, recipe)));
    }

    /** Whether the last finished read of this very screen was turned away at the channel's sign-in. */
    private boolean authExpired(UUID orgId, SellerAccount account, Optional<AsideRecipe> recipe) {
        return recipe.flatMap(r -> jobs.lastFinished(orgId, account.getId(), r))
                .map(job -> LocalAgentRunState.of(job) == LocalAgentRunState.AUTH_REQUIRED)
                .orElse(false);
    }

    private DataType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("확인할 자료 종류가 필요합니다.");
        }
        try {
            return DataType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("확인할 수 있는 자료 종류가 아닙니다.");
        }
    }

    /**
     * The idempotency key, derived rather than accepted whole.
     *
     * <p>It carries the recipe tag and the account so two presses on different accounts or different data
     * types can never collide, and the client's own id so two presses on the SAME one do. The id is bounded at
     * 64 characters by the column, and the client's part is bounded and character-checked here — a key is an
     * identifier, not a place to put text.
     */
    private String clientJobId(AsideRecipe recipe, UUID accountId, String requestId) {
        String own = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId.trim();
        if (own.length() > 40 || !own.matches("[A-Za-z0-9_-]+")) {
            throw ApiException.badRequest("요청 식별자 형식이 올바르지 않습니다.");
        }
        return "op-" + recipe.jobTag() + "-" + accountId.toString().substring(0, 8) + "-" + own;
    }
}
