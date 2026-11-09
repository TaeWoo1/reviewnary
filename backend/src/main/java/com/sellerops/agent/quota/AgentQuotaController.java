package com.sellerops.agent.quota;

import com.sellerops.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * READ: how much of today's Agent budget this org has spent.
 *
 * <p>Visible on purpose. A ceiling nobody can see is a ceiling that is only ever discovered by
 * hitting it, and the seller's first experience of it would be a refusal with no context.
 */
@RestController
@RequestMapping("/api/agent/quota")
public class AgentQuotaController {

    private final AgentQuotaService quota;

    public AgentQuotaController(AgentQuotaService quota) {
        this.quota = quota;
    }

    /** One day's usage by kind · step · trigger · actor (2026-10-28). {@code date} is a KST day; today when absent. */
    @GetMapping("/usage")
    public AgentQuotaService.UsageReport usage(@AuthenticationPrincipal AuthPrincipal principal,
                                               @org.springframework.web.bind.annotation.RequestParam(required = false)
                                               String date) {
        java.time.LocalDate day;
        try {
            day = date == null || date.isBlank() ? java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                    : java.time.LocalDate.parse(date.strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw com.sellerops.common.ApiException.badRequest("날짜는 YYYY-MM-DD로 보내 주세요.");
        }
        return quota.report(principal.orgId(), day);
    }

    @GetMapping
    public AgentQuotaService.AgentQuotaStatus status(@AuthenticationPrincipal AuthPrincipal principal) {
        return quota.status(principal.orgId());
    }
}
