package com.sellerops.vendorlink;

import com.sellerops.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller PC's vendor-server link, for the connection screen — contract §2 · §7. Behind the ordinary seller
 * session; on a seller PC the backend answers loopback only ({@code LoopbackRequestGuard}). No response carries the
 * install token, the device code or a key.
 */
@RestController
@RequestMapping("/api/local/vendor-link")
public class VendorLinkController {

    private final VendorLinkService service;
    private final VendorReviewPullService reviewPull;

    public VendorLinkController(VendorLinkService service, VendorReviewPullService reviewPull) {
        this.service = service;
        this.reviewPull = reviewPull;
    }

    @GetMapping
    public VendorLinkService.Status status() {
        return service.status();
    }

    @PostMapping("/start")
    public VendorLinkService.Status start() {
        return service.start();
    }

    @PostMapping("/poll")
    public VendorLinkService.Status poll() {
        return service.poll();
    }

    @DeleteMapping
    public VendorLinkService.Status unlink() {
        return service.unlink();
    }

    @GetMapping("/public-ip")
    public VendorLinkService.PublicIpView publicIp() {
        return service.publicIp();
    }

    /**
     * Pulls the vendor server's review feed now (§3.2). The seller's own press is what binds this account's
     * not-yet-bound NAVER stores to their organisation; the scheduled pull never binds.
     */
    @PostMapping("/reviews/pull")
    public VendorReviewPullService.PullResult pullReviews(@AuthenticationPrincipal AuthPrincipal principal) {
        return reviewPull.pullFor(principal.orgId());
    }
}
