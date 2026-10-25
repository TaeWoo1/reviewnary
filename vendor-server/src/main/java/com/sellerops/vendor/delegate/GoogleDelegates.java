package com.sellerops.vendor.delegate;

import com.sellerops.vendor.Codes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>One round trip to Google, finished only by the tab that started it</b> — contract §2 (D3 revised 2026-10-26).
 *
 * <ol>
 *   <li>{@link #start}: the page sends what it wants (join with an invite, or sign in) and a random {@code binding}
 *       it keeps in its own sessionStorage. A row is written — state, nonce and PKCE verifier, the binding's hash —
 *       and the page goes to Google.</li>
 *   <li>{@link #callback}: Google comes back with a code. It is traded for the account at once and parked on the row
 *       with a one-time handoff; the browser is sent to {@code /delegate#google=<handoff>}. Nothing about accounts is
 *       decided here.</li>
 *   <li>{@link #finish}: the page sends the handoff with its binding. Only a match spends the row; then the invite
 *       becomes an account ({@link Delegates#joinWithGoogle}) or the account signs in
 *       ({@link Delegates#googleLogin}). A callback link someone else sends finishes nothing: that tab has no
 *       binding — this server keeps no cookies, so this is what ties the return to the start.</li>
 * </ol>
 *
 * <p>Every row lives ten minutes and is spent once; Retention deletes it after.
 */
@Service
public class GoogleDelegates {

    public static final Duration ATTEMPT_LIFETIME = Duration.ofMinutes(10);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final Logger log = LoggerFactory.getLogger(GoogleDelegates.class);

    public enum Purpose { JOIN, LOGIN }

    /** Why a Google round trip did not end in a session. One per case the person can act on. */
    public enum Failure { NOT_ENABLED, INVALID_INVITE, CANCELLED, GOOGLE_FAILED, EXPIRED, EMAIL_MISMATCH,
        ALREADY_REGISTERED, NOT_REGISTERED }

    public static final class GoogleException extends RuntimeException {
        private final Failure failure;

        GoogleException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    private final JdbcClient jdbc;
    private final GoogleSignIn google;
    private final Delegates delegates;
    private final Clock clock;

    @Autowired
    public GoogleDelegates(JdbcClient jdbc, GoogleSignIn google, Delegates delegates) {
        this(jdbc, google, delegates, Clock.systemUTC());
    }

    public GoogleDelegates(JdbcClient jdbc, GoogleSignIn google, Delegates delegates, Clock clock) {
        this.jdbc = jdbc;
        this.google = google;
        this.delegates = delegates;
        this.clock = clock;
    }

    public boolean enabled() {
        return google.enabled();
    }

    /** Writes the attempt and returns Google's address for the browser. */
    @Transactional
    public String start(Purpose purpose, String inviteCode, String binding) {
        if (!google.enabled()) {
            throw new GoogleException(Failure.NOT_ENABLED);
        }
        if (purpose == null || binding == null || !TOKEN.matcher(binding).matches()) {
            throw new GoogleException(Failure.GOOGLE_FAILED);
        }
        String hint = null;
        String inviteHash = null;
        if (purpose == Purpose.JOIN) {
            hint = delegates.inviteEmail(inviteCode).orElseThrow(() -> new GoogleException(Failure.INVALID_INVITE));
            inviteHash = Codes.hash(inviteCode.trim());
        }
        String state = Codes.newCode();
        String nonce = Codes.newCode();
        String verifier = Codes.newCode();
        Instant now = clock.instant();
        jdbc.sql("insert into delegate_google_attempts (state_hash, purpose, invite_hash, binding_hash, nonce, "
                        + "code_verifier, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(Codes.hash(state), purpose.name(), inviteHash, Codes.hash(binding), nonce, verifier, ts(now),
                        ts(now.plus(ATTEMPT_LIFETIME)))
                .update();
        return google.authorizeUrl(state, nonce, verifier, hint);
    }

    /**
     * Google's return. Answers the fragment the browser is sent to: {@code google=<handoff>} or
     * {@code google-error=<reason>}. Not one transaction: the call to Google sits between the read and the write, and
     * the write only succeeds once.
     */
    public String callback(String state, String code, String error) {
        if (state == null || !TOKEN.matcher(state).matches()) {
            return failed(Failure.EXPIRED);
        }
        String stateHash = Codes.hash(state);
        Instant now = clock.instant();
        Optional<Pending> pending = jdbc.sql("select nonce, code_verifier from delegate_google_attempts "
                        + "where state_hash = ? and handoff_hash is null and finished_at is null and expires_at > ?")
                .params(stateHash, ts(now))
                .query((rs, n) -> new Pending(rs.getString("nonce"), rs.getString("code_verifier")))
                .optional();
        if (pending.isEmpty()) {
            return failed(Failure.EXPIRED);
        }
        if (error != null && !error.isBlank()) {
            spend(stateHash, now);
            return failed("access_denied".equals(error) ? Failure.CANCELLED : Failure.GOOGLE_FAILED);
        }
        GoogleSignIn.Identity identity;
        try {
            identity = google.exchange(code, pending.get().verifier(), pending.get().nonce());
        } catch (GoogleSignIn.Refused e) {
            log.warn("google sign-in refused: {}", e.getMessage());
            spend(stateHash, now);
            return failed(Failure.GOOGLE_FAILED);
        }
        String handoff = Codes.newCode();
        int parked = jdbc.sql("update delegate_google_attempts set google_sub = ?, google_email = ?, handoff_hash = ? "
                        + "where state_hash = ? and handoff_hash is null and finished_at is null")
                .params(identity.sub(), identity.email(), Codes.hash(handoff), stateHash)
                .update();
        return parked == 1 ? "google=" + handoff : failed(Failure.EXPIRED);
    }

    /** The tab that started the trip, back with its binding: spends the attempt and names the delegate. */
    @Transactional(noRollbackFor = GoogleException.class)
    public UUID finish(String handoff, String binding) {
        if (handoff == null || binding == null || !TOKEN.matcher(handoff).matches()) {
            throw new GoogleException(Failure.EXPIRED);
        }
        String handoffHash = Codes.hash(handoff);
        Instant now = clock.instant();
        Optional<Parked> parked = jdbc.sql("select state_hash, purpose, invite_hash, binding_hash, google_sub, "
                        + "google_email from delegate_google_attempts "
                        + "where handoff_hash = ? and finished_at is null and expires_at > ?")
                .params(handoffHash, ts(now))
                .query((rs, n) -> new Parked(rs.getString("state_hash"), Purpose.valueOf(rs.getString("purpose")),
                        rs.getString("invite_hash"), rs.getString("binding_hash"), rs.getString("google_sub"),
                        rs.getString("google_email")))
                .optional();
        if (parked.isEmpty() || spend(parked.get().stateHash(), now) != 1) {
            throw new GoogleException(Failure.EXPIRED);
        }
        if (!MessageDigest.isEqual(Codes.hash(binding).getBytes(StandardCharsets.US_ASCII),
                parked.get().bindingHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new GoogleException(Failure.GOOGLE_FAILED);
        }
        if (parked.get().purpose() == Purpose.LOGIN) {
            return delegates.googleLogin(parked.get().sub())
                    .orElseThrow(() -> new GoogleException(Failure.NOT_REGISTERED));
        }
        try {
            return delegates.joinWithGoogle(parked.get().inviteHash(), parked.get().sub(), parked.get().email());
        } catch (Delegates.EnrolException e) {
            throw new GoogleException(switch (e.failure()) {
                case EMAIL_MISMATCH -> Failure.EMAIL_MISMATCH;
                case ALREADY_REGISTERED -> Failure.ALREADY_REGISTERED;
                default -> Failure.INVALID_INVITE;
            });
        }
    }

    private int spend(String stateHash, Instant now) {
        return jdbc.sql("update delegate_google_attempts set finished_at = ?, google_sub = null, google_email = null "
                        + "where state_hash = ? and finished_at is null")
                .params(ts(now), stateHash)
                .update();
    }

    private static String failed(Failure failure) {
        return "google-error=" + failure.name().toLowerCase();
    }

    private record Pending(String nonce, String verifier) {}

    private record Parked(String stateHash, Purpose purpose, String inviteHash, String bindingHash, String sub,
                          String email) {}

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
