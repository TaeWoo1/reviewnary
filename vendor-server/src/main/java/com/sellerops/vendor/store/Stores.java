package com.sellerops.vendor.store;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A seller's stores, and which delegate may work on which</b> — contract §2.
 *
 * <p>A store row is the vendor's record that a seller has handed one of their stores to the review-delegation
 * service: the seller invited a delegate into it as a sub-manager on the marketplace. It carries the seller's
 * account, the channel and a label, and no marketplace identifier or credential — the delegate reaches the store
 * with their own invited account, never through anything kept here.
 *
 * <p>A delegate sees a store only while an assignment to it is open. Ending one keeps the row (who could see what,
 * and when, stays answerable). Every later review and task read by a delegate is gated on {@link #isAssigned}.
 */
@Service
public class Stores {

    /** The channels a store may be on. NAVER is the one review delegation serves today (scope lock v1.14 ⑦). */
    public enum Channel { NAVER }

    public static final int MAX_LABEL_LENGTH = 80;

    public record View(UUID id, String channel, String label) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public Stores(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public Stores(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records a store for a seller account. */
    @Transactional
    public UUID add(UUID accountId, Channel channel, String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.strip();
        if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("label must be 1-" + MAX_LABEL_LENGTH + " characters");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into stores (id, account_id, channel, label, created_at) values (?, ?, ?, ?, ?)")
                .params(id, accountId, channel.name(), label, ts(clock.instant()))
                .update();
        return id;
    }

    @Transactional(readOnly = true)
    public List<View> ofAccount(UUID accountId) {
        return jdbc.sql("select id, channel, label from stores where account_id = ? order by created_at, id")
                .param(accountId)
                .query((rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("channel"),
                        rs.getString("label")))
                .list();
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID storeId) {
        return jdbc.sql("select count(*) from stores where id = ?").param(storeId).query(Integer.class).single() > 0;
    }

    /** Opens an assignment. False when one is already open — assigning twice changes nothing. */
    @Transactional
    public boolean assign(UUID storeId, UUID delegateId) {
        if (isAssigned(storeId, delegateId)) {
            return false;
        }
        jdbc.sql("insert into store_assignments (id, store_id, delegate_id, assigned_at) values (?, ?, ?, ?)")
                .params(UUID.randomUUID(), storeId, delegateId, ts(clock.instant()))
                .update();
        return true;
    }

    /** Ends the open assignment. False when there was none. */
    @Transactional
    public boolean unassign(UUID storeId, UUID delegateId) {
        return jdbc.sql("update store_assignments set ended_at = ? "
                        + "where store_id = ? and delegate_id = ? and ended_at is null")
                .params(ts(clock.instant()), storeId, delegateId)
                .update() > 0;
    }

    @Transactional(readOnly = true)
    public boolean isAssigned(UUID storeId, UUID delegateId) {
        return jdbc.sql("select count(*) from store_assignments "
                        + "where store_id = ? and delegate_id = ? and ended_at is null")
                .params(storeId, delegateId)
                .query(Integer.class).single() > 0;
    }

    /** The stores a delegate may work on now. */
    @Transactional(readOnly = true)
    public List<View> assignedTo(UUID delegateId) {
        return jdbc.sql("select s.id, s.channel, s.label from stores s join store_assignments a on a.store_id = s.id "
                        + "where a.delegate_id = ? and a.ended_at is null order by s.created_at, s.id")
                .param(delegateId)
                .query((rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("channel"),
                        rs.getString("label")))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> accountOf(UUID storeId) {
        return jdbc.sql("select account_id from stores where id = ?").param(storeId).query(UUID.class).optional();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
