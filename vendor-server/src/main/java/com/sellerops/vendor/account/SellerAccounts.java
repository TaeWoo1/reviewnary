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
 */
@Service
public class SellerAccounts {

    public static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 128;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s]{2,}");

    public enum Status { ACTIVE, SUSPENDED }

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
