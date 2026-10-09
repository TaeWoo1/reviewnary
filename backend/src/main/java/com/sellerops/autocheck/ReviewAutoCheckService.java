package com.sellerops.autocheck;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.connector.DataType;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.AutoCheckAuthority;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The seller's automatic-review-check setting: on by default, off only when they say so.</b>
 *
 * <p>Three jobs, and the first is the one that decides what kind of thing this is:
 *
 * <ul>
 *   <li><b>A row appears by itself.</b> A seller who connects NAVER gets automatic checking without a consent
 *   screen, a device choice or an interval choice — connecting a channel is the instruction to keep it current,
 *   and the product says so in one line while they connect it. The row is created lazily, on first read or on
 *   the reconciler's first look, so it exists whether or not the scheduler flag is on in this deployment.</li>
 *   <li><b>Turning it off is permanent until they turn it on.</b> {@code revoked_at} is a tombstone: the
 *   create-if-missing rule never resurrects an off row, because a setting that switched itself back on an hour
 *   after a seller switched it off is the worst thing in this file's reach.</li>
 *   <li><b>It answers the dispatch gate</b> ({@link AutoCheckAuthority}), per job, with no cache. Turning it
 *   off takes effect on the next window of a walk already under way, not at the end of one.</li>
 * </ul>
 *
 * <p><b>Nothing here knows about a device.</b> That is the whole shape of the correction this class embodies: a
 * helper is how the reading happens, resolved per job by the dispatcher, and its 180-day token, its re-install
 * and its replacement machine are none of this setting's business.
 */
@Service
public class ReviewAutoCheckService implements AutoCheckAuthority {

    /** REVIEW is the only data type with a boundary to be missing and a screen to read it from. */
    public static final String DATA_TYPE = "REVIEW";

    private final ReviewAutoCheckRepository rows;
    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;
    private final Clock clock;

    /**
     * The container's constructor, named because there are two.
     *
     * <p>With more than one candidate and none annotated, Spring falls back to looking for a no-arg constructor
     * and the whole context fails to start — the trap {@code AsideMarketplaceAccess} documents, and it cost this
     * change one full-context suite already.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewAutoCheckService(ReviewAutoCheckRepository rows, SellerAccountRepository accounts,
                                  ChannelRepository channels) {
        this(rows, accounts, channels, Clock.systemUTC());
    }

    public ReviewAutoCheckService(ReviewAutoCheckRepository rows, SellerAccountRepository accounts,
                                  ChannelRepository channels, Clock clock) {
        this.rows = rows;
        this.accounts = accounts;
        this.channels = channels;
        this.clock = clock;
    }

    /**
     * The setting as the screen should draw it. <b>Read-only — looking at a setting never creates one.</b>
     *
     * <p>It used to create the row if the account deserved one, so that a seller who connected and opened 설정
     * ten seconds later was not told «off» while the connect screen had just said «on». That reasoning was
     * about the window between connecting and the first background tick, and that window no longer exists:
     * finishing a connection creates the row itself ({@link ReviewAutoCheckConnectionListener}).
     *
     * <p>What remained was a GET that wrote, and that is the wrong shape for two reasons. A read that turns
     * something on cannot be told apart from a read that reports something already on — so nobody can answer
     * «who switched this account's automatic checking on» by looking. And any path that reaches this method for
     * an account — an admin view, a support tool, a listing that renders many rows — would be switching
     * settings on as a side effect of being looked at.
     *
     * <p>An account with no row therefore reads as {@link ReviewAutoCheckView#UNSUPPORTED} when it has no
     * readable screen, and as off-with-no-row when it was connected before this feature existed. The second
     * one is what the explicit per-organisation backfill is for ({@link #backfillForOrg}); it is not something
     * a screen fixes by being opened.
     */
    @Transactional(readOnly = true)
    public ReviewAutoCheckView view(UUID orgId, UUID sellerAccountId) {
        Optional<SellerAccount> account = accounts.findByIdAndOrgId(sellerAccountId, orgId);
        if (account.isEmpty() || recipeFor(account.get()).isEmpty()) {
            return ReviewAutoCheckView.UNSUPPORTED;
        }
        return rows.findBySellerAccountIdAndDataType(sellerAccountId, DATA_TYPE)
                .filter(row -> orgId.equals(row.getOrgId()))
                .map(ReviewAutoCheckView::of)
                // 읽을 화면은 있는데 행이 없다: 이 기능 전에 연결한 계정이다. 「꺼짐」으로 그리는 것이 맞고,
                // 그것을 켜는 것은 그 조직의 명시적 backfill이지 화면을 열어 보는 일이 아니다.
                .orElse(new ReviewAutoCheckView(true, false, null));
    }

    /**
     * The seller's switch. Off writes the tombstone; on clears it and makes the account due immediately.
     *
     * <p>This is the one read-write path a seller drives, so it may create the row: turning the setting on for
     * an account that never had one is the same decision as having it on, stated explicitly. {@link #view} may
     * not, because looking is not deciding.
     */
    @Transactional
    public ReviewAutoCheckView set(UUID orgId, UUID sellerAccountId, UUID userId, boolean on) {
        ReviewAutoCheck row = ensure(orgId, sellerAccountId)
                // Not «forbidden»: there is no screen to read for this account, so there is no setting to set.
                .orElseThrow(() -> ApiException.badRequest("이 판매 계정은 자동 확인을 지원하지 않습니다."));
        Instant now = clock.instant();
        if (on) {
            row.setEnabled(true);
            row.setRevokedAt(null);
            row.setConsentedBy(userId);
            row.setConsentedAt(now);
            // Due now, so switching it on does something a seller can see rather than something they wait for.
            row.setNextCheckAt(now);
            row.setPausedReason(null);
        } else {
            row.setEnabled(false);
            row.setRevokedAt(now);
            row.setNextCheckAt(null);
            row.setPausedReason(null);
        }
        return ReviewAutoCheckView.of(rows.save(row));
    }

