package com.sellerops.vendor.retention;

import com.sellerops.vendor.review.StoreReviews;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>Deleting what the server no longer needs</b> — contract §6. One pass deletes:
 *
 * <ul>
 *   <li><b>A review and its tasks</b> once no task on it is still running or ended within {@link #ENDED_TASK_RETENTION},
 *       and either its no-reply retention ran out ({@code purge_after}, D4) or it had tasks and none is left that
 *       young. Whichever of the two comes later wins. A review nobody has decided about — no task, no "no reply" —
 *       is work in progress and is never deleted here.</li>
 *   <li>Expired invites (they hold an address and, for delegates, a staged password and TOTP secret), logged-out
 *       sessions past their expiry, and step-up codes a day old (their caps look back an hour, §2).</li>
 * </ul>
 *
 * <p>What a deleted review leaves behind is its id and the hash of its 리뷰글번호 ({@code purged_reviews}), so the
 * next upload that carries it does not collect it again.
 *
 * <p><b>Every rule is decided by dates the rows themselves carry</b> and that never move later. A restored backup
 * therefore holds the reason to delete everything that was deleted after it was taken, as long as backups are kept
 * for less than {@link #ENDED_TASK_RETENTION}: {@link PurgeSchedule} runs one pass before the server starts serving.
 */
@Component
public class Retention {

    /** Completed tasks and their review: 90 days after the task ended (§6). */
    public static final Duration ENDED_TASK_RETENTION = Duration.ofDays(90);

    private static final Logger log = LoggerFactory.getLogger(Retention.class);

    /**
     * A review whose time is up: nothing on it is still running or recently ended, and its no-reply retention
     * ({@code purge_after}) — if any — has run out, and it either had that or had tasks.
     */
    private static final String DUE = "not exists (select 1 from reply_tasks t where t.review_id = r.id "
            + "and (t.ended_at is null or t.ended_at > :cutoff)) "
            + "and ((r.purge_after is not null and r.purge_after <= :now) "
            + "or (r.purge_after is null and exists (select 1 from reply_tasks t where t.review_id = r.id)))";

    public record Purged(int reviews, int tasks, int invites, int sessions, int stepUps) {}

    /** How long a step-up code row is kept: past its ten minutes and the hour its caps count over. */
    static final java.time.Duration STEP_UP_RETENTION = java.time.Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public Retention(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    public Retention(JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** One pass. Each review is deleted in its own transaction, so one that cannot go now does not hold the rest. */
    public Purged purge() {
        Instant now = clock.instant();
        Map<String, Object> at = Map.of("now", Timestamp.from(now),
                "cutoff", Timestamp.from(now.minus(ENDED_TASK_RETENTION)));
        List<UUID> due = jdbc.sql("select r.id from store_reviews r where " + DUE).params(at)
                .query(UUID.class).list();
        int reviews = 0;
        int tasks = 0;
        for (UUID id : due) {
            try {
                Integer deleted = tx.execute(s -> purgeReview(id, at, now));
                if (deleted != null) {
                    reviews++;
                    tasks += deleted;
                }
            } catch (RuntimeException e) {
                // A task opened on it at this very moment: it is no longer due, and the next pass looks again.
                log.warn("retention: review not deleted type={}", e.getClass().getSimpleName());
            }
        }
        Integer invites = tx.execute(s -> jdbc.sql("delete from seller_invites where expires_at <= ?")
                .param(Timestamp.from(now)).update()
                + jdbc.sql("delete from delegate_invites where expires_at <= ?").param(Timestamp.from(now)).update());
        Integer sessions = tx.execute(s -> jdbc.sql("delete from revoked_sessions where expires_at < ?")
                .param(Timestamp.from(now)).update());
        Integer stepUps = tx.execute(s -> jdbc.sql("delete from step_up_codes where issued_at < ?")
                .param(Timestamp.from(now.minus(STEP_UP_RETENTION))).update());
        Purged purged = new Purged(reviews, tasks, invites == null ? 0 : invites, sessions == null ? 0 : sessions,
                stepUps == null ? 0 : stepUps);
        // Counts only — what was deleted is not written anywhere.
        log.info("retention: deleted reviews={} tasks={} invites={} sessions={} stepUps={}", purged.reviews(),
                purged.tasks(), purged.invites(), purged.sessions(), purged.stepUps());
        return purged;
    }

    /** Deletes one review and everything hanging from it; null when it is no longer due. */
    private Integer purgeReview(UUID id, Map<String, Object> at, Instant now) {
        // Locked first, then asked again: a task opened since the list was read keeps it.
        List<Object[]> locked = jdbc.sql("select store_id, review_no from store_reviews where id = :id for update")
                .param("id", id)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)})
                .list();
        if (locked.isEmpty() || jdbc.sql("select count(*) from store_reviews r where r.id = :id and " + DUE)
                .params(at).param("id", id).query(Integer.class).single() == 0) {
            return null;
        }
        UUID storeId = (UUID) locked.get(0)[0];
        String hash = StoreReviews.reviewNoHash((String) locked.get(0)[1]);
        List<UUID> tasks = jdbc.sql("select id from reply_tasks where review_id = ?").param(id).query(UUID.class)
                .list();
        if (!tasks.isEmpty()) {
            jdbc.sql("delete from task_commands where task_id in (:ids)").param("ids", tasks).update();
            jdbc.sql("delete from reply_task_changes where task_id in (:ids)").param("ids", tasks).update();
            jdbc.sql("delete from reply_tasks where review_id = ?").param(id).update();
        }
        jdbc.sql("delete from store_review_events where review_id = ?").param(id).update();
        // Already there after a backup was restored and this review is deleted a second time.
        if (jdbc.sql("select count(*) from purged_reviews where store_id = ? and review_no_hash = ?")
                .params(storeId, hash).query(Integer.class).single() == 0) {
            jdbc.sql("insert into purged_reviews (store_id, review_no_hash, review_id, purged_at) values (?, ?, ?, ?)")
                    .params(storeId, hash, id, Timestamp.from(now))
                    .update();
        }
        jdbc.sql("delete from store_reviews where id = ?").param(id).update();
        return tasks.size();
    }
}
