package com.sellerops.vendor.account;

import com.sellerops.vendor.Codes;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Seller accounts exist only by invitation</b> — contract §2, D2.
 *
 * <p>There is no sign-up route. The operator issues an invite for one e-mail address ({@code invite} command,
 * {@link com.sellerops.vendor.ops.OpsCommands}); the seller redeems it once, choosing a password; from then on the
 * account logs in. Suspension is a status, read on every authenticated request (sessions and installs alike), so it
 * takes effect on the next request and lifting it restores the same installs.
 *
 * <p><b>Recovery</b> (contract §2): a seller who lost their mailbox or password is not invited again — an invite never
 * redeems into an address that already has an account. The operator, having checked who is asking, issues a recovery
 * invite tied to the existing account ({@link #issueRecovery}); redeeming it sets a new password (and the new address,
 * if one was given) on that same account and ends its sessions. Stores, tasks and PC links stay; the operator reviews
 * the PCs separately. A changed address is unverified again until a step-up code reaches it.
 */
@Service
public class SellerAccounts {

    public static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 128;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s]{2,}");

    public enum Status { ACTIVE, SUSPENDED }

    /** What an invite is for: a new account, or a recovery of {@code accountId}'s. */
    public record Invite(String email, boolean recovery) {}

    /** Why a redemption did not create an account. One message per case the seller can act on. */
    public enum RedeemFailure { INVALID_INVITE, WEAK_PASSWORD, ALREADY_REGISTERED }

    public static final class RedeemException extends RuntimeException {
        private final RedeemFailure failure;

        RedeemException(RedeemFailure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public RedeemFailure failure() {
            return failure;
        }
    }

    private final JdbcClient jdbc;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    /** Compared against when the e-mail is unknown, so a login takes the same time either way. */
    private final String decoyHash;
    private final Clock clock;

    @Autowired
    public SellerAccounts(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public SellerAccounts(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.decoyHash = passwords.encode(Codes.newCode());
    }

    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Issues an invite and returns its code — the only time the code exists in plain text. */
    @Transactional
    public String issueInvite(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("not an e-mail address");
        }
        String code = Codes.newCode();
        jdbc.sql("insert into seller_invites (code_hash, email, expires_at) values (?, ?, ?)")
                .params(Codes.hash(code), email, ts(clock.instant().plus(INVITE_LIFETIME)))
                .update();
        return code;
    }

    /**
     * A recovery invite for an existing account, to its current address or to {@code rawNewEmail}. Throws
     * {@link IllegalArgumentException} for a malformed address and {@link IllegalStateException} when the new address
     * already belongs to another account.
     */
    @Transactional
    public String issueRecovery(UUID accountId, String rawNewEmail) {
        String current = emailOf(accountId).orElseThrow(() -> new IllegalArgumentException("no such account"));
        String email = rawNewEmail == null || rawNewEmail.isBlank() ? current : normalizeEmail(rawNewEmail);
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("not an e-mail address");
        }
        if (!email.equals(current) && idOf(email).isPresent()) {
            throw new IllegalStateException("address in use");
        }
        String code = Codes.newCode();
        jdbc.sql("insert into seller_invites (code_hash, email, expires_at, account_id) values (?, ?, ?, ?)")
                .params(Codes.hash(code), email, ts(clock.instant().plus(INVITE_LIFETIME)), accountId)
                .update();
        return code;
    }

    /**
     * The address a still-usable invite was issued to — what the join page shows before anything is decided, so the
     * person sees which account they are about to make. Empty for a spent, expired or unknown code.
     */
    @Transactional(readOnly = true)
    public Optional<String> inviteEmail(String code) {
        return invite(code).map(Invite::email);
    }

    /** {@link #inviteEmail}, and whether the invite recovers an existing account. */
    @Transactional(readOnly = true)
    public Optional<Invite> invite(String code) {
        return jdbc.sql("select email, account_id from seller_invites "
                        + "where code_hash = ? and redeemed_at is null and expires_at > ?")
                .params(Codes.hash(code == null ? "" : code.trim()), ts(clock.instant()))
                .query((rs, n) -> new Invite(rs.getString("email"), rs.getObject("account_id") != null))
                .optional();
    }

    /** Spends the invite and creates the account. The invite is consumed in the same transaction. */
    @Transactional
    public UUID redeem(String code, String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new RedeemException(RedeemFailure.WEAK_PASSWORD);
        }
        Instant now = clock.instant();
        String hash = Codes.hash(code == null ? "" : code.trim());
        int spent = jdbc.sql("update seller_invites set redeemed_at = ? "
                        + "where code_hash = ? and redeemed_at is null and expires_at > ?")
                .params(ts(now), hash, ts(now))
                .update();
        if (spent != 1) {
            throw new RedeemException(RedeemFailure.INVALID_INVITE);
        }
        String email = jdbc.sql("select email from seller_invites where code_hash = ?").param(hash)
                .query(String.class).single();
        UUID recovering = jdbc.sql("select account_id from seller_invites where code_hash = ?").param(hash)
                .query(UUID.class).optional().orElse(null);
        if (recovering != null) {
            return recover(recovering, email, password, now);
        }
        if (jdbc.sql("select count(*) from seller_accounts where email = ?").param(email)
                .query(Integer.class).single() > 0) {
            throw new RedeemException(RedeemFailure.ALREADY_REGISTERED);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into seller_accounts (id, email, password_hash, status, created_at) values (?, ?, ?, ?, ?)")
                .params(id, email, passwords.encode(password), Status.ACTIVE.name(), ts(now))
                .update();
        return id;
    }

    /**
     * The recovery half of {@link #redeem}: the same account, a new password, the invite's address. Its sessions end —
     * every one issued before this moment; the session the join page gets next is issued after it.
     */
    private UUID recover(UUID accountId, String email, String password, Instant now) {
        if (jdbc.sql("select count(*) from seller_accounts where email = ? and id <> ?").params(email, accountId)
                .query(Integer.class).single() > 0) {
            throw new RedeemException(RedeemFailure.ALREADY_REGISTERED);
        }
        jdbc.sql("update seller_accounts set password_hash = ?, "
                        + "email_verified_at = case when email = ? then email_verified_at else null end, email = ?, "
                        + "sessions_not_before = ? where id = ?")
                .params(passwords.encode(password), email, email, ts(now.minusMillis(1)), accountId)
                .update();
        return accountId;
    }

    /** The account a correct, active login names; empty for every failure alike. */
    @Transactional(readOnly = true)
    public Optional<UUID> login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        String given = password == null ? "" : password;
        Optional<Row> row = jdbc.sql("select id, password_hash, status from seller_accounts where email = ?")
                .param(email)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("password_hash"),
                        rs.getString("status")))
                .optional();
        if (row.isEmpty()) {
            passwords.matches(given, decoyHash);
            return Optional.empty();
        }
        boolean matches = passwords.matches(given, row.get().passwordHash());
        return matches && Status.ACTIVE.name().equals(row.get().status()) ? Optional.of(row.get().id())
                : Optional.empty();
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID accountId) {
        return jdbc.sql("select count(*) from seller_accounts where id = ? and status = ?")
                .params(accountId, Status.ACTIVE.name())
                .query(Integer.class).single() > 0;
    }

    /** The account's sign-in address — a linked PC shows which server account it receives for. */
    @Transactional(readOnly = true)
    public Optional<String> emailOf(UUID accountId) {
        return jdbc.sql("select email from seller_accounts where id = ?").param(accountId)
                .query(String.class).optional();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> idOf(String rawEmail) {
        return jdbc.sql("select id from seller_accounts where email = ?").param(normalizeEmail(rawEmail))
                .query(UUID.class).optional();
    }

    /** Suspends by e-mail. False when no such account exists. */
    @Transactional
    public boolean suspend(String rawEmail) {
        return jdbc.sql("update seller_accounts set status = ?, suspended_at = ? where email = ?")
                .params(Status.SUSPENDED.name(), ts(clock.instant()), normalizeEmail(rawEmail))
                .update() == 1;
    }

    /** Lifts a suspension. False when no such account exists. */
    @Transactional
    public boolean reinstate(String rawEmail) {
        return jdbc.sql("update seller_accounts set status = ?, suspended_at = null where email = ?")
                .params(Status.ACTIVE.name(), normalizeEmail(rawEmail))
                .update() == 1;
    }

    private record Row(UUID id, String passwordHash, String status) {}

    static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
