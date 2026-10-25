package com.sellerops.vendor.delegate;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.LoginThrottle;
import com.sellerops.vendor.auth.SessionRevocations;
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
import org.springframework.web.bind.annotation.RequestHeader;
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

    public record PeekRequest(@NotBlank String inviteCode) {}

    /** {@code email} is masked ({@link #mask}): the page names the address without spelling it out on screen. */
    public record PeekResponse(String email, Delegates.Role role, Instant expiresAt) {}

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
    private final SessionRevocations revocations;

    public DelegateAuthController(Delegates delegates, SessionTokens sessions, LoginThrottle throttle,
                                  SessionRevocations revocations) {
        this.delegates = delegates;
        this.sessions = sessions;
        this.throttle = throttle;
        this.revocations = revocations;
    }

    /**
     * What an invite makes, before anything is chosen: the join page says who it is for and until when. The code is
     * the credential, as on the seller's peek; the address comes back masked because it is drawn on a screen that may
     * be shared, and Google is told the whole address separately (login_hint).
     */
    @PostMapping("/invites/peek")
    public ResponseEntity<?> peek(@RequestBody @Valid PeekRequest request) {
        return delegates.invite(request.inviteCode())
                .<ResponseEntity<?>>map(i -> ResponseEntity.ok(new PeekResponse(mask(i.email()), i.role(),
                        i.expiresAt())))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of("error", "invalid_invite")));
    }

    /** {@code reviewnary.kr@gmail.com} → {@code re***@gmail.com}: two letters of the name, the domain whole. */
    static String mask(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        // A short name keeps one letter, so the mask never shows the whole of it.
        int keep = at > 2 ? 2 : 1;
        return email.substring(0, keep) + "***" + email.substring(at);
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

    /** Ends this one session (the route required a live delegate session); the delegate's others keep working. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
        sessions.verifyAny(authorization.substring("Bearer ".length()).trim()).ifPresent(revocations::revoke);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<?> failure(Delegates.EnrolException e) {
        HttpStatus status = e.failure() == Delegates.EnrolFailure.ALREADY_REGISTERED
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.failure().name().toLowerCase()));
    }
}
