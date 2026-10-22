package com.sellerops.vendor.auth;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.OtpQr;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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

    public record PeekRequest(@NotBlank String inviteCode) {}

    public record PeekResponse(String email) {}

    /**
     * A new account's session, and where its phone approval page is — the join page shows that address and its QR
     * (rows as {@link OtpQr#rows}) so the seller can open it on the phone.
     */
    public record RedeemResponse(String token, Instant expiresAt, String email, String phoneUrl,
                                 List<String> phoneQr) {}

    private final SellerAccounts accounts;
    private final SessionTokens sessions;
    private final LoginThrottle throttle;
    private final SessionRevocations revocations;

    private final String publicUrl;

    public AuthController(SellerAccounts accounts, SessionTokens sessions, LoginThrottle throttle,
                          SessionRevocations revocations, @Value("${vendor.public-url:}") String publicUrl) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.throttle = throttle;
        this.revocations = revocations;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
    }

    /** Which account an invite makes, before the password is chosen. The code itself is the credential. */
    @PostMapping("/invites/peek")
    public ResponseEntity<?> peek(@RequestBody @Valid PeekRequest request) {
        return accounts.inviteEmail(request.inviteCode())
                .<ResponseEntity<?>>map(email -> ResponseEntity.ok(new PeekResponse(email)))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of("error", "invalid_invite")));
    }

    @PostMapping("/invites/redeem")
    public ResponseEntity<?> redeem(@RequestBody @Valid RedeemRequest request) {
        try {
            String email = accounts.inviteEmail(request.inviteCode()).orElse(null);
            UUID id = accounts.redeem(request.inviteCode(), request.password());
            SessionTokens.Issued issued = sessions.issue(id);
            String phoneUrl = (publicUrl.isEmpty()
                    ? ServletUriComponentsBuilder.fromCurrentContextPath().toUriString() : publicUrl) + "/replies";
            return ResponseEntity.status(HttpStatus.CREATED).body(new RedeemResponse(issued.token(),
                    issued.expiresAt(), email, phoneUrl, OtpQr.rows(phoneUrl)));
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
