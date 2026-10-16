package com.sellerops.vendor.install;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.account.SellerAccounts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Seller PCs registered to an account</b> — contract §2. A PC {@link #start starts} a grant with its public key,
 * the seller {@link #approve approves} the user code from a logged-in session, the PC {@link #redeem redeems} the
 * device code for an opaque token, and every later request is one {@link #authenticate lookup} by hash.
 *
 * <p>Revocation is per install (a lost PC) and immediate, because the token is looked up on every request. An
 * account's suspension ends every one of its installs at the next request without touching the rows, so lifting
 * it restores them (D2).
 */
@Service
public class Installs {

    /** Same as the backend's helper devices: a linked PC stays linked this long, then asks once more. */
    public static final Duration TOKEN_LIFETIME = Duration.ofDays(180);
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final Pattern DEVICE_NAME = Pattern.compile("[\\p{L}\\p{N} ._()'\\-]{1,80}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._+\\-]{1,40}");

    public enum StartFailure { INVALID_DEVICE_NAME, INVALID_VERSION, INVALID_PUBLIC_KEY, BUSY }

    public static final class StartException extends RuntimeException {
        private final StartFailure failure;

        StartException(StartFailure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public StartFailure failure() {
            return failure;
        }
    }

    public record Redeemed(String token, UUID installId, Instant expiresAt) {}

    public record Verdict(InstallGrants.PollOutcome outcome, Redeemed redeemed) {}

    public record Authenticated(UUID installId, UUID accountId) {}

    /** What a seller sees about each of their PCs. No token, no key. */
    public record View(UUID id, String deviceName, String appVersion, Instant createdAt, Instant lastUsedAt,
                       Instant expiresAt) {}

    private final InstallGrants grants;
    private final SellerAccounts accounts;
    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public Installs(InstallGrants grants, SellerAccounts accounts, JdbcClient jdbc) {
        this(grants, accounts, jdbc, Clock.systemUTC());
    }

    public Installs(InstallGrants grants, SellerAccounts accounts, JdbcClient jdbc, Clock clock) {
        this.grants = grants;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public InstallGrants.Issued start(String deviceName, String appVersion, String publicKey) {
        String name = deviceName == null ? "" : deviceName.trim();
        if (!DEVICE_NAME.matcher(name).matches()) {
            throw new StartException(StartFailure.INVALID_DEVICE_NAME);
        }
        String version = appVersion == null || appVersion.isBlank() ? null : appVersion.trim();
        if (version != null && !VERSION.matcher(version).matches()) {
            throw new StartException(StartFailure.INVALID_VERSION);
        }
        byte[] key = InstallPublicKey.parse(publicKey)
                .orElseThrow(() -> new StartException(StartFailure.INVALID_PUBLIC_KEY));
        return grants.issue(new InstallGrants.Pending(name, version, key))
                .orElseThrow(() -> new StartException(StartFailure.BUSY));
    }

    /** The seller's session is the approval. Returns the approved device name, or empty for an unknown code. */
    public Optional<String> approve(UUID accountId, String userCode) {
        if (!accounts.isActive(accountId)) {
            return Optional.empty();
        }
        return grants.approve(userCode, accountId);
    }

    @Transactional
    public Verdict redeem(String deviceCode) {
        InstallGrants.Poll poll = grants.poll(deviceCode);
        if (poll.outcome() != InstallGrants.PollOutcome.APPROVED) {
            return new Verdict(poll.outcome(), null);
        }
        if (!accounts.isActive(poll.accountId())) {
            return new Verdict(InstallGrants.PollOutcome.EXPIRED_TOKEN, null);
        }
        Instant now = clock.instant();
        String token = InstallTokens.mint();
        UUID id = UUID.randomUUID();
        Instant expiresAt = now.plus(TOKEN_LIFETIME);
        jdbc.sql("insert into installs (id, account_id, token_hash, public_key, device_name, app_version, created_at, "
                        + "expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, poll.accountId(), Codes.hash(token), poll.pending().publicKey(),
                        poll.pending().deviceName(), poll.pending().appVersion(), ts(now), ts(expiresAt))
                .update();
        return new Verdict(InstallGrants.PollOutcome.APPROVED, new Redeemed(token, id, expiresAt));
    }

    /**
     * One lookup per request. Empty for an unknown, revoked or expired token and for a suspended account — the
     * filter answers every empty with 401 and never says which.
     */
    @Transactional
    public Optional<Authenticated> authenticate(String token) {
        if (!InstallTokens.looksLikeInstallToken(token)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<Row> found = jdbc.sql("select i.id, i.account_id, i.last_used_at from installs i "
                        + "join seller_accounts a on a.id = i.account_id "
                        + "where i.token_hash = ? and i.revoked_at is null and i.expires_at > ? and a.status = ?")
                .params(Codes.hash(token), ts(now), SellerAccounts.Status.ACTIVE.name())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        instant(rs.getTimestamp("last_used_at"))))
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Row row = found.get();
        if (row.lastUsedAt() == null || row.lastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            jdbc.sql("update installs set last_used_at = ? where id = ?").params(ts(now), row.id()).update();
        }
        return Optional.of(new Authenticated(row.id(), row.accountId()));
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID accountId) {
        return jdbc.sql("select id, device_name, app_version, created_at, last_used_at, expires_at from installs "
                        + "where account_id = ? and revoked_at is null and expires_at > ? order by created_at desc")
                .params(accountId, ts(clock.instant()))
                .query((rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("device_name"),
                        rs.getString("app_version"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("last_used_at")), instant(rs.getTimestamp("expires_at"))))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<View> view(UUID accountId, UUID installId) {
        return list(accountId).stream().filter(v -> v.id().equals(installId)).findFirst();
    }

    /**
     * Revokes one of the account's installs. False when the account has no such install — another account's
     * install is indistinguishable from a missing one. Revoking a revoked install is not an error.
     */
    @Transactional
    public boolean revoke(UUID accountId, UUID installId) {
        int owned = jdbc.sql("select count(*) from installs where id = ? and account_id = ?")
                .params(installId, accountId).query(Integer.class).single();
        if (owned == 0) {
            return false;
        }
        jdbc.sql("update installs set revoked_at = ? where id = ? and revoked_at is null")
                .params(ts(clock.instant()), installId).update();
        return true;
    }

    /** The public key a relay result is sealed to (contract §5). */
    @Transactional(readOnly = true)
    public Optional<byte[]> publicKey(UUID installId) {
        return jdbc.sql("select public_key from installs where id = ?").param(installId)
                .query((rs, n) -> rs.getBytes("public_key")).optional();
    }

    private record Row(UUID id, UUID accountId, Instant lastUsedAt) {}

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
