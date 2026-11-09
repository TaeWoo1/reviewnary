package com.sellerops.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An evaluation's spending ceiling (2026-11-05, eleventh review): asked before every model call, with what was spent —
 * returned calls at their real tokens, a call not yet returned at its worst case — and this call's worst case.
 */
class DevLlmBudgetTest {

    private static final UUID ORG = UUID.randomUUID();

    private static DevLlmBudget budget(double usd) {
        return new DevLlmBudget(usd, "2026-11-05T00:00:00Z", 4, 20, 800, 2400, 4000);
    }

    private static AgentLlmUsageRepository spent(long in, long out, long pending) {
        AgentLlmUsageRepository usage = mock(AgentLlmUsageRepository.class);
        when(usage.spentSince(any(), any())).thenReturn(List.<Object[]>of(new Object[] {in, out, pending}));
        return usage;
    }

    @Test
    @DisplayName("a call's worst case is its input estimate plus its configured output cap, at the configured prices")
    void worstCases() {
        DevLlmBudget b = budget(1);
        assertThat(b.worstCase("NEED_PLAN")).isCloseTo(0.024, within(1e-9));
        assertThat(b.worstCase("NEED_JUDGE")).isCloseTo(0.072, within(1e-9));
        assertThat(b.worstCase(null)).as("a draft").isCloseTo(0.12, within(1e-9));
        assertThat(b.worstCase("CLAIM_RECHECK")).as("every check shares the draft cap").isCloseTo(0.12, within(1e-9));
    }

    @Test
    @DisplayName("a call starts only while what was spent plus its own worst case stays within the budget")
    void theGate() {
        DevLlmBudget b = budget(1);
        // 100k in · 10k out = $0.40 + $0.20 = $0.60; a check's worst case $0.12 → $0.72 ≤ $1.
        assertThat(b.allows(spent(100_000, 10_000, 0), ORG, "FIGURE_CHECK")).isTrue();
        // $0.90 spent: a judge ($0.072) fits, a draft ($0.12) does not.
        assertThat(b.allows(spent(150_000, 15_000, 0), ORG, "NEED_JUDGE")).isTrue();
        assertThat(b.allows(spent(150_000, 15_000, 0), ORG, null)).isFalse();
        // A call charged and not returned counts at its worst case.
        assertThat(b.allows(spent(100_000, 10_000, 3), ORG, null)).as("$0.60 + 3 × $0.12 + $0.12 > $1").isFalse();
    }

    @Test
    @DisplayName("a sum over no rows comes back null from SQL — read as zero, so the first call starts")
    void nothingSpentYet() {
        assertThat(budget(1).allows(spent(0, 0, 0), ORG, null)).isTrue();
        AgentLlmUsageRepository empty = mock(AgentLlmUsageRepository.class);
        when(empty.spentSince(any(), any())).thenReturn(List.<Object[]>of(new Object[] {null, null, null}));
        assertThat(budget(1).allows(empty, ORG, null)).isTrue();
        assertThat(budget(1).spent(empty, ORG)).isZero();
    }

    @Test
    @DisplayName("off by default; on without a start refuses to boot")
    void offByDefault() {
        DevLlmBudget off = new DevLlmBudget(0, "", 4, 20, 800, 2400, 4000);
        assertThat(off.enabled()).isFalse();
        AgentLlmUsageRepository usage = mock(AgentLlmUsageRepository.class);
        assertThat(off.allows(usage, ORG, null)).isTrue();
        verify(usage, never()).spentSince(any(), any());
        assertThatThrownBy(() -> new DevLlmBudget(1, " ", 4, 20, 800, 2400, 4000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a refused call is refused like the day's ceiling — no usage row, nothing called")
    void refusedLikeTheCeiling() {
        AgentLlmUsageRepository usage = spent(240_000, 0, 0); // $0.96
        AgentQuotaService quota = new AgentQuotaService(usage, null);
        quota.setBudget(budget(1));
        AgentQuotaService.Charge refused = quota.chargeCheck(ORG, AgentUsageKind.DRAFT, "FIGURE_CHECK",
                AgentUsageActor.USER);
        assertThat(refused.decision().allowed()).isFalse();
        assertThat(refused.usageId()).isNull();
        verify(usage, never()).save(any());

        AgentLlmUsageRepository room = spent(0, 0, 0);
        when(room.save(any())).thenAnswer(i -> i.getArgument(0));
        AgentQuotaService open = new AgentQuotaService(room, null);
        open.setBudget(budget(1));
        assertThat(open.chargeCheck(ORG, AgentUsageKind.DRAFT, "FIGURE_CHECK", AgentUsageActor.USER).decision().allowed())
                .as("within the budget, the call is metered and goes").isTrue();
        verify(room).save(any());
    }
}
