package com.sellerops.vendor.store;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * <p>A delegate sees a store only while an assignment to it is open <b>and checked</b>. Ending one keeps the row (who
 * could see what, and when, stays answerable). Every later review and task read by a delegate is gated on
 * {@link #isAssigned}, which is {@link #WORKING}.
 *
 * <p><b>The store check</b> (V9), done by an admin before any seller's reviews reach the server, and recorded with who,
 * when and on what basis: the store is the seller account holder's ({@link #checkOwner}), and the assigned delegate's
 * own NAVER account is a sub-manager of it in Seller Center ({@link #checkAssignment}). Until both are recorded the
 * assignment does nothing — no store in the delegate's list, no upload, no task. Assigning again after an end is a new
 * row, checked again.
 */
@Service
public class Stores {

    /** The channels a store may be on. NAVER is the one review delegation serves today (scope lock v1.14 ⑦). */
    public enum Channel { NAVER }

    public static final int MAX_LABEL_LENGTH = 80;

    /**
     * An assignment {@code a} the delegate can work through on store {@code s}: open, checked, on a store whose owner
     * is checked. The one definition every delegate read uses.
     */
    public static final String WORKING =
            "a.ended_at is null and a.checked_at is not null and s.owner_checked_at is not null";

    /** What the admin saw that makes the store the seller's. */
    public enum OwnerBasis {
        /** The seller, signed in to Seller Center, showed this store's own screen. */
        SELLER_CENTER_SCREEN,
        /** The seller confirmed from their account's address that they sent this store's sub-manager invite. */
        SUBMANAGER_INVITE
    }

    public enum Check { CHECKED, ALREADY_CHECKED, NOT_ADMIN, NOT_FOUND }

    /** One store as the operator's checklist shows it. Null checker and time: not checked yet. */
    public record ChecklistEntry(UUID storeId, String label, String sellerEmail, String ownerCheckedBy,
                                 Instant ownerCheckedAt, OwnerBasis ownerBasis, List<AssignmentCheck> assignments) {

        /** True when at least one delegate can upload this store's reviews now. */
        public boolean receives() {
            return ownerCheckedAt != null && assignments.stream().anyMatch(a -> a.checkedAt() != null);
        }
    }

    /** One open assignment on the checklist. */
    public record AssignmentCheck(String delegateEmail, Instant assignedAt, String checkedBy, Instant checkedAt) {}

    /**
     * {@code lastUploadAt}: when a delegate last uploaded this store's export, or null — how fresh what the PC pulls
     * can be, which the PC shows apart from when it last asked.
     */
    public record View(UUID id, String channel, String label, Instant lastUploadAt) {}

    private static final String LAST_UPLOAD =
            "(select max(u.uploaded_at) from review_uploads u where u.store_id = s.id)";

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
        return jdbc.sql("select s.id, s.channel, s.label, " + LAST_UPLOAD + " as last_upload_at from stores s "
                        + "where s.account_id = ? order by s.created_at, s.id")
                .param(accountId)
                .query(Stores::view)
                .list();
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID storeId) {
        return jdbc.sql("select count(*) from stores where id = ?").param(storeId).query(Integer.class).single() > 0;
    }

    /**
     * Opens an assignment, not yet checked ({@link #checkAssignment}). False when one is already open — assigning
     * twice changes nothing.
     */
    @Transactional
    public boolean assign(UUID storeId, UUID delegateId) {
        if (isOpen(storeId, delegateId)) {
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

    /** True when the delegate may work on the store now: {@link #WORKING}. */
    @Transactional(readOnly = true)
    public boolean isAssigned(UUID storeId, UUID delegateId) {
        return jdbc.sql("select count(*) from store_assignments a join stores s on s.id = a.store_id "
                        + "where a.store_id = ? and a.delegate_id = ? and " + WORKING)
                .params(storeId, delegateId)
                .query(Integer.class).single() > 0;
    }

    private boolean isOpen(UUID storeId, UUID delegateId) {
        return jdbc.sql("select count(*) from store_assignments "
                        + "where store_id = ? and delegate_id = ? and ended_at is null")
                .params(storeId, delegateId)
                .query(Integer.class).single() > 0;
    }

    /** The stores a delegate may work on now. */
    @Transactional(readOnly = true)
    public List<View> assignedTo(UUID delegateId) {
        return jdbc.sql("select s.id, s.channel, s.label, " + LAST_UPLOAD + " as last_upload_at from stores s "
                        + "join store_assignments a on a.store_id = s.id "
                        + "where a.delegate_id = ? and " + WORKING + " order by s.created_at, s.id")
                .param(delegateId)
                .query(Stores::view)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> accountOf(UUID storeId) {
        return jdbc.sql("select account_id from stores where id = ?").param(storeId).query(UUID.class).optional();
    }

    /** An admin records that the store is the seller account holder's, and on what basis. Recorded once. */
    @Transactional
    public Check checkOwner(UUID storeId, UUID adminId, OwnerBasis basis) {
        if (!isAdmin(adminId)) {
            return Check.NOT_ADMIN;
        }
        if (!exists(storeId)) {
            return Check.NOT_FOUND;
        }
        int checked = jdbc.sql("update stores set owner_checked_by = ?, owner_checked_at = ?, owner_check_basis = ? "
                        + "where id = ? and owner_checked_at is null")
                .params(adminId, ts(clock.instant()), basis.name(), storeId)
                .update();
        return checked == 1 ? Check.CHECKED : Check.ALREADY_CHECKED;
    }

    /**
     * An admin records that the delegate's own NAVER account is a sub-manager of the store in Seller Center. Only the
     * open assignment; one ended and opened again is checked again.
     */
    @Transactional
    public Check checkAssignment(UUID storeId, UUID delegateId, UUID adminId) {
        if (!isAdmin(adminId)) {
            return Check.NOT_ADMIN;
        }
        if (!isOpen(storeId, delegateId)) {
            return Check.NOT_FOUND;
        }
        int checked = jdbc.sql("update store_assignments set checked_by = ?, checked_at = ? "
                        + "where store_id = ? and delegate_id = ? and ended_at is null and checked_at is null")
                .params(adminId, ts(clock.instant()), storeId, delegateId)
                .update();
        return checked == 1 ? Check.CHECKED : Check.ALREADY_CHECKED;
    }

    /** Every store with its two checks and its open assignments, oldest first — the operator's checklist. */
    @Transactional(readOnly = true)
    public List<ChecklistEntry> checklist() {
        List<ChecklistEntry> stores = jdbc.sql("select s.id, s.label, acc.email, chk.email as checker, "
                        + "s.owner_checked_at, s.owner_check_basis from stores s "
                        + "join seller_accounts acc on acc.id = s.account_id "
                        + "left join delegates chk on chk.id = s.owner_checked_by order by s.created_at, s.id")
                .query((rs, n) -> {
                    Timestamp at = rs.getTimestamp("owner_checked_at");
                    String basis = rs.getString("owner_check_basis");
                    return new ChecklistEntry(rs.getObject("id", UUID.class), rs.getString("label"),
                            rs.getString("email"), rs.getString("checker"), at == null ? null : at.toInstant(),
                            basis == null ? null : OwnerBasis.valueOf(basis), List.of());
                })
                .list();
        return stores.stream().map(e -> new ChecklistEntry(e.storeId(), e.label(), e.sellerEmail(),
                e.ownerCheckedBy(), e.ownerCheckedAt(), e.ownerBasis(), jdbc.sql(
                        "select d.email, a.assigned_at, chk.email as checker, a.checked_at from store_assignments a "
                                + "join delegates d on d.id = a.delegate_id "
                                + "left join delegates chk on chk.id = a.checked_by "
                                + "where a.store_id = ? and a.ended_at is null order by a.assigned_at, a.id")
                        .param(e.storeId())
                        .query((rs, n) -> {
                            Timestamp checked = rs.getTimestamp("checked_at");
                            return new AssignmentCheck(rs.getString("email"),
                                    rs.getTimestamp("assigned_at").toInstant(), rs.getString("checker"),
                                    checked == null ? null : checked.toInstant());
                        })
                        .list())).toList();
    }

    private boolean isAdmin(UUID delegateId) {
        return jdbc.sql("select count(*) from delegates where id = ? and role = 'ADMIN' and status = 'ACTIVE'")
                .param(delegateId).query(Integer.class).single() > 0;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static View view(ResultSet rs, int n) throws SQLException {
        Timestamp last = rs.getTimestamp("last_upload_at");
        return new View(rs.getObject("id", UUID.class), rs.getString("channel"), rs.getString("label"),
                last == null ? null : last.toInstant());
    }
}
