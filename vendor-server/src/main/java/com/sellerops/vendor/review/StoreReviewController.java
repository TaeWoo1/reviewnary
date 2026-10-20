package com.sellerops.vendor.review;

import com.sellerops.vendor.auth.BearerAuthFilter.Principal;
import com.sellerops.vendor.store.Stores;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller's side of the review exchange — contract §3.2. A PC (install token) pulls events after its cursor and
 * confirms what it saved; the PC or the seller marks a review as needing no reply. Only stores of the caller's own
 * account; another account's store is the same 404 as one that does not exist.
 */
@RestController
public class StoreReviewController {

    public record AckRequest(@NotNull Long cursor) {}

    public record DispositionRequest(@NotNull Boolean noReplyNeeded, @NotBlank String commandId) {}

    private final Stores stores;
    private final StoreReviews reviews;

    public StoreReviewController(Stores stores, StoreReviews reviews) {
        this.stores = stores;
        this.reviews = reviews;
    }

    @GetMapping("/v1/stores/{storeId}/reviews")
    public ResponseEntity<?> feed(@AuthenticationPrincipal Principal pc, @PathVariable UUID storeId,
                                  @RequestParam(defaultValue = "0") long cursor,
                                  @RequestParam(defaultValue = "100") int limit) {
        if (!ownStore(pc, storeId)) {
            return ResponseEntity.notFound().build();
        }
        if (cursor < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_cursor"));
        }
        return ResponseEntity.ok(reviews.feed(storeId, cursor, limit));
    }

    @PostMapping("/v1/stores/{storeId}/reviews/ack")
    public ResponseEntity<?> ack(@AuthenticationPrincipal Principal pc, @PathVariable UUID storeId,
                                 @RequestBody @Valid AckRequest request) {
        if (!ownStore(pc, storeId)) {
            return ResponseEntity.notFound().build();
        }
        return reviews.ack(storeId, pc.installId(), request.cursor()) ? ResponseEntity.noContent().build()
                : ResponseEntity.badRequest().body(Map.of("error", "invalid_cursor"));
    }

    @PostMapping("/v1/stores/{storeId}/reviews/{reviewId}/disposition")
    public ResponseEntity<?> disposition(@AuthenticationPrincipal Principal caller, @PathVariable UUID storeId,
                                         @PathVariable UUID reviewId, @RequestBody @Valid DispositionRequest request) {
        if (!ownStore(caller, storeId)) {
            return ResponseEntity.notFound().build();
        }
        if (!request.noReplyNeeded()) {
            // Only the one judgement exists; withdrawing it is not part of the contract.
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported_disposition"));
        }
        return reviews.markNoReplyNeeded(storeId, reviewId) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private boolean ownStore(Principal caller, UUID storeId) {
        return stores.accountOf(storeId).filter(caller.accountId()::equals).isPresent();
    }
}
