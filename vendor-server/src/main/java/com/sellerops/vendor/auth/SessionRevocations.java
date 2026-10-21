package com.sellerops.vendor.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Ending a session before it expires</b> — contract §2. A signed session cannot be taken back by itself, so the
 * server remembers two things, and {@link BearerAuthFilter} asks {@link #live} on every request:
 *
 * <ul>
 *   <li><b>Log out</b> ({@link #revoke}): that one session, by its id, until it would have expired anyway. The same
 *       person's other sessions — the phone and the desk are separate — keep working.</li>
 *   <li><b>End every session</b> ({@link #endAll}): every session of that person issued before now, for a leaked
 *       token or a lost phone. Unlike suspension the account stays as it is; signing in again works at once.</li>
 * </ul>
 *
 * <p>Compared to the millisecond, and a session issued in the very millisecond of {@link #endAll} is ended with the
 * rest: when in doubt, the person signs in once more. Nothing that was ended ever comes back.
 */
@Component
public class SessionRevocations {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public SessionRevocations(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public SessionRevocations(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** False when this session was logged out, or ended with every session of its person. */
    @Transactional(readOnly = true)
    public boolean live(SessionTokens.Session session) {
        boolean revoked = jdbc.sql("select count(*) from revoked_sessions where jti = ?").param(session.id())
                .query(Integer.class).single() > 0;
        if (revoked) {
            return false;
        }
        Optional<Instant> notBefore = jdbc.sql("select sessions_not_before from " + table(session.kind())
                        + " where id = ?")
                .param(session.subject())
                .query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
                .optional()
                .flatMap(o -> o);
        return notBefore.isEmpty() || session.issuedAt().isAfter(notBefore.get());
    }

    /**
     * Logs this one session out. Rows past their session's expiry are dropped on the way — the signature refuses
     * those already.
     */
    @Transactional
    public void revoke(SessionTokens.Session session) {
        Instant now = clock.instant();
        jdbc.sql("delete from revoked_sessions where expires_at < ?").param(Timestamp.from(now)).update();
        try {
            jdbc.sql("insert into revoked_sessions (jti, expires_at) values (?, ?)")
                    .params(session.id(), Timestamp.from(session.expiresAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            // Logged out twice: once is enough.
        }
    }

    /** Ends every session the person has now. False when there is no such person. */
    @Transactional
    public boolean endAll(SessionTokens.Kind kind, UUID subject) {
        return jdbc.sql("update " + table(kind) + " set sessions_not_before = ? where id = ?")
                .params(Timestamp.from(clock.instant()), subject)
                .update() == 1;
    }

    private static String table(SessionTokens.Kind kind) {
        return switch (kind) {
            case SELLER -> "seller_accounts";
            case DELEGATE -> "delegates";
        };
    }
}
