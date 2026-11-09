package com.sellerops.agent.quota;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One model call this org spent. No goal text, no response — usage only. */
@Getter
@Setter
@Entity
@Table(name = "agent_llm_usage")
public class AgentLlmUsage extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentUsageKind kind;

    @Column(name = "run_id", length = 200)
    private String runId;

    /**
     * Who spent it. Recorded for every call; only {@code USER} is compared against the daily limits.
     * Never null — an unattributable call is charged to the seller (see {@link AgentUsageActor}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, length = 16)
    private AgentUsageActor actor = AgentUsageActor.USER;

    /** The reply agent's step (CLASSIFY · DRAFT · CRITIQUE · REVISE); null for a call that is not one. */
    @Column(name = "step", length = 16)
    private String step;

    /** What started the run: SELLER · INGEST · SCHEDULED; null for a call that does not say. */
    @Column(name = "trigger_kind", length = 16)
    private String triggerKind;

    /** This row opens one piece of work the seller asked for — the seller-facing count; every row is a model call. */
    @Column(name = "counts_as_item", nullable = false)
    private boolean countsAsItem;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;
}
