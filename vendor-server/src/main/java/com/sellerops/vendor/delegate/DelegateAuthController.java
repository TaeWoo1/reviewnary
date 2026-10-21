package com.sellerops.vendor.delegate;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.LoginThrottle;
import com.sellerops.vendor.auth.SessionTokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three calls a delegate makes without a session — contract §2, D2 · D3: stage an invite (password → TOTP
 * secret), confirm it with the first authenticator code (→ account, recovery codes, session), and sign in. Errors
 * are a fixed code; a sign-in failure never says which factor was wrong or whether the address exists.
 */
@RestController
@RequestMapping("/v1/delegate/auth")
public class DelegateAuthController {

    /** Delegate failures share the seller throttle's memory but never its keys. */
    static final String THROTTLE_PREFIX = "delegate:";

    public record StageRequest(@NotBlank String inviteCode, @NotBlank String password) {}

    public record ConfirmRequest(@NotBlank String inviteCode, @NotBlank String otp) {}

    /** Exactly one of {@code otp} and {@code recoveryCode}. */
    public record LoginRequest(@NotBlank String email, @NotBlank String password, String otp, String recoveryCode) {}

    /** {@code qr}: the same URI as rows of modules ({@link OtpQr}) for the join page to draw. */
    public record StageResponse(String secret, String otpauthUri, List<String> qr) {}

    public record EnrolledResponse(String token, Instant expiresAt, List<String> recoveryCodes) {}

    public record SessionResponse(String token, Instant expiresAt) {}

    private final Delegates delegates;
    private final SessionTokens sessions;
    private final LoginThrottle throttle;

    public DelegateAuthController(Delegates delegates, SessionTokens sessions, LoginThrottle throttle) {
        this.delegates = delegates;
        this.sessions = sessions;
        this.throttle = throttle;
    }

    @PostMapping("/invites/stage")
    public ResponseEntity<?> stage(@RequestBody @Valid StageRequest request) {
        try {
            Delegates.Staged staged = delegates.stage(request.inviteCode(), request.password());
            return ResponseEntity.ok(new StageResponse(staged.secret(), staged.otpauthUri(),
                    OtpQr.rows(staged.otpauthUri())));
        } catch (Delegates.EnrolException e) {
            return failure(e);
        }
    }

    @PostMapping("/invites/confirm")
    public ResponseEntity<?> confirm(@RequestBody @Valid ConfirmRequest request) {
        try {
            Delegates.Enrolled enrolled = delegates.confirm(request.inviteCode(), request.otp());
            SessionTokens.Issued issued = sessions.issue(SessionTokens.Kind.DELEGATE, enrolled.delegateId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new EnrolledResponse(issued.token(), issued.expiresAt(), enrolled.recoveryCodes()));
        } catch (Delegates.EnrolException e) {
            return failure(e);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginRequest request) {
        String key = THROTTLE_PREFIX + SellerAccounts.normalizeEmail(request.email());
        if (!throttle.allowed(key)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "too_many_attempts"));
        }
        Optional<UUID> delegate = delegates.login(request.email(), request.password(), request.otp(),
                request.recoveryCode());
        if (delegate.isEmpty()) {
            throttle.failed(key);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_login"));
        }
        throttle.succeeded(key);
        SessionTokens.Issued issued = sessions.issue(SessionTokens.Kind.DELEGATE, delegate.get());
        return ResponseEntity.ok(new SessionResponse(issued.token(), issued.expiresAt()));
    }

    private static ResponseEntity<?> failure(Delegates.EnrolException e) {
        HttpStatus status = e.failure() == Delegates.EnrolFailure.ALREADY_REGISTERED
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.failure().name().toLowerCase()));
    }
}
