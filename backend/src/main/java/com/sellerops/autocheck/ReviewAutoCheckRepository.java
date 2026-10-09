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
     * Connected marketplace accounts that have no automatic-check row yet, oldest first.
     *
     * <p><b>This is how «신규 연결은 기본 ON» is true without the connection code knowing about this lane.</b> A
     * seller finishes connecting; the next tick adopts the account and the setting is on. The alternative — a
     * call inside each channel's connection lifecycle — would put this feature's name in four places that are
     * about something else, and would silently miss the fifth.
     *
     * <p>A row that exists is never returned, so an account the seller switched off is not adopted again: the
     * off row IS the memory of that decision.
     *
     * <p>Whether the account has a review screen at all is decided above, per account
     * ({@code ReviewAutoCheckService#ensure}) — a Cafe24 store whose reviews arrive by API gets no row and no
     * setting, which is different from having one that is off.
     */
    @Query(value = """
            select a.id from seller_accounts a
            where a.connection_status = 'CONNECTED' and a.is_file_upload = false
              and not exists (select 1 from review_auto_check c
                              where c.seller_account_id = a.id and c.data_type = 'REVIEW')
            order by a.created_at asc
            limit :limit
            """, nativeQuery = true)
    List<UUID> accountsWithoutRow(@Param("limit") int limit);

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
