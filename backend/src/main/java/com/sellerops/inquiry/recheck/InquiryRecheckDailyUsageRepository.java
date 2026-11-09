package com.sellerops.inquiry.recheck;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** The statements behind {@link InquiryRecheckLedger}'s daily count. Each is one statement, so racers cannot overshoot. */
public interface InquiryRecheckDailyUsageRepository
        extends JpaRepository<InquiryRecheckDailyUsage, InquiryRecheckDailyUsage.Key> {

    /**
     * Take one request out of the day, only while fewer than {@code limit} have been taken. 1 = granted; 0 = the day is
     * spent, or its row does not exist yet. The {@code requests < :limit} test and the increment are one statement — two
     * runs taking at once cannot both take the last request.
     */
    @Transactional
    @Modifying
    @Query("update InquiryRecheckDailyUsage u set u.requests = u.requests + 1, u.updatedAt = :now "
            + "where u.sellerAccountId = :account and u.usageDay = :day and u.requests < :limit")
    int take(@Param("account") UUID account, @Param("day") LocalDate day, @Param("limit") int limit,
             @Param("now") Instant now);

    /**
     * The day's row at zero, if it is not there yet. As {@code ProductRepository.insertIfAbsent}: every parameter cast,
     * because PostgreSQL types a bare VALUES parameter as text. Two racers may still collide on the key on PostgreSQL;
     * the caller treats that as "the row is there now".
     */
    @Transactional
    @Modifying
    @Query(value = "merge into inquiry_recheck_daily_usage t "
            + "using (values (cast(:account as uuid), cast(:day as date), cast(:now as timestamp with time zone))) "
            + "s(seller_account_id, usage_day, ts) "
            + "on t.seller_account_id = s.seller_account_id and t.usage_day = s.usage_day "
            + "when not matched then insert (seller_account_id, usage_day, requests, updated_at) "
            + "values (s.seller_account_id, s.usage_day, 0, s.ts)", nativeQuery = true)
    int openDay(@Param("account") UUID account, @Param("day") LocalDate day, @Param("now") Instant now);

    @Query("select u.requests from InquiryRecheckDailyUsage u where u.sellerAccountId = :account and u.usageDay = :day")
    Optional<Integer> requestsOn(@Param("account") UUID account, @Param("day") LocalDate day);
}
