package com.sellerops.vendor.install;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two calls a seller PC makes before it holds any credential — contract §2. Shapes follow RFC 8628 like the
 * backend's helper grant: {@code code} issues the pair, {@code token} polls and answers
 * {@code authorization_pending} / {@code expired_token} as 400 with an {@code error} field, or the token once.
 */
@RestController
@RequestMapping("/v1/installs")
public class InstallGrantController {

    public record CodeRequest(@NotBlank String deviceName, String appVersion, @NotBlank String publicKey) {}

    public record CodeResponse(String deviceCode, String userCode, Instant expiresAt, int interval) {}

    public record TokenRequest(@NotBlank String deviceCode) {}

    public record TokenResponse(String token, UUID installId, Instant expiresAt) {}

    private final Installs installs;

    public InstallGrantController(Installs installs) {
        this.installs = installs;
    }

    @PostMapping("/code")
    public ResponseEntity<?> code(@RequestBody @Valid CodeRequest request, HttpServletRequest http) {
        try {
            InstallGrants.Issued issued = installs.start(request.deviceName(), request.appVersion(),
                    request.publicKey(), http.getRemoteAddr());
            return ResponseEntity.ok(new CodeResponse(issued.deviceCode(), issued.userCode(), issued.expiresAt(),
                    issued.intervalSeconds()));
        } catch (Installs.StartException e) {
            HttpStatus status = switch (e.failure()) {
                case BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
                case TOO_MANY -> HttpStatus.TOO_MANY_REQUESTS;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(Map.of("error", e.failure().name().toLowerCase()));
        }
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody @Valid TokenRequest request) {
        Installs.Verdict v = installs.redeem(request.deviceCode());
        return switch (v.outcome()) {
            case APPROVED -> ResponseEntity.ok(new TokenResponse(v.redeemed().token(), v.redeemed().installId(),
                    v.redeemed().expiresAt()));
            case AUTHORIZATION_PENDING -> ResponseEntity.badRequest().body(Map.of("error", "authorization_pending"));
            case EXPIRED_TOKEN -> ResponseEntity.badRequest().body(Map.of("error", "expired_token"));
        };
    }
}