    /**
     * <b>The dispatch gate's answer.</b> This account, this recipe, right now.
     *
     * <p>The recipe is checked against the one this account's own channel publishes for reviews, not merely
     * «some review recipe»: a setting for a NAVER store must not authorise a job naming another channel's
     * screen, even though no caller can name one today.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean allows(UUID orgId, UUID sellerAccountId, AsideRecipe recipe) {
        if (orgId == null || sellerAccountId == null || recipe == null) {
            return false;
        }
        Optional<SellerAccount> account = accounts.findByIdAndOrgId(sellerAccountId, orgId);
        if (account.isEmpty() || !recipeFor(account.get()).filter(recipe::equals).isPresent()) {
            return false;
        }
        return rows.findBySellerAccountIdAndDataType(sellerAccountId, DATA_TYPE)
                .filter(row -> orgId.equals(row.getOrgId()))
                .filter(ReviewAutoCheck::on)
                .isPresent();
    }

    /**
     * <b>Switch automatic checking on for the accounts this organisation already has.</b>
     *
     * <p>A one-time move for the accounts that were connected before this feature existed, and it is scoped to
     * <b>one organisation</b> — the caller's own. That scope is the whole design: there is no sweep over every
     * organisation in a database anywhere in this lane, so a backend that happens to hold a fixture or
     * benchmark organisation never picks it up, and the alternative — a list of organisations for product code
     * to skip — does not have to exist.
     *
     * <p>Idempotent: an account that already has a row, on or off, is not a candidate. A seller who switched
     * the setting off and then asked for a backfill stays off, because the off row is the memory of that.
     */
    @Transactional
    public int backfillForOrg(UUID orgId) {
        if (orgId == null) {
            return 0;
        }
        int created = 0;
        for (UUID accountId : rows.accountsWithoutRow(orgId)) {
            if (ensure(orgId, accountId).isPresent()) {
                created++;
            }
        }
        return created;
    }

    /** The row for one account, without creating one. For the reconciler, which has already selected it. */
    @Transactional(readOnly = true)
    public Optional<ReviewAutoCheck> find(UUID sellerAccountId) {
        return rows.findBySellerAccountIdAndDataType(sellerAccountId, DATA_TYPE);
    }

    /**
     * Create the default-on row when this account has a review screen and has finished connecting.
     *
     * <p><b>Three callers, all of them an explicit act inside one organisation:</b> the connection completing,
     * the seller switching the setting on, and that organisation's own one-time backfill. Not a screen being
     * looked at ({@link #view} is read-only), and not a background loop
     * ({@code ReviewAutoCheckReconciler#tick} creates nothing).
     *
     * <p>Empty for every account that has no screen read for reviews — a Cafe24 store whose reviews arrive by
     * API has nothing for this lane to do and must not grow a setting that would read as «off». Empty too while
     * a connection is still PENDING: a store that is not connected yet cannot be read, and a row created then
     * would start a cadence against a screen nobody can open.
     */
    @Transactional
    public Optional<ReviewAutoCheck> ensure(UUID orgId, UUID sellerAccountId) {
        Optional<ReviewAutoCheck> existing = rows.findBySellerAccountIdAndDataType(sellerAccountId, DATA_TYPE)
                .filter(row -> orgId.equals(row.getOrgId()));
        if (existing.isPresent()) {
            return existing;
        }
        SellerAccount account = accounts.findByIdAndOrgId(sellerAccountId, orgId).orElse(null);
        if (account == null || account.isFileUpload()
                || account.getConnectionStatus() != ChannelStatus.CONNECTED
                || recipeFor(account).isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        ReviewAutoCheck row = new ReviewAutoCheck();
        row.setOrgId(orgId);
        row.setSellerAccountId(sellerAccountId);
        row.setDataType(DATA_TYPE);
        row.setMode("READ_ONLY");
        row.setEnabled(true);
        row.setConsentedAt(now);
        row.setIntervalMinutes(ReviewAutoCheck.INTERVAL_MINUTES);
        row.setNextCheckAt(now);
        return Optional.of(rows.save(row));
    }

    /**
     * Which recipe reads this account's reviews off a screen <b>for a period we name</b>, if any.
     *
     * <p>The second half is the scope of this whole lane. A channel whose runner cannot move the screen's
     * period gets no setting — not an off one — because an unattended read of «whatever the screen is showing»
     * cannot say afterwards what it covered ({@link AsideRecipe#readsNamedPeriod()}). Today that is Coupang
     * 상품평, which stays the seller's own press until its runner can select a period.
     */
    public Optional<AsideRecipe> recipeFor(SellerAccount account) {
        return channels.findById(account.getChannelId())
                .map(Channel::getCode)
                .flatMap(code -> AsideRecipe.forScreenRead(code, DataType.REVIEW))
                .filter(AsideRecipe::readsNamedPeriod);
    }
}
