package com.sellerops.agent.quota;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentLlmUsageRepository extends JpaRepository<AgentLlmUsage, UUID> {

    long countByOrgIdAndUsageDate(UUID orgId, LocalDate usageDate);

    /**
     * The calls the daily limit is about: this org's, today, spent by the SELLER.
     *
     * <p>The unqualified count above is what reporting reads and it still sees everything. This one
     * exists because a benchmark signing in as a real account used to spend that account's budget.
     */
    long countByOrgIdAndUsageDateAndActor(UUID orgId, LocalDate usageDate, AgentUsageActor actor);

    /** The pieces of work the seller asked for today — the seller-facing count (owner, 2026-10-29). */
    @Query("select count(u) from AgentLlmUsage u where u.orgId = :orgId and u.usageDate = :day and u.countsAsItem = true "
            + "and u.actor = com.sellerops.agent.quota.AgentUsageActor.USER")
    long countSellerItems(@Param("orgId") UUID orgId, @Param("day") LocalDate day);

    /**
     * Distinct runs today. {@code run_id is not null} matters: a null is "this call belonged to no
     * run", and counting nulls as one run would let every draft in the org share a single slot.
     */
    @Query("select count(distinct u.runId) from AgentLlmUsage u "
            + "where u.orgId = :orgId and u.usageDate = :day and u.runId is not null")
    long countDistinctRuns(@Param("orgId") UUID orgId, @Param("day") LocalDate day);

    /** Distinct SELLER runs today — the run limit's own count. */
    @Query("select count(distinct u.runId) from AgentLlmUsage u "
            + "where u.orgId = :orgId and u.usageDate = :day and u.runId is not null "
            + "and u.actor = com.sellerops.agent.quota.AgentUsageActor.USER")
    long countDistinctUserRuns(@Param("orgId") UUID orgId, @Param("day") LocalDate day);

    /** True when this run has already been counted — a later iteration must not buy a second slot. */
    boolean existsByOrgIdAndUsageDateAndRunId(UUID orgId, LocalDate usageDate, String runId);

    /**
     * One day's calls grouped by what they were (2026-10-28): kind, the reply agent's step, what started the run, and who
     * spent it — with how many calls, how many distinct runs, and the time and tokens they took. Counts only.
     */
    @Query("select u.kind, u.step, u.triggerKind, u.actor, count(u), count(distinct u.runId), "
            + "coalesce(sum(u.elapsedMs), 0), coalesce(sum(u.inputTokens), 0), coalesce(sum(u.outputTokens), 0) "
            + "from AgentLlmUsage u where u.orgId = :orgId and u.usageDate = :day "
            + "group by u.kind, u.step, u.triggerKind, u.actor")
    java.util.List<Object[]> breakdown(@Param("orgId") UUID orgId, @Param("day") LocalDate day);

    /** Today's calls by the seller and by runs no seller started — what drafting ahead measures its share against. */
    @Query("select count(u) from AgentLlmUsage u where u.orgId = :orgId and u.usageDate = :day "
            + "and u.actor in (com.sellerops.agent.quota.AgentUsageActor.USER, com.sellerops.agent.quota.AgentUsageActor.RUN)")
    long countSellerAndRunCalls(@Param("orgId") UUID orgId, @Param("day") LocalDate day);

    /**
     * Input and output tokens of the org's calls since {@code since}, and how many were charged without tokens yet — a
     * call still running, or one whose meter could not write (DevLlmBudget counts those at their worst case).
     */
    @Query("select coalesce(sum(u.inputTokens), 0), coalesce(sum(u.outputTokens), 0), "
            + "coalesce(sum(case when u.inputTokens is null and u.outputTokens is null then 1 else 0 end), 0) "
            + "from AgentLlmUsage u where u.orgId = :orgId and u.createdAt >= :since")
    java.util.List<Object[]> spentSince(@Param("orgId") UUID orgId, @Param("since") java.time.Instant since);
}
