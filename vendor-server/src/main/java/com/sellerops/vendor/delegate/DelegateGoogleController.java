package com.sellerops.vendor.delegate;

import com.sellerops.vendor.auth.SessionTokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A delegate's way in through Google — contract §2 (D3 revised 2026-10-26), the steps in {@link GoogleDelegates}.
 * All four calls are made without a session. Errors are a fixed code; Google's own answer never reaches the page.
 */
@RestController
@RequestMapping("/v1/delegate/auth")
public class DelegateGoogleController {

    /** {@code inviteCode}: required to join, ignored to sign in. */
    public record StartRequest(@NotNull GoogleDelegates.Purpose purpose, String inviteCode, @NotBlank String binding) {}

    public record StartResponse(String url) {}

    public record FinishRequest(@NotBlank String handoff, @NotBlank String binding) {}

    public record Methods(boolean google) {}

    private final GoogleDelegates google;
    private final SessionTokens sessions;

    public DelegateGoogleController(GoogleDelegates google, SessionTokens sessions) {
        this.google = google;
        this.sessions = sessions;
    }

    /** Which ways in the pages offer. */
    @GetMapping("/methods")
    public Methods methods() {
        return new Methods(google.enabled());
    }

    @PostMapping("/google/start")
    public ResponseEntity<?> start(@RequestBody @Valid StartRequest request) {
        try {
            return ResponseEntity.ok(new StartResponse(google.start(request.purpose(), request.inviteCode(),
                    request.binding())));
        } catch (GoogleDelegates.GoogleException e) {
            return failure(e);
        }
    }

    /** Google sends the browser here; it leaves for the desk with the outcome in the fragment, which no server sees. */
    @GetMapping("/google/callback") // = GoogleSignIn.CALLBACK_PATH, the address registered with Google
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/delegate#" + google.callback(state, code, error)))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/google/finish")
    public ResponseEntity<?> finish(@RequestBody @Valid FinishRequest request) {
        try {
            UUID delegate = google.finish(request.handoff(), request.binding());
            SessionTokens.Issued issued = sessions.issue(SessionTokens.Kind.DELEGATE, delegate);
            return ResponseEntity.ok(new DelegateAuthController.SessionResponse(issued.token(), issued.expiresAt()));
        } catch (GoogleDelegates.GoogleException e) {
            return failure(e);
        }
    }

    private static ResponseEntity<?> failure(GoogleDelegates.GoogleException e) {
        HttpStatus status = switch (e.failure()) {
            case NOT_ENABLED -> HttpStatus.NOT_FOUND;
            case ALREADY_REGISTERED -> HttpStatus.CONFLICT;
            case NOT_REGISTERED -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.failure().name().toLowerCase()));
    }
}
