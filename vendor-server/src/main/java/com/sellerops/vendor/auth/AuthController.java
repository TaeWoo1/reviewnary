package com.sellerops.vendor.auth;

import com.sellerops.vendor.account.SellerAccounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two calls a seller makes without a session — contract §2, D2: redeem an invite (the only way an account comes
 * to exist) and log in. Errors are a fixed code, never a message that says which half of a login was wrong.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    public record RedeemRequest(@NotBlank String inviteCode, @NotBlank String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record SessionResponse(String token, Instant expiresAt) {}

    private final SellerAccounts accounts;
    private final SessionTokens sessions;
    private final LoginThrottle throttle;
    private final SessionRevocations revocations;

    public AuthController(SellerAccounts accounts, SessionTokens sessions, LoginThrottle throttle,
                          SessionRevocations revocations) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.throttle = throttle;
        this.revocations = revocations;
    }

    @PostMapping("/invites/redeem")
    public ResponseEntity<?> redeem(@RequestBody @Valid RedeemRequest request) {
        try {
            UUID id = accounts.redeem(request.inviteCode(), request.password());
            SessionTokens.Issued issued = sessions.issue(id);
            return ResponseEntity.status(HttpStatus.CREATED).body(new SessionResponse(issued.token(),
                    issued.expiresAt()));
        } catch (SellerAccounts.RedeemException e) {
            HttpStatus status = e.failure() == SellerAccounts.RedeemFailure.ALREADY_REGISTERED
                    ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("error", e.failure().name().toLowerCase()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginRequest request) {
        String email = SellerAccounts.normalizeEmail(request.email());
        if (!throttle.allowed(email)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "too_many_attempts"));
        }
        Optional<UUID> account = accounts.login(email, request.password());
        if (account.isEmpty()) {
            throttle.failed(email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_login"));
        }
        throttle.succeeded(email);
        SessionTokens.Issued issued = sessions.issue(account.get());
        return ResponseEntity.ok(new SessionResponse(issued.token(), issued.expiresAt()));
    }

    /**
     * Ends the session this request carries — only that one; the seller's other sessions keep working. The route
     * already required a live seller session, so the bearer here is exactly that one.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
        sessions.verifyAny(authorization.substring("Bearer ".length()).trim()).ifPresent(revocations::revoke);
        return ResponseEntity.noContent().build();
    }
}
