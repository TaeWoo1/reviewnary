package com.sellerops.inquiry.recheck;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements behind {@link InquiryRecheckLedger}'s account wait (V186). Reads are of single columns, not entities, so
 * a bulk statement is never hidden by the persistence context.
 *
 * <p><b>Only the run that holds the account moves its wait</b> (2026-11-09). Every write carries the writer's lock holder
 * and its clock, and changes a row only while {@code inquiry_recheck_run_lock} still names that holder with a lease that
 * has not run out — the check and the change in one statement. A run that stalled past its lease and was taken over
 * therefore finds 0 rows to change: it can neither clear nor lengthen the wait the run that took over has recorded.
 */
public interface InquiryRecheckAccountWaitRepository extends JpaRepository<InquiryRecheckAccountWait, UUID> {

    /**
     * The first account-side stop in a row: 1 if this inserted the row; 0 if one is already there, or if {@code holder}
     * no longer holds the account at {@code now}. The source row exists only while the lock does.
     */
    @Transactional
    @Modifying
    @Query(value = "merge into inquiry_recheck_account_wait t "
            + "using (select l.seller_account_id as seller_account_id, cast(:stop as varchar(32)) as last_stop, "
            + "cast(:now as timestamp with time zone) as failed_at, cast(:until as timestamp with time zone) as wait_until "
            + "from inquiry_recheck_run_lock l where l.seller_account_id = cast(:account as uuid) "
            + "and l.holder = cast(:holder as uuid) and l.lease_until > cast(:now as timestamp with time zone)) s "
            + "on t.seller_account_id = s.seller_account_id "
            + "when not matched then insert (seller_account_id, failures, last_stop, failed_at, wait_until) "
            + "values (s.seller_account_id, 1, s.last_stop, s.failed_at, s.wait_until)", nativeQuery = true)
    int openWait(@Param("account") UUID account, @Param("holder") UUID holder, @Param("stop") String stop,
                 @Param("now") Instant now, @Param("until") Instant until);

    /**
     * One more in a row — only if the count is still the one read ({@code seen}) and {@code holder} still holds the
     * account at {@code now}; 0 means someone else moved it, or the lease is no longer this run's.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InquiryRecheckAccountWait w set w.failures = :next, w.lastStop = :stop, w.failedAt = :now, "
            + "w.waitUntil = :until where w.sellerAccountId = :account and w.failures = :seen "
            + "and exists (select 1 from InquiryRecheckRunLock l where l.sellerAccountId = :account "
            + "and l.holder = :holder and l.leaseUntil > :now)")
    int addFailure(@Param("account") UUID account, @Param("holder") UUID holder, @Param("seen") int seen,
                   @Param("next") int next, @Param("stop") String stop, @Param("now") Instant now,
                   @Param("until") Instant until);

    /** The account answered: drop its wait — only while {@code holder} still holds the account at {@code now}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from InquiryRecheckAccountWait w where w.sellerAccountId = :account "
            + "and exists (select 1 from InquiryRecheckRunLock l where l.sellerAccountId = :account "
            + "and l.holder = :holder and l.leaseUntil > :now)")
    int clear(@Param("account") UUID account, @Param("holder") UUID holder, @Param("now") Instant now);

    @Query("select w.failures from InquiryRecheckAccountWait w where w.sellerAccountId = :account")
    Optional<Integer> failuresOf(@Param("account") UUID account);

    @Query("select w.waitUntil from InquiryRecheckAccountWait w where w.sellerAccountId = :account")
    Optional<Instant> waitUntilOf(@Param("account") UUID account);
}
