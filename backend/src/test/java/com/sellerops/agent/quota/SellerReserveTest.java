package com.sellerops.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.reply.ReplyAgentService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>Automatic work leaves the seller's reserve, and starts only when it can finish</b> (sixth outside review,
 * 2026-10-29). One ceiling still bounds every call; the seller's own presses may use all of it, automatic work stops
 * {@code reserve} calls short, and work that is just starting asks for room for the calls it will still need.
 */
@DataJpaTest
@ActiveProfiles("test")
class SellerReserveTest {

    @Autowired
    private AgentLlmUsageRepository usage;

    /** A ceiling of 10 with 4 kept for the seller: automatic work stops at 6. */
    private AgentQuotaService quota() {
        return new AgentQuotaService(usage, new AgentQuotaProperties(true, true, true, 100, 10, 100, 4));
    }

    @Test
    @DisplayName("automatic calls stop at the ceiling less the reserve; the seller's go on to the ceiling")
    void automaticWorkStopsAtTheReserve() {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = quota();
        for (int i = 0; i < 6; i++) {
            assertThat(quota.consumeStep(org, null, "CLASSIFY", "INGEST", AgentUsageActor.RUN).decision().allowed())
                    .as("automatic call %d", i + 1).isTrue();
        }
        QuotaDecision refused = quota.consumeStep(org, null, "CLASSIFY", "INGEST", AgentUsageActor.RUN).decision();
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).isEqualTo(QuotaDecision.Reason.AUTOMATIC_SHARE);
        assertThat(quota.hasRoom(org, AgentUsageActor.RUN)).isFalse();
        assertThat(quota.hasRoom(org, AgentUsageActor.USER)).isTrue();
        assertThat(quota.chargeCheck(org, AgentUsageKind.DRAFT, AgentQuotaService.CHECK_STEP, AgentUsageActor.RUN)
                .decision().allowed()).as("an automatic fact check is automatic work too").isFalse();

        for (int i = 0; i < 4; i++) {
            assertThat(quota.consume(org, AgentUsageKind.DRAFT, null, AgentUsageActor.USER).allowed())
                    .as("seller call %d inside the reserve", i + 1).isTrue();
        }
        QuotaDecision full = quota.consume(org, AgentUsageKind.DRAFT, null, AgentUsageActor.USER);
        assertThat(full.allowed()).isFalse();
        assertThat(full.reason()).isEqualTo(QuotaDecision.Reason.DAILY_LLM_CALLS);
        assertThat(quota.report(org, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))))
                .satisfies(r -> {
                    assertThat(r.automaticCallsLimit()).isEqualTo(6);
                    assertThat(r.automaticCalls()).isEqualTo(6);
                    assertThat(r.sellerAndRunCalls()).isEqualTo(10);
                });
    }

    @Test
    @DisplayName("a run opens only when every call it may still make fits; its later steps are not asked again")
    void aRunOpensOnlyWhenItCanFinish() {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = quota();
        // Two automatic calls spent: 4 left before the reserve. A run that judges first may make 5 — it must not open.
        quota.consumeStep(org, null, "CLASSIFY", "INGEST", AgentUsageActor.RUN);
        quota.consumeStep(org, null, "CLASSIFY", "INGEST", AgentUsageActor.RUN);
        assertThat(quota.consumeStep(org, "rvr-1", "CLASSIFY", "INGEST", AgentUsageActor.RUN,
                ReplyAgentService.callsAfter("CLASSIFY")).decision().allowed()).isFalse();
        // With the judgement reused the run starts at its draft, which needs 4 in all — that fits.
        assertThat(quota.consumeStep(org, "rvr-2", "DRAFT", "INGEST", AgentUsageActor.RUN,
                ReplyAgentService.callsAfter("DRAFT")).decision().allowed()).isTrue();
        // Its next step belongs to a run already open: only the call itself must fit.
        assertThat(quota.consumeStep(org, "rvr-2", "CRITIQUE", "INGEST", AgentUsageActor.RUN,
                ReplyAgentService.callsAfter("CRITIQUE")).decision().allowed()).isTrue();
        // The seller's run of the same shape still opens: the reserve is theirs.
        assertThat(quota.consumeStep(org, "rvr-3", "CLASSIFY", "SELLER", AgentUsageActor.USER,
                ReplyAgentService.callsAfter("CLASSIFY")).decision().allowed()).isTrue();
    }

    @Test
    @DisplayName("an inquiry draft starts only when its claim check fits after it")
    void anInquiryDraftLeavesRoomForItsCheck() {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = quota();
        for (int i = 0; i < 5; i++) {
            quota.consume(org, AgentUsageKind.DRAFT, null, AgentUsageActor.RUN);
        }
        // One automatic call left: a draft that needs a check after it does not start.
        assertThat(quota.consume(org, AgentUsageKind.DRAFT, null, AgentUsageActor.RUN, 1).allowed()).isFalse();
        assertThat(quota.consume(org, AgentUsageKind.DRAFT, null, AgentUsageActor.RUN, 0).allowed()).isTrue();
    }

    @Test
    @DisplayName("a reserve larger than the ceiling leaves automatic work nothing, never a negative limit")
    void anOversizedReserveIsClamped() {
        AgentQuotaProperties p = new AgentQuotaProperties(true, true, true, 100, 10, 100, 50);
        assertThat(p.sellerReserve()).isEqualTo(10);
        assertThat(p.automaticCalls()).isZero();
    }
}
