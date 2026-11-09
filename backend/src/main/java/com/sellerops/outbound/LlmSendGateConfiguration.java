package com.sellerops.outbound;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * <b>The model-send check stands on the server too</b> — {@code docs/standing/server_operation_boundary_v1.md} §4.1-1
 * (owner decision and external review 2026-10-31). The Reviewnary server holds every seller's customers' text, so
 * what a seller-PC install checks before a model call (phone, e-mail and long numbers removed; addresses, undeclared
 * images and unreadable bodies held) is checked here as well. On unless {@code sellerops.llm.send-gate.enabled} is
 * false, which only the hermetic test profile sets.
 */
@Configuration
public class LlmSendGateConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmSendGateConfiguration.class);

    public LlmSendGateConfiguration(@Value("${sellerops.llm.send-gate.enabled:true}") boolean enabled) {
        LlmSendGate.arm(enabled);
        log.info("LLM send gate {}", enabled ? "armed" : "off (sellerops.llm.send-gate.enabled=false)");
    }

    @PreDestroy
    void disarm() {
        LlmSendGate.arm(false);
    }
}
