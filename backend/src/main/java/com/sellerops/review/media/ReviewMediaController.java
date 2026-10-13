package com.sellerops.review.media;

import com.sellerops.auth.AuthPrincipal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>The review screen's photos.</b> Two reads — the list, and one photo's bytes — mirroring the case screen's
 * {@code /media/{ordinal}} route. Organisation from the JWT only; a review of another organisation is a 404.
 *
 * <p>Mapped under {@code /api/reviews/{reviewId}/media}: more specific than {@code ReviewDetailController}'s
 * {@code GET /api/reviews/{reviewId}}, and disjoint from the workspace and decision routes on the same prefix.
 */
@RestController
@RequestMapping("/api/reviews/{reviewId}/media")
public class ReviewMediaController {

    private final ReviewMediaReadService service;

    public ReviewMediaController(ReviewMediaReadService service) {
        this.service = service;
    }

    @GetMapping
    public ReviewMediaView list(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID reviewId) {
        return service.list(principal.orgId(), reviewId);
    }

    /** One review photo, for the seller's own screen. 404 when there is none to show. */
    @GetMapping("/{ordinal}")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal AuthPrincipal principal,
                                        @PathVariable UUID reviewId, @PathVariable int ordinal) {
        return service.image(principal.orgId(), reviewId, ordinal)
                .map(loaded -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                loaded.meta().contentType() == null ? "image/jpeg" : loaded.meta().contentType()))
                        .header("Cache-Control", "private, max-age=300")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(loaded.bytes()))
                .orElse(ResponseEntity.notFound().build());
    }
}
