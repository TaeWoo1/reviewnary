package com.sellerops.inquiry.recheck;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements behind {@link InquiryRecheckLedger}'s run lock. Every transition is one conditional statement — "1 row
 * changed" means this caller won, "0" means someone else holds it (the CAS idiom of {@code AgentRunRepository}).
 */
public interface InquiryRecheckRunLockRepository extends JpaRepository<InquiryRecheckRunLock, UUID> {

    /** Take a free account: 1 if this inserted the row, 0 if a row is already there. Parameters cast as in openDay. */
    @Transactional
    @Modifying
    @Query(value = "merge into inquiry_recheck_run_lock t "
            + "using (values (cast(:account as uuid), cast(:holder as uuid), cast(:now as timestamp with time zone), "
            + "cast(:until as timestamp with time zone))) s(seller_account_id, holder, acquired_at, lease_until) "
            + "on t.seller_account_id = s.seller_account_id "
            + "when not matched then insert (seller_account_id, holder, acquired_at, lease_until) "
            + "values (s.seller_account_id, s.holder, s.acquired_at, s.lease_until)", nativeQuery = true)
    int takeFree(@Param("account") UUID account, @Param("holder") UUID holder, @Param("now") Instant now,
                 @Param("until") Instant until);

    /** Take an account whose holder's lease has run out — a run that died without letting go. */
    @Transactional
    @Modifying
    @Query("update InquiryRecheckRunLock l set l.holder = :holder, l.acquiredAt = :now, l.leaseUntil = :until "
            + "where l.sellerAccountId = :account and l.leaseUntil <= :now")
    int takeOverExpired(@Param("account") UUID account, @Param("holder") UUID holder, @Param("now") Instant now,
                        @Param("until") Instant until);

    /** Keep holding: 1 only while this holder still holds it and its lease has not run out. */
    @Transactional
    @Modifying
    @Query("update InquiryRecheckRunLock l set l.leaseUntil = :until "
            + "where l.sellerAccountId = :account and l.holder = :holder and l.leaseUntil > :now")
    int renew(@Param("account") UUID account, @Param("holder") UUID holder, @Param("now") Instant now,
              @Param("until") Instant until);

    /** 1 while {@code holder} holds the account with a lease that has not run out at {@code now}, else 0. */
    @Query("select count(l) from InquiryRecheckRunLock l "
            + "where l.sellerAccountId = :account and l.holder = :holder and l.leaseUntil > :now")
    long countHeld(@Param("account") UUID account, @Param("holder") UUID holder, @Param("now") Instant now);

    /** Let go — only this holder's row, never one a later run has taken over. */
    @Transactional
    @Modifying
    @Query("delete from InquiryRecheckRunLock l where l.sellerAccountId = :account and l.holder = :holder")
    int release(@Param("account") UUID account, @Param("holder") UUID holder);
}
