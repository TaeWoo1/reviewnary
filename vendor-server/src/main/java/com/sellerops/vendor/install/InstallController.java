package com.sellerops.vendor.install;

import com.sellerops.vendor.auth.BearerAuthFilter.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Installs seen from both sides — contract §2, D2. The seller (session) approves a PC's code, lists their PCs and
 * revokes one; a PC (install token) reads and removes only its own row. Which caller may reach which route is
 * decided in {@code SecurityConfig}, not here.
 */
@RestController
public class InstallController {

    public record ApproveRequest(@NotBlank String userCode) {}

    private final Installs installs;

    public InstallController(Installs installs) {
        this.installs = installs;
    }

    @PostMapping("/v1/installs/approve")
    public ResponseEntity<?> approve(@AuthenticationPrincipal Principal seller,
                                     @RequestBody @Valid ApproveRequest request) {
        return installs.approve(seller.accountId(), request.userCode())
                .<ResponseEntity<?>>map(name -> ResponseEntity.ok(Map.of("deviceName", name)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "unknown_code")));
    }

    @GetMapping("/v1/installs")
    public List<Installs.View> list(@AuthenticationPrincipal Principal seller) {
        return installs.list(seller.accountId());
    }

    @DeleteMapping("/v1/installs/{installId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Principal seller, @PathVariable UUID installId) {
        return installs.revoke(seller.accountId(), installId) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/v1/installs/me")
    public ResponseEntity<Installs.View> me(@AuthenticationPrincipal Principal install) {
        return ResponseEntity.of(installs.view(install.accountId(), install.installId()));
    }

    @DeleteMapping("/v1/installs/me")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal Principal install) {
        installs.revoke(install.accountId(), install.installId());
        return ResponseEntity.noContent().build();
    }
}
