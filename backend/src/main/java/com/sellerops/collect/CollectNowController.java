package com.sellerops.collect;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.device.HelperDeviceAuthFilter;
import com.sellerops.collect.dto.CollectNowReadinessView;
import com.sellerops.collect.dto.CollectNowView;
import com.sellerops.collect.dto.ReviewCatchUpPlanView;
import com.sellerops.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>지금 수집 — the one route the product's button calls, for every channel and every kind of data.</b>
 *
 * <p>The organisation comes from the session principal and never from the body, so the only accounts reachable
 * are the ones this organisation owns. The body names an account and a kind of data; it cannot name a route, a
 * recipe, a connector, a device, a URL or a page count, because none of those is a parameter here. Which way
 * the collection goes is {@link CollectNowRouter}'s answer, not the caller's request.
 */
@RestController
public class CollectNowController {

    private final CollectNowService service;

    public CollectNowController(CollectNowService service) {
        this.service = service;
    }

    /**
     * What the client sends.
     *
     * @param requestId the client's own id for this press, so a double-click converges on one collection
     */
    public record CollectNowRequest(String dataType, String requestId) {
    }

    @PostMapping("/api/seller-accounts/{accountId}/collect-now")
    public CollectNowView collectNow(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request,
                                     @PathVariable UUID accountId, @RequestBody CollectNowRequest body) {
        // The authorisation on this lane IS a person pressing, so the one caller that may not press is the
        // helper itself. The device routes require this attribute; this one refuses it, which is what stops a
        // helper from giving itself work through the product's own door.
        if (request.getAttribute(HelperDeviceAuthFilter.DEVICE_ID_ATTRIBUTE) != null) {
            throw ApiException.forbidden("이 요청은 사용자만 보낼 수 있습니다.");
        }
        return service.collectNow(principal.orgId(), accountId, body.dataType(), body.requestId());
    }

    /**
     * <b>로그인을 기다리던 수집을 이어간다.</b> 없으면 아무 일도 하지 않는다.
     *
     * <p>A POST because it may start work, and {@code null} in the body's place when nothing was waiting — the
     * client renders that as «nothing to continue», never as a failure. No {@code requestId}: this does not
     * start a collection, it continues one that already exists and already has an identity.
     */
    @PostMapping("/api/seller-accounts/{accountId}/collect-now/resume")
    public com.sellerops.localagent.ScreenReadView resume(@AuthenticationPrincipal AuthPrincipal principal,
                                                          HttpServletRequest request,
                                                          @PathVariable UUID accountId,
                                                          @RequestBody CollectNowRequest body) {
        if (request.getAttribute(HelperDeviceAuthFilter.DEVICE_ID_ATTRIBUTE) != null) {
            throw ApiException.forbidden("이 요청은 사용자만 보낼 수 있습니다.");
        }
        return service.resumeAfterSignIn(principal.orgId(), accountId, body.dataType()).orElse(null);
    }

    @GetMapping("/api/seller-accounts/{accountId}/collect-now/readiness")
    public CollectNowReadinessView readiness(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID accountId,
                                             @RequestParam("dataType") String dataType) {
        return service.readiness(principal.orgId(), accountId, dataType);
    }

    /**
     * <b>What a catch-up would read — without reading it.</b>
     *
     * <p>A GET, and the only endpoint on this controller that dispatches nothing: the plan is meant to be
     * looked at before a press, including by a seller who then decides not to press.
     */
    @GetMapping("/api/seller-accounts/{accountId}/collect-now/catch-up-plan")
    public ReviewCatchUpPlanView catchUpPlan(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID accountId,
                                             @RequestParam("dataType") String dataType) {
        return service.catchUpPlan(principal.orgId(), accountId, dataType);
    }
}
