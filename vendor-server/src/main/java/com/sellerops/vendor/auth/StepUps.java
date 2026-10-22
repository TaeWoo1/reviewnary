package com.sellerops.vendor.auth;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.mail.Mailer;
import com.sellerops.vendor.mail.OutboundMail;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The seller's step-up: a one-time code to the account's e-mail before a new way out of the account opens</b> —
 * contract §2 (2026-10-22, owner-agreed: e-mail code for the pilot).
 *
 * <ul>
 *   <li><b>Bound, single use.</b> A code belongs to one account, one session ({@code jti}) and one request — for a PC
 *       link, that PC's user code. Verifying it does not open anything by itself; {@link #spend} consumes the one
 *       verified code for that same request once, within {@link #TTL}. Ten minutes of "any PC" never exists.</li>
 *   <li><b>Failures do not reset.</b> Asking again issues a new code but the wrong tries are summed over every code
 *       of that request ({@link #MAX_FAILURES_PER_REQUEST}) and of the account in the last hour
 *       ({@link #MAX_FAILURES_PER_ACCOUNT}); issuing is capped the same way ({@link #MAX_CODES_PER_REQUEST},
 *       {@link #MAX_CODES_PER_ACCOUNT}).</li>
 *   <li><b>What it proves, and what not.</b> The first correct code records {@code email_verified_at}: the invite
 *       link proves nothing about the mailbox, the operator hands it over. A code by e-mail does not stop someone who
 *       holds the mailbox too, or a phishing page that relays the code — a pilot's added check, not strong
 *       authentication (OWASP MFA cheat sheet, "Email").</li>
 * </ul>
 */
@Service
public class StepUps {

    private static final Logger log = LoggerFactory.getLogger(StepUps.class);

    public static final Duration TTL = Duration.ofMinutes(10);
    public static final int MAX_CODES_PER_REQUEST = 3;
    public static final int MAX_FAILURES_PER_REQUEST = 5;
    public static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);
    public static final int MAX_CODES_PER_ACCOUNT = 10;
    public static final int MAX_FAILURES_PER_ACCOUNT = 10;

    public enum Purpose { PC_LINK }

    public enum Refusal {
        /** No mail can be delivered from this deployment. */
        MAIL_OFF,
        /** The mail server did not take it. */
        MAIL_FAILED,
        TOO_MANY,
        /** Too many wrong codes for this request or account: start over from the PC later. */
        LOCKED,
        /** No code for this request, or it ran out. */
        EXPIRED,
        WRONG_CODE
    }

    public static final class StepUpException extends RuntimeException {
        private final Refusal refusal;
        private final int left;

        StepUpException(Refusal refusal, int left) {
            super(refusal.name());
            this.refusal = refusal;
            this.left = left;
        }

        public Refusal refusal() {
            return refusal;
        }

        /** Wrong tries left for this request; meaningful for {@link Refusal#WRONG_CODE}. */
        public int left() {
            return left;
        }
    }

    /** Where the code went (the address masked) and until when it works. */
    public record Sent(String maskedTo, Instant expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final Clock clock;

    @Autowired
    public StepUps(JdbcClient jdbc, Mailer mailer) {
        this(jdbc, mailer, Clock.systemUTC());
    }

    public StepUps(JdbcClient jdbc, Mailer mailer, Clock clock) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.clock = clock;
    }

    /**
     * Mails a fresh code for this request. {@code mail} turns the code into the message — the caller words it
     * (what is being confirmed, which PC). Earlier unspent codes of the request stop working.
     */
    @Transactional(noRollbackFor = StepUpException.class)
    public Sent send(UUID accountId, String sessionId, Purpose purpose, String target, MailWording mail) {
        if (!mailer.deliverable()) {
            throw new StepUpException(Refusal.MAIL_OFF, 0);
        }
        Instant now = clock.instant();
        String targetHash = Codes.hash(target);
        lockAccount(accountId);
        if (failuresOfRequest(accountId, purpose, targetHash) >= MAX_FAILURES_PER_REQUEST
                || failuresOfAccount(accountId, now) >= MAX_FAILURES_PER_ACCOUNT) {
            throw new StepUpException(Refusal.LOCKED, 0);
        }
        int ofRequest = jdbc.sql("select count(*) from step_up_codes where account_id = ? and purpose = ? "
                        + "and target_hash = ?")
                .params(accountId, purpose.name(), targetHash).query(Integer.class).single();
        int ofAccount = jdbc.sql("select count(*) from step_up_codes where account_id = ? and issued_at > ?")
                .params(accountId, ts(now.minus(ACCOUNT_WINDOW))).query(Integer.class).single();
        if (ofRequest >= MAX_CODES_PER_REQUEST || ofAccount >= MAX_CODES_PER_ACCOUNT) {
            throw new StepUpException(Refusal.TOO_MANY, 0);
        }
        String email = jdbc.sql("select email from seller_accounts where id = ?").param(accountId)
                .query(String.class).single();
        // Only the newest code of a request works.
        jdbc.sql("update step_up_codes set expires_at = ? where account_id = ? and purpose = ? and target_hash = ? "
                        + "and verified_at is null and expires_at > ?")
                .params(ts(now), accountId, purpose.name(), targetHash, ts(now))
                .update();
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expiresAt = now.plus(TTL);
        jdbc.sql("insert into step_up_codes (id, account_id, session_id, purpose, target_hash, code_hash, issued_at, "
                        + "expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), accountId, sessionId, purpose.name(), targetHash, codeHash(code),
                        ts(now), ts(expiresAt))
                .update();
        try {
            mailer.send(mail.of(email, code));
        } catch (RuntimeException e) {
            // Counted as issued (it may have gone out); nothing about the mail or the code is logged.
            log.warn("step-up mail not sent: type={}", e.getClass().getSimpleName());
            throw new StepUpException(Refusal.MAIL_FAILED, 0);
        }
        return new Sent(mask(email), expiresAt);
    }

    /** Checks the code for this session's request. Right once: it waits to be {@link #spend spent}. */
    @Transactional(noRollbackFor = StepUpException.class)
    public void verify(UUID accountId, String sessionId, Purpose purpose, String target, String given) {
        Instant now = clock.instant();
        String targetHash = Codes.hash(target);
        lockAccount(accountId);
        int failures = failuresOfRequest(accountId, purpose, targetHash);
        if (failures >= MAX_FAILURES_PER_REQUEST || failuresOfAccount(accountId, now) >= MAX_FAILURES_PER_ACCOUNT) {
            throw new StepUpException(Refusal.LOCKED, 0);
        }
        Optional<Row> latest = jdbc.sql("select id, code_hash, expires_at, verified_at from step_up_codes "
                        + "where account_id = ? and session_id = ? and purpose = ? and target_hash = ? "
                        // Newest first; within one instant, the one a later send did not cut short.
                        + "and spent_at is null order by issued_at desc, expires_at desc limit 1")
                .params(accountId, sessionId, purpose.name(), targetHash)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("code_hash"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("verified_at") != null))
                .optional();
        if (latest.isEmpty() || !latest.get().expiresAt().isAfter(now)) {
            throw new StepUpException(Refusal.EXPIRED, 0);
        }
        String normalized = given == null ? "" : given.replaceAll("\\s", "");
        if (!MessageDigest.isEqual(latest.get().codeHash().getBytes(StandardCharsets.US_ASCII),
                codeHash(normalized).getBytes(StandardCharsets.US_ASCII))) {
            jdbc.sql("update step_up_codes set failed_attempts = failed_attempts + 1 where id = ?")
                    .param(latest.get().id()).update();
            int left = MAX_FAILURES_PER_REQUEST - failures - 1;
            throw new StepUpException(left <= 0 ? Refusal.LOCKED : Refusal.WRONG_CODE, Math.max(0, left));
        }
        if (!latest.get().verified()) {
            jdbc.sql("update step_up_codes set verified_at = ? where id = ?").params(ts(now), latest.get().id())
                    .update();
            jdbc.sql("update seller_accounts set email_verified_at = ? where id = ? and email_verified_at is null")
                    .params(ts(now), accountId).update();
        }
    }

    /**
     * Consumes this session's verified code for this request, once. True when there was one — the guarded action may
     * then go ahead; false and it must not.
     */
    @Transactional
    public boolean spend(UUID accountId, String sessionId, Purpose purpose, String target) {
        Instant now = clock.instant();
        return jdbc.sql("update step_up_codes set spent_at = ? where id = (select id from step_up_codes "
                        + "where account_id = ? and session_id = ? and purpose = ? and target_hash = ? "
                        + "and verified_at is not null and verified_at > ? and spent_at is null "
                        + "order by verified_at desc limit 1)")
                .params(ts(now), accountId, sessionId, purpose.name(), Codes.hash(target), ts(now.minus(TTL)))
                .update() == 1;
    }

    /** Turns the address and the code into the message. */
    @FunctionalInterface
    public interface MailWording {
        OutboundMail of(String to, String code);
    }

    /** {@code se***@example.com}: enough for the seller to recognise, not the whole address on the page. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    private void lockAccount(UUID accountId) {
        // One request at a time per account, so two parallel tries cannot both slip under a cap.
        jdbc.sql("select id from seller_accounts where id = ? for update").param(accountId).query(UUID.class).list();
    }

    private int failuresOfRequest(UUID accountId, Purpose purpose, String targetHash) {
        return jdbc.sql("select coalesce(sum(failed_attempts), 0) from step_up_codes where account_id = ? "
                        + "and purpose = ? and target_hash = ?")
                .params(accountId, purpose.name(), targetHash).query(Integer.class).single();
    }

    private int failuresOfAccount(UUID accountId, Instant now) {
        return jdbc.sql("select coalesce(sum(failed_attempts), 0) from step_up_codes where account_id = ? "
                        + "and issued_at > ?")
                .params(accountId, ts(now.minus(ACCOUNT_WINDOW))).query(Integer.class).single();
    }

    private static String codeHash(String code) {
        return Codes.hash("step-up:" + code);
    }

    private record Row(UUID id, String codeHash, Instant expiresAt, boolean verified) {}

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
