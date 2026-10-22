package com.sellerops.vendor.install;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.BearerAuthFilter.Principal;
import com.sellerops.vendor.auth.StepUps;
import com.sellerops.vendor.ip.ClientIpv4;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Installs seen from both sides — contract §2, D2. The seller (session) looks up and approves a PC's code, lists
 * their PCs and revokes one; a PC (install token) reads and removes only its own row. Which caller may reach which route is
 * decided in {@code SecurityConfig}, not here.
 *
 * <p><b>Approving a new PC takes a step-up</b> (§2 추가 인증): a code mailed to the account ({@code step-up}), entered
 * on the same page in the same session ({@code step-up/verify}), and spent by this one approval. Without it the
 * approval is 403 {@code step_up_required}. Once approved, the account's address is told a PC was linked
 * ({@link InstallNotices}).
 */
@RestController
public class InstallController {

    public record ApproveRequest(@NotBlank String userCode) {}

    public record VerifyRequest(@NotBlank String userCode, @NotBlank String code) {}

    /** This PC's row, and the seller account it belongs to — the PC shows "from this account → into this one". */
    public record Me(UUID id, String deviceName, String appVersion, Instant createdAt, Instant lastUsedAt,
                     Instant expiresAt, String accountEmail) {}

    private final Installs installs;
    private final SellerAccounts accounts;
    private final StepUps stepUps;
    private final InstallNotices notices;

    public InstallController(Installs installs, SellerAccounts accounts, StepUps stepUps, InstallNotices notices) {
        this.installs = installs;
        this.accounts = accounts;
        this.stepUps = stepUps;
        this.notices = notices;
    }

    @PostMapping("/v1/installs/approve")
    public ResponseEntity<?> approve(@AuthenticationPrincipal Principal seller,
                                     @RequestBody @Valid ApproveRequest request) {
        String userCode = InstallGrants.normalizeUserCode(request.userCode());
        if (installs.pending(seller.accountId(), userCode, null).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown_code"));
        }
        if (!stepUps.spend(seller.accountId(), seller.sessionId(), StepUps.Purpose.PC_LINK, userCode)) {
            return ResponseEntity.status(403).body(Map.of("error", "step_up_required"));
        }
        return installs.approve(seller.accountId(), userCode)
                .<ResponseEntity<?>>map(name -> {
                    notices.linked(seller.accountId(), name);
                    return ResponseEntity.ok(Map.of("deviceName", name));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "unknown_code")));
    }

    /** Mails this session a code for linking the PC that shows {@code userCode}. */
    @PostMapping("/v1/installs/step-up")
    public ResponseEntity<?> stepUp(@AuthenticationPrincipal Principal seller,
                                    @RequestBody @Valid ApproveRequest request) {
        String userCode = InstallGrants.normalizeUserCode(request.userCode());
        Optional<Installs.PendingView> pending = installs.pending(seller.accountId(), userCode, null);
        if (pending.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown_code"));
        }
        try {
            StepUps.Sent sent = stepUps.send(seller.accountId(), seller.sessionId(), StepUps.Purpose.PC_LINK,
                    userCode, (to, code) -> InstallNotices.codeMail(to, code, pending.get().deviceName()));
            return ResponseEntity.ok(Map.of("sentTo", sent.maskedTo(), "expiresAt", sent.expiresAt()));
        } catch (StepUps.StepUpException e) {
            return refused(e);
        }
    }

    @PostMapping("/v1/installs/step-up/verify")
    public ResponseEntity<?> verify(@AuthenticationPrincipal Principal seller,
                                    @RequestBody @Valid VerifyRequest request) {
        String userCode = InstallGrants.normalizeUserCode(request.userCode());
        if (installs.pending(seller.accountId(), userCode, null).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown_code"));
        }
        try {
            stepUps.verify(seller.accountId(), seller.sessionId(), StepUps.Purpose.PC_LINK, userCode, request.code());
            return ResponseEntity.noContent().build();
        } catch (StepUps.StepUpException e) {
            return refused(e);
        }
    }

    private static ResponseEntity<?> refused(StepUps.StepUpException e) {
        return switch (e.refusal()) {
            case MAIL_OFF, MAIL_FAILED -> ResponseEntity.status(503).body(Map.of("error",
                    e.refusal() == StepUps.Refusal.MAIL_OFF ? "mail_off" : "mail_failed"));
            case TOO_MANY -> ResponseEntity.status(429).body(Map.of("error", "too_many"));
            case LOCKED -> ResponseEntity.status(429).body(Map.of("error", "locked"));
            case EXPIRED -> ResponseEntity.status(410).body(Map.of("error", "expired"));
            case WRONG_CODE -> ResponseEntity.badRequest().body(Map.of("error", "wrong_code", "left", e.left()));
        };
    }

    @PostMapping("/v1/installs/pending")
    public ResponseEntity<?> pending(@AuthenticationPrincipal Principal seller,
                                     @RequestBody @Valid ApproveRequest request, HttpServletRequest http) {
        return installs.pending(seller.accountId(), request.userCode(), ClientIpv4.of(http.getRemoteAddr()).orElse(null))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
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
    public ResponseEntity<Me> me(@AuthenticationPrincipal Principal install) {
        String email = accounts.emailOf(install.accountId()).orElse(null);
        return ResponseEntity.of(installs.view(install.accountId(), install.installId())
                .map(v -> new Me(v.id(), v.deviceName(), v.appVersion(), v.createdAt(), v.lastUsedAt(), v.expiresAt(),
                        email)));
    }

    @DeleteMapping("/v1/installs/me")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal Principal install) {
        installs.revoke(install.accountId(), install.installId());
        return ResponseEntity.noContent().build();
    }
}
