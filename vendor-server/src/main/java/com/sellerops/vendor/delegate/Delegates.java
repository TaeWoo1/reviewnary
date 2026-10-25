package com.sellerops.vendor.delegate;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.task.ReplyTasks;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Delegates: one person, one account, password and authenticator code</b> — contract §2, D2 · D3.
 *
 * <p>A delegate is the vendor's own staff member who, invited by a seller into their store as a sub-manager on the
 * marketplace, fetches that store's review file and posts the replies the seller approved — by hand, with their own
 * marketplace account. This class knows nothing about that account: it decides only who may sign in here.
 *
 * <ol>
 *   <li><b>Invite-only.</b> The operator issues an invite for one address and a role ({@code invite-delegate}).</li>
 *   <li><b>Enrolment is two calls and the invite is spent by the second.</b> {@link #stage} takes the invite and a
 *       password and hands back a TOTP secret; {@link #confirm} takes the invite and the first code from the
 *       authenticator app. Only a correct code creates the account — so nobody ends up with an account whose second
 *       factor never reached a phone — and it returns ten single-use recovery codes, shown once.</li>
 *   <li><b>Every sign-in needs both factors.</b> A password with an authenticator code, or with an unused recovery
 *       code. A code's 30-second step is accepted once.</li>
 *   <li><b>Or Google, instead of both</b> (owner, 2026-10-26). The invite is spent by a Google account whose verified
 *       address is the invited one ({@link #joinWithGoogle}); that person then has no password or authenticator here
 *       and is found by Google's account number alone ({@link #googleLogin}). One person uses one way, never both.
 *       The round trip to Google is {@link GoogleDelegates}.</li>
 *   <li><b>Suspension ends the person's sessions at the next request and ends their store assignments.</b>
 *       Reinstating restores the account, not the assignments — those are given again on purpose. Work a suspended
 *       delegate had already taken on is settled by the task lane (contract §2: it becomes {@code UNKNOWN}, never
 *       silently handed to someone else).</li>
 * </ol>
 */
@Service
public class Delegates {

    public static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    public static final int RECOVERY_CODES = 10;
    static final String ISSUER = "reviewnary";
    private static final char[] RECOVERY_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789".toCharArray();
    private static final int RECOVERY_LENGTH = 10;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s]{2,}");

    public enum Role { DELEGATE, ADMIN }

    public enum Status { ACTIVE, SUSPENDED }

    /** Why an enrolment call did not go through. One per case the invitee can act on. */
    public enum EnrolFailure { INVALID_INVITE, WEAK_PASSWORD, ALREADY_REGISTERED, NOT_STAGED, WRONG_CODE, EMAIL_MISMATCH }

    public static final class EnrolException extends RuntimeException {
        private final EnrolFailure failure;

        EnrolException(EnrolFailure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public EnrolFailure failure() {
            return failure;
        }
    }

    /** What the invitee types or scans into an authenticator app. */
    public record Staged(String secret, String otpauthUri) {}

    /** The new account and its recovery codes — the only time those codes exist in plain text. */
    public record Enrolled(UUID delegateId, List<String> recoveryCodes) {}

    public record Active(UUID id, String email, Role role) {}

    private final JdbcClient jdbc;
    private final TotpSecretBox box;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    /** Compared against when the e-mail is unknown, so a sign-in takes the same time either way. */
    private final String decoyHash;
    private final Clock clock;

    @Autowired
    public Delegates(JdbcClient jdbc, TotpSecretBox box) {
        this(jdbc, box, Clock.systemUTC());
    }

    public Delegates(JdbcClient jdbc, TotpSecretBox box, Clock clock) {
        this.jdbc = jdbc;
        this.box = box;
        this.clock = clock;
        this.decoyHash = passwords.encode(Codes.newCode());
    }

    /** Issues an invite and returns its code — the only time the code exists in plain text. */
    @Transactional
    public String issueInvite(String rawEmail, Role role) {
        String email = SellerAccounts.normalizeEmail(rawEmail);
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("not an e-mail address");
        }
        String code = Codes.newCode();
        jdbc.sql("insert into delegate_invites (code_hash, email, role, expires_at) values (?, ?, ?, ?)")
                .params(Codes.hash(code), email, role.name(), ts(clock.instant().plus(INVITE_LIFETIME)))
                .update();
        return code;
    }

    /** Stages a password and a fresh TOTP secret on a live invite. Calling it again replaces both. */
    @Transactional
    public Staged stage(String code, String password) {
        if (password == null || password.length() < SellerAccounts.MIN_PASSWORD_LENGTH
                || password.length() > SellerAccounts.MAX_PASSWORD_LENGTH) {
            throw new EnrolException(EnrolFailure.WEAK_PASSWORD);
        }
        Instant now = clock.instant();
        String hash = Codes.hash(code == null ? "" : code.trim());
        Optional<String> email = liveInviteEmail(hash, now);
        if (email.isEmpty()) {
            throw new EnrolException(EnrolFailure.INVALID_INVITE);
        }
        if (registered(email.get())) {
            throw new EnrolException(EnrolFailure.ALREADY_REGISTERED);
        }
        byte[] secret = Totp.newSecret();
        jdbc.sql("update delegate_invites set staged_password = ?, staged_totp_secret = ? where code_hash = ?")
                .params(passwords.encode(password), box.seal(secret), hash)
                .update();
        String base32 = Totp.base32(secret);
        String label = URLEncoder.encode(ISSUER + ":" + email.get(), StandardCharsets.UTF_8).replace("+", "%20");
        return new Staged(base32, "otpauth://totp/" + label + "?secret=" + base32 + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS);
    }

    /** Spends the invite on a correct first code, creates the account and returns its recovery codes. */
    @Transactional
    public Enrolled confirm(String code, String otp) {
        Instant now = clock.instant();
        String hash = Codes.hash(code == null ? "" : code.trim());
        Optional<StagedRow> staged = jdbc.sql("select email, role, staged_password, staged_totp_secret "
                        + "from delegate_invites where code_hash = ? and redeemed_at is null and expires_at > ?")
                .params(hash, ts(now))
                .query((rs, n) -> new StagedRow(rs.getString("email"), rs.getString("role"),
                        rs.getString("staged_password"), rs.getBytes("staged_totp_secret")))
                .optional();
        if (staged.isEmpty()) {
            throw new EnrolException(EnrolFailure.INVALID_INVITE);
        }
        if (staged.get().passwordHash() == null || staged.get().sealedSecret() == null) {
            throw new EnrolException(EnrolFailure.NOT_STAGED);
        }
        OptionalLong step = Totp.matchingStep(box.open(staged.get().sealedSecret()), otp, now);
        if (step.isEmpty()) {
            throw new EnrolException(EnrolFailure.WRONG_CODE);
        }
        int spent = jdbc.sql("update delegate_invites set redeemed_at = ?, staged_password = null, "
                        + "staged_totp_secret = null where code_hash = ? and redeemed_at is null")
                .params(ts(now), hash)
                .update();
        if (spent != 1) {
            throw new EnrolException(EnrolFailure.INVALID_INVITE);
        }
        if (registered(staged.get().email())) {
            throw new EnrolException(EnrolFailure.ALREADY_REGISTERED);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into delegates (id, email, password_hash, totp_secret, totp_last_step, role, status, "
                        + "created_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, staged.get().email(), staged.get().passwordHash(), staged.get().sealedSecret(),
                        step.getAsLong(), staged.get().role(), Status.ACTIVE.name(), ts(now))
                .update();
        List<String> codes = new ArrayList<>(RECOVERY_CODES);
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String raw = Codes.fromAlphabet(RECOVERY_ALPHABET, RECOVERY_LENGTH);
            jdbc.sql("insert into delegate_recovery_codes (delegate_id, code_hash) values (?, ?)")
                    .params(id, Codes.hash(raw))
                    .update();
            codes.add(raw.substring(0, RECOVERY_LENGTH / 2) + "-" + raw.substring(RECOVERY_LENGTH / 2));
        }
        return new Enrolled(id, List.copyOf(codes));
    }

    /**
     * The delegate a correct, active sign-in names; empty for every failure alike. Exactly one second factor:
     * an authenticator code, or else a recovery code, which is spent.
     */
    @Transactional
    public Optional<UUID> login(String rawEmail, String password, String otp, String recoveryCode) {
        String email = SellerAccounts.normalizeEmail(rawEmail);
        String given = password == null ? "" : password;
        Optional<Row> row = jdbc.sql("select id, password_hash, totp_secret, status from delegates where email = ?")
                .param(email)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("password_hash"),
                        rs.getBytes("totp_secret"), rs.getString("status")))
                .optional();
        if (row.isEmpty() || row.get().passwordHash() == null) {
            // Unknown, or signs in with Google: the same work and the same answer as a wrong password.
            passwords.matches(given, decoyHash);
            return Optional.empty();
        }
        if (!passwords.matches(given, row.get().passwordHash()) || !Status.ACTIVE.name().equals(row.get().status())) {
            return Optional.empty();
        }
        boolean hasOtp = otp != null && !otp.isBlank();
        boolean hasRecovery = recoveryCode != null && !recoveryCode.isBlank();
        if (hasOtp == hasRecovery) {
            return Optional.empty();
        }
        UUID id = row.get().id();
        if (hasOtp) {
            OptionalLong step = Totp.matchingStep(box.open(row.get().sealedSecret()), otp, clock.instant());
            if (step.isEmpty()) {
                return Optional.empty();
            }
            // A step is accepted once: the conditional update loses to any sign-in that already used it.
            int fresh = jdbc.sql("update delegates set totp_last_step = ? where id = ? "
                            + "and (totp_last_step is null or totp_last_step < ?)")
                    .params(step.getAsLong(), id, step.getAsLong())
                    .update();
            return fresh == 1 ? Optional.of(id) : Optional.empty();
        }
        String normalized = recoveryCode.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
        int used = jdbc.sql("update delegate_recovery_codes set used_at = ? "
                        + "where delegate_id = ? and code_hash = ? and used_at is null")
                .params(ts(clock.instant()), id, Codes.hash(normalized))
                .update();
        return used == 1 ? Optional.of(id) : Optional.empty();
    }

    /** What a live invite makes: for the join page to say before anything is chosen. */
    public record Invite(String email, Role role, Instant expiresAt) {}

    /** A live invite's address, role and deadline; empty when the code is unknown, spent or expired. */
    @Transactional(readOnly = true)
    public Optional<Invite> invite(String code) {
        return jdbc.sql("select email, role, expires_at from delegate_invites "
                        + "where code_hash = ? and redeemed_at is null and expires_at > ?")
                .params(Codes.hash(code == null ? "" : code.trim()), ts(clock.instant()))
                .query((rs, n) -> new Invite(rs.getString("email"), Role.valueOf(rs.getString("role")),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    /** The address a live invite was issued to, for Google to offer that account first; empty when not live. */
    @Transactional(readOnly = true)
    public Optional<String> inviteEmail(String code) {
        return liveInviteEmail(Codes.hash(code == null ? "" : code.trim()), clock.instant());
    }

    /**
     * Spends the invite (by its code's hash — the Google attempt keeps only that) on a Google account whose verified
     * address is the invited one, and creates the delegate with that account's number and no password or
     * authenticator. A different address leaves the invite live, so the invitee can come back with the right account.
     */
    // Refusals write nothing, so they need not roll back the caller's transaction (it spends the Google attempt).
    @Transactional(noRollbackFor = EnrolException.class)
    public UUID joinWithGoogle(String inviteHash, String googleSub, String googleEmail) {
        Instant now = clock.instant();
        String hash = inviteHash == null ? "" : inviteHash;
        Optional<StagedRow> invite = jdbc.sql("select email, role, staged_password, staged_totp_secret "
                        + "from delegate_invites where code_hash = ? and redeemed_at is null and expires_at > ?")
                .params(hash, ts(now))
                .query((rs, n) -> new StagedRow(rs.getString("email"), rs.getString("role"), null, null))
                .optional();
        if (invite.isEmpty()) {
            throw new EnrolException(EnrolFailure.INVALID_INVITE);
        }
        if (!invite.get().email().equals(SellerAccounts.normalizeEmail(googleEmail))) {
            throw new EnrolException(EnrolFailure.EMAIL_MISMATCH);
        }
        boolean subTaken = jdbc.sql("select count(*) from delegates where google_sub = ?").param(googleSub)
                .query(Integer.class).single() > 0;
        if (registered(invite.get().email()) || subTaken) {
            throw new EnrolException(EnrolFailure.ALREADY_REGISTERED);
        }
        int spent = jdbc.sql("update delegate_invites set redeemed_at = ?, staged_password = null, "
                        + "staged_totp_secret = null where code_hash = ? and redeemed_at is null")
                .params(ts(now), hash)
                .update();
        if (spent != 1) {
            throw new EnrolException(EnrolFailure.INVALID_INVITE);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into delegates (id, email, google_sub, role, status, created_at) values (?, ?, ?, ?, ?, ?)")
                .params(id, invite.get().email(), googleSub, invite.get().role(), Status.ACTIVE.name(), ts(now))
                .update();
        return id;
    }

    /** The active delegate registered with this Google account; empty when there is none or they are suspended. */
    @Transactional(readOnly = true)
    public Optional<UUID> googleLogin(String googleSub) {
        return jdbc.sql("select id from delegates where google_sub = ? and status = ?")
                .params(googleSub, Status.ACTIVE.name())
                .query(UUID.class)
                .optional();
    }

    /** The delegate behind a session, read on every request — empty once suspended. */
    @Transactional(readOnly = true)
    public Optional<Active> active(UUID delegateId) {
        return jdbc.sql("select id, email, role from delegates where id = ? and status = ?")
                .params(delegateId, Status.ACTIVE.name())
                .query((rs, n) -> new Active(rs.getObject("id", UUID.class), rs.getString("email"),
                        Role.valueOf(rs.getString("role"))))
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> idOf(String rawEmail) {
        return jdbc.sql("select id from delegates where email = ?")
                .param(SellerAccounts.normalizeEmail(rawEmail))
                .query(UUID.class)
                .optional();
    }

    @Transactional(readOnly = true)
    public int unusedRecoveryCodes(UUID delegateId) {
        return jdbc.sql("select count(*) from delegate_recovery_codes where delegate_id = ? and used_at is null")
                .param(delegateId)
                .query(Integer.class).single();
    }

    /** Suspends by e-mail and ends every store assignment. False when no such delegate exists. */
    @Transactional
    public boolean suspend(String rawEmail) {
        Optional<UUID> id = idOf(rawEmail);
        if (id.isEmpty()) {
            return false;
        }
        Instant now = clock.instant();
        jdbc.sql("update delegates set status = ?, suspended_at = ? where id = ?")
                .params(Status.SUSPENDED.name(), ts(now), id.get())
                .update();
        jdbc.sql("update store_assignments set ended_at = ? where delegate_id = ? and ended_at is null")
                .params(ts(now), id.get())
                .update();
        ReplyTasks.unknownForSuspended(jdbc, id.get(), now);
        return true;
    }

    /** Lifts a suspension; assignments are not restored. False when no such delegate exists. */
    @Transactional
    public boolean reinstate(String rawEmail) {
        return jdbc.sql("update delegates set status = ?, suspended_at = null where email = ?")
                .params(Status.ACTIVE.name(), SellerAccounts.normalizeEmail(rawEmail))
                .update() == 1;
    }

    private Optional<String> liveInviteEmail(String hash, Instant now) {
        return jdbc.sql("select email from delegate_invites where code_hash = ? and redeemed_at is null and expires_at > ?")
                .params(hash, ts(now))
                .query(String.class)
                .optional();
    }

    private boolean registered(String email) {
        return jdbc.sql("select count(*) from delegates where email = ?").param(email)
                .query(Integer.class).single() > 0;
    }

    private record StagedRow(String email, String role, String passwordHash, byte[] sealedSecret) {}

    private record Row(UUID id, String passwordHash, byte[] sealedSecret, String status) {}

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
