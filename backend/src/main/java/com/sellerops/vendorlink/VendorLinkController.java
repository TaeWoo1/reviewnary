package com.sellerops.vendorlink;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public record StartRequest(List<UUID> storeIds) {}

    /** {@code organisation}: the name of the SellerOps organisation the stores are received into. */
    public record ReviewsOverview(VendorReviewPullService.State state, String accountEmail, String organisation,
                                  List<VendorReviewPullService.StoreView> stores) {}

    private final VendorLinkService service;
    private final VendorReviewPullService reviewPull;
    private final VendorReplyTaskService replyTasks;
    private final OrganizationRepository organizations;

    public VendorLinkController(VendorLinkService service, VendorReviewPullService reviewPull,
                                VendorReplyTaskService replyTasks, OrganizationRepository organizations) {
        this.service = service;
        this.reviewPull = reviewPull;
        this.replyTasks = replyTasks;
        this.organizations = organizations;
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

    /** The 「담당자가 올리는 네이버 리뷰」 section: whose server account, into which organisation, each store (§3.2). */
    @GetMapping("/reviews/overview")
    public ReviewsOverview reviewsOverview(@AuthenticationPrincipal AuthPrincipal principal) {
        VendorReviewPullService.Overview overview = reviewPull.overview(principal.orgId());
        String organisation = organizations.findById(principal.orgId()).map(Organization::getName).orElse(null);
        return new ReviewsOverview(overview.state(), overview.accountEmail(), organisation, overview.stores());
    }

    /**
     * 「리뷰 받기 시작」: binds exactly the stores the screen showed ({@code storeIds}) to this organisation and pulls.
     * Nothing else binds a store — a store the server adds later waits to be shown and started the same way.
     */
    @PostMapping("/reviews/start")
    public VendorReviewPullService.PullResult startReviews(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @RequestBody StartRequest request) {
        return reviewPull.start(principal.orgId(), request.storeIds() == null ? List.of() : request.storeIds());
    }

    /** 「지금 받기」: new reviews from this organisation's started stores that are not stopped. Binds nothing. */
    @PostMapping("/reviews/pull")
    public VendorReviewPullService.PullResult pullReviews(@AuthenticationPrincipal AuthPrincipal principal) {
        return reviewPull.pullFor(principal.orgId());
    }

    /** 「받기 멈추기」 · 「다시 받기」 for one store of this organisation; 404 when it is not theirs. */
    @PostMapping("/reviews/stores/{storeId}/{action:pause|resume}")
    public ResponseEntity<Void> pauseOrResume(@AuthenticationPrincipal AuthPrincipal principal,
                                              @PathVariable UUID storeId, @PathVariable String action) {
        return reviewPull.setPaused(principal.orgId(), storeId, "pause".equals(action))
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** 「휴대폰으로 승인 요청」: sends this review's current draft for the seller's approval on the phone (§4). */
    @PostMapping("/reviews/{reviewId}/approval-request")
    public VendorReplyTaskService.TaskView requestApproval(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @PathVariable UUID reviewId) {
        return replyTasks.requestApproval(principal.orgId(), reviewId);
    }

    /** 「승인하고 담당자에게 맡기기」: sends the current draft and approves it here, for a delegate to post (§4, D9). */
    @PostMapping("/reviews/{reviewId}/hand-over")
    public VendorReplyTaskService.TaskView handOver(@AuthenticationPrincipal AuthPrincipal principal,
                                                    @PathVariable UUID reviewId) {
        return replyTasks.handOver(principal.orgId(), reviewId);
    }

    /** 「요청 취소」: cancels the review's delegation task, or asks the delegate to stop once claimed (§4.2, D10). */
    @PostMapping("/reviews/{reviewId}/cancel")
    public VendorReplyTaskService.TaskView cancel(@AuthenticationPrincipal AuthPrincipal principal,
                                                  @PathVariable UUID reviewId) {
        return replyTasks.cancel(principal.orgId(), reviewId);
    }

    /** The latest delegation task this PC knows for the review; 204 when there is none. */
    @GetMapping("/reviews/{reviewId}/task")
    public ResponseEntity<VendorReplyTaskService.TaskView> task(@AuthenticationPrincipal AuthPrincipal principal,
                                                                @PathVariable UUID reviewId) {
        return replyTasks.taskFor(principal.orgId(), reviewId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
