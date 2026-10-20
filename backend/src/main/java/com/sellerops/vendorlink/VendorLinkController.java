package com.sellerops.vendorlink;

import com.sellerops.auth.AuthPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final VendorReplyTaskService replyTasks;

    public VendorLinkController(VendorLinkService service, VendorReviewPullService reviewPull,
                                VendorReplyTaskService replyTasks) {
        this.service = service;
        this.reviewPull = reviewPull;
        this.replyTasks = replyTasks;
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

    /** 「휴대폰으로 승인 요청」: sends this review's current draft for the seller's approval on the phone (§4). */
    @PostMapping("/reviews/{reviewId}/approval-request")
    public VendorReplyTaskService.TaskView requestApproval(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @PathVariable UUID reviewId) {
        return replyTasks.requestApproval(principal.orgId(), reviewId);
    }

    /** The latest delegation task this PC knows for the review; 204 when there is none. */
    @GetMapping("/reviews/{reviewId}/task")
    public ResponseEntity<VendorReplyTaskService.TaskView> task(@AuthenticationPrincipal AuthPrincipal principal,
                                                                @PathVariable UUID reviewId) {
        return replyTasks.taskFor(principal.orgId(), reviewId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
