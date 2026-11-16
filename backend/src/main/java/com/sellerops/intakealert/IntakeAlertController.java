package com.sellerops.intakealert;

import com.sellerops.auth.AuthPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own intake-alert settings, mock inbox and notice pages. Every read is the principal's org + user. */
@RestController
@RequestMapping("/api/intake-alerts")
public class IntakeAlertController {

    private final IntakeAlertSettingsService service;

    public IntakeAlertController(IntakeAlertSettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public IntakeAlertViews.SettingsView settings(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.get(principal.orgId(), principal.userId());
    }

    @PutMapping("/settings")
    public IntakeAlertViews.SettingsView update(@AuthenticationPrincipal AuthPrincipal principal,
                                                @RequestBody IntakeAlertViews.SettingsRequest request) {
        return service.update(principal.orgId(), principal.userId(), request);
    }

    @GetMapping("/deliveries")
    public List<IntakeAlertViews.DeliveryRow> deliveries(@AuthenticationPrincipal AuthPrincipal principal,
                                                         @RequestParam(defaultValue = "30") int limit) {
        return service.deliveries(principal.orgId(), principal.userId(), limit);
    }

    @GetMapping("/deliveries/{deliveryId}")
    public IntakeAlertViews.DeliveryDetail delivery(@AuthenticationPrincipal AuthPrincipal principal,
                                                    @PathVariable UUID deliveryId) {
        return service.detail(principal.orgId(), principal.userId(), deliveryId);
    }
}
