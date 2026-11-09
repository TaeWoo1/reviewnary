package com.sellerops.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>Each step of a reply-agent run is on the meter, with what started it and what it took</b> (owner, 2026-10-28:
 * 「모니터링 잘 할 수 있도록만 해 줘」). A run no seller started is metered as {@code RUN} and does not spend the seller's
 * own limit.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReplyStepUsageTest {

    @Autowired
    private AgentLlmUsageRepository usage;

    private AgentQuotaService quota(int limit) {
        return new AgentQuotaService(usage, new AgentQuotaProperties(true, true, true, limit, limit));
    }

    @Test
    @DisplayName("a step is charged with its step and trigger, and its time and tokens land on the same row")
    void aStepCarriesItsStepTriggerTimeAndTokens() {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = quota(100);

        AgentQuotaService.Charge classify = quota.consumeStep(org, "run-1", "CLASSIFY", "INGEST", AgentUsageActor.RUN);
        quota.recordOutcome(classify.usageId(), 812, 1200, 40);
        AgentQuotaService.Charge draft = quota.consumeStep(org, "run-1", "DRAFT", "INGEST", AgentUsageActor.RUN);
        quota.recordOutcome(draft.usageId(), 2410, 2600, 180);
        quota.consumeStep(org, "run-2", "DRAFT", "SELLER", AgentUsageActor.USER);

        AgentQuotaService.UsageReport report = quota.report(org, LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertThat(report.rows()).extracting(AgentQuotaService.UsageRow::step, AgentQuotaService.UsageRow::trigger,
                        AgentQuotaService.UsageRow::actor, AgentQuotaService.UsageRow::calls)
                .containsExactly(
                        tuple("CLASSIFY", "INGEST", "RUN", 1L),
                        tuple("DRAFT", "INGEST", "RUN", 1L),
                        tuple("DRAFT", "SELLER", "USER", 1L));
        AgentQuotaService.UsageRow first = report.rows().get(0);
        assertThat(first.elapsedMs()).isEqualTo(812);
        assertThat(first.inputTokens()).isEqualTo(1200);
        assertThat(first.outputTokens()).isEqualTo(40);
        assertThat(report.sellerAndRunCalls()).isEqualTo(3);
        // The actual ceiling counts every call (owner, 2026-10-29); the seller's count is the one run they started.
        assertThat(quota.status(org).llmCallsUsed()).isEqualTo(3);
        assertThat(quota.status(org).itemsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("one actual AI ceiling counts every call — the seller's, automatic runs', and checks (owner, 2026-10-29)")
    void oneCeilingCountsEveryCall() {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = new AgentQuotaService(usage, new AgentQuotaProperties(true, true, true, 100, 3, 100));
        assertThat(quota.consumeStep(org, "s-1", "CLASSIFY", "SELLER", AgentUsageActor.USER).decision().allowed()).isTrue();
        assertThat(quota.consumeStep(org, "r-1", "CLASSIFY", "INGEST", AgentUsageActor.RUN).decision().allowed()).isTrue();
        AgentQuotaService.Charge check = quota.chargeCheck(org, AgentUsageKind.DRAFT, AgentQuotaService.CHECK_STEP,
                AgentUsageActor.USER);
        assertThat(check.decision().allowed()).isTrue();
        quota.recordOutcome(check.usageId(), 640, 900, 30);
        assertThat(quota.hasRoom(org)).isFalse();
        assertThat(quota.consumeStep(org, "r-2", "CLASSIFY", "INGEST", AgentUsageActor.RUN).decision().allowed())
                .as("an automatic judgement is under the same ceiling").isFalse();
        assertThat(quota.chargeCheck(org, AgentUsageKind.DRAFT, AgentQuotaService.CHECK_STEP, AgentUsageActor.USER)
                .decision().allowed()).as("so is a fact check").isFalse();
        assertThat(quota.status(org).llmCallsUsed()).isEqualTo(3);
        AgentQuotaService.UsageReport report = quota.report(org, LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertThat(report.rows()).filteredOn(r -> AgentQuotaService.CHECK_STEP.equals(r.step())).singleElement()
                .satisfies(r -> assertThat(r.inputTokens()).isEqualTo(900));
    }

    @Test
    @DisplayName("the seller-facing count is pieces of work, not calls — a run's later steps are not new work")
    void theSellerCountIsWorkNotCalls() {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = new AgentQuotaService(usage, new AgentQuotaProperties(true, true, true, 100, 100, 1));
        assertThat(quota.consumeStep(org, "s-1", "CLASSIFY", "SELLER", AgentUsageActor.USER).decision().allowed()).isTrue();
        assertThat(quota.consumeStep(org, "s-1", "DRAFT", "SELLER", AgentUsageActor.USER).decision().allowed())
                .as("the same run's next step").isTrue();
        assertThat(quota.chargeCheck(org, AgentUsageKind.DRAFT, AgentQuotaService.CHECK_STEP, AgentUsageActor.USER)
                .decision().allowed()).as("a check is not new work").isTrue();
        assertThat(quota.status(org).itemsUsed()).isEqualTo(1);
        assertThat(quota.consumeStep(org, "s-2", "CLASSIFY", "SELLER", AgentUsageActor.USER).decision())
                .satisfies(d -> {
                    assertThat(d.allowed()).isFalse();
                    assertThat(d.reason()).isEqualTo(QuotaDecision.Reason.DAILY_ITEMS);
                });
        assertThat(quota.consumeStep(org, "r-1", "CLASSIFY", "INGEST", AgentUsageActor.RUN).decision().allowed())
                .as("automatic work is not the seller's count").isTrue();
    }
}
