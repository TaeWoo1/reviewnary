package com.sellerops.autocheck;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewAutoCheckRepository extends JpaRepository<ReviewAutoCheck, UUID> {

    Optional<ReviewAutoCheck> findBySellerAccountIdAndDataType(UUID sellerAccountId, String dataType);

    List<ReviewAutoCheck> findByOrgIdAndDataType(UUID orgId, String dataType);

    /**
     * The rows that are due, locked for this tick and skipped if another tick holds them.
     *
     * <p>{@code for update skip locked}, and the claimer advances {@code next_check_at} before this
     * transaction commits — the same shape as {@code SyncScheduleRepository#lockDue}, for the same reason: two
     * ticks must never both work one account, and a second tick waiting for the first would be a tick doing
     * nothing slowly instead of nothing quickly.
     *
     * <p>A null {@code next_check_at} is due. That is how a row created this second gets its first read without
     * waiting out a cadence nobody has started.
     */
    /**
     * <b>One organisation's</b> connected marketplace accounts that have no automatic-check row yet.
     *
     * <p>Scoped to an organisation, and that is not a detail: the only caller is the per-organisation backfill
     * a seller's own session asks for ({@code ReviewAutoCheckService#backfillForOrg}). Nothing in this lane
     * sweeps every organisation in the database — so a backend that holds a fixture or benchmark organisation
     * never picks it up, and the product never needs a list of organisations to skip.
     *
     * <p>A row that exists is never returned, so an account the seller switched off is not switched back on by
     * a backfill: the off row IS the memory of that decision.
     *
     * <p>Whether the account has a review screen that can be read for a NAMED period is decided above, per
     * account ({@code ReviewAutoCheckService#ensure}) — a Cafe24 store whose reviews arrive by API, and a
     * Coupang store whose screen cannot select a period, each get no row and no setting, which is a different
     * answer from having one that is off.
     */
    @Query("""
            select a.id from SellerAccount a
            where a.orgId = :orgId
              and a.connectionStatus = com.sellerops.channel.ChannelStatus.CONNECTED
              and a.fileUpload = false
              and not exists (select 1 from ReviewAutoCheck c
                              where c.sellerAccountId = a.id and c.dataType = 'REVIEW')
            order by a.createdAt asc
            """)
    List<UUID> accountsWithoutRow(@Param("orgId") UUID orgId);

    @Query(value = """
            select * from review_auto_check
            where enabled = true and revoked_at is null
              and (next_check_at is null or next_check_at <= :now)
            order by next_check_at asc nulls first
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<ReviewAutoCheck> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
