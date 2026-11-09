package com.sellerops.agent.quota;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>The lock one org's day of AI is charged under</b> (outside review, 2026-10-29: two requests that both saw one call
 * left both went out). Every charge locks this row before it counts, compares and records, so for one org and one day
 * the three happen one charge at a time. It holds no number — {@code agent_llm_usage} stays the count.
 */
@Getter
@Setter
@Entity
@Table(name = "agent_daily_budget",
        uniqueConstraints = @UniqueConstraint(name = "uq_agent_daily_budget", columnNames = {"org_id", "usage_date"}))
public class AgentDailyBudget extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;
}
