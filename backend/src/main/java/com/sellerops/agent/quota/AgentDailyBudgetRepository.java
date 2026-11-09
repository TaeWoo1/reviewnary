package com.sellerops.agent.quota;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentDailyBudgetRepository extends JpaRepository<AgentDailyBudget, UUID> {

    /** The day's row, locked until the caller's transaction ends — the one place charges for this org-day queue up. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AgentDailyBudget b where b.orgId = :orgId and b.usageDate = :day")
    Optional<AgentDailyBudget> lockDay(@Param("orgId") UUID orgId, @Param("day") LocalDate day);
}
