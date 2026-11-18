package com.sellerops.product.detail.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.AgentUsageKind;
import com.sellerops.agent.quota.QuotaDecision;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every 상세페이지 picture is charged before the vendor is reached (2026-11-19, FINDINGS B215): the first pilot run made
 * 25 calls and left no {@code agent_llm_usage} row, so the day's ceiling and the money budget neither counted nor
 * stopped it.
 */
class ImageFactExtractionServiceTest {

    private final UUID org = UUID.randomUUID();
    private final UUID usageId = UUID.randomUUID();
    private AgentLlmTransport transport;
    private AgentQuotaService quota;
    private ImageFactExtractionService service;

    @BeforeEach
    void setUp() {
        transport = mock(AgentLlmTransport.class);
        quota = mock(AgentQuotaService.class);
        ImageKnowledgeProperties properties = new ImageKnowledgeProperties(true, org.toString(), "ANTHROPIC",
                "claude-test", "test-key", 1200, "none");
        service = new ImageFactExtractionService(properties, transport, quota);
    }

    @Test
    @DisplayName("no room on the meter: refused before the vendor, and the reason says so")
    void refusedWhenTheMeterHasNoRoom() {
        QuotaDecision none = new QuotaDecision(false, null, 80, 80);
        when(quota.chargeCheck(org, AgentUsageKind.IMAGE, ImageFactExtractionService.READ_STEP, AgentUsageActor.RUN))
                .thenReturn(new AgentQuotaService.Charge(none, null));

        ImageFactExtractionGenerator.Result result = service.read(org, new byte[] {1, 2, 3}, "image/jpeg");

        assertThat(result.reason()).isEqualTo(ImageFactExtractionService.QUOTA_REFUSED);
        verify(transport, never()).postProductImages(any(), any(), anyString());
        verify(quota, never()).recordOutcome(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("room on the meter: charged as an image read, then the call's outcome is recorded on that row")
    void chargedThenRecorded() {
        when(quota.chargeCheck(org, AgentUsageKind.IMAGE, ImageFactExtractionService.READ_STEP, AgentUsageActor.RUN))
                .thenReturn(new AgentQuotaService.Charge(QuotaDecision.passWithoutCharge(), usageId));
        when(transport.postProductImages(any(), any(), anyString()))
                .thenReturn(new AgentLlmTransport.Response(500, "vendor down"));

        ImageFactExtractionGenerator.Result result = service.read(org, new byte[] {1, 2, 3}, "image/jpeg");

        assertThat(result.reason()).isEqualTo("http:500");
        verify(transport).postProductImages(any(), any(), anyString());
        // No tokens reported ⇒ stored as unknown, which the money budget prices at the call's worst case.
        verify(quota).recordOutcome(eq(usageId), anyLong(), isNull(), isNull());
    }

    @Test
    @DisplayName("a seller's own press is charged as the seller's")
    void sellerPressIsTheSellers() {
        QuotaDecision none = new QuotaDecision(false, null, 80, 80);
        when(quota.chargeCheck(org, AgentUsageKind.IMAGE, ImageFactExtractionService.READ_STEP, AgentUsageActor.USER))
                .thenReturn(new AgentQuotaService.Charge(none, null));

        service.read(org, new byte[] {1}, "image/jpeg", AgentUsageActor.USER);

        verify(quota).chargeCheck(org, AgentUsageKind.IMAGE, ImageFactExtractionService.READ_STEP, AgentUsageActor.USER);
    }

    @Test
    @DisplayName("an org outside the allow-list is never charged")
    void disabledOrgIsNotCharged() {
        ImageFactExtractionGenerator.Result result =
                service.read(UUID.randomUUID(), new byte[] {1}, "image/jpeg");

        assertThat(result.reason()).isEqualTo("disabled");
        verify(quota, never()).chargeCheck(any(), any(), any(), any());
    }
}
