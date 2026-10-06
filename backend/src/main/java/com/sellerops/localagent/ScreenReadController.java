package com.sellerops.localagent;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.device.HelperDeviceAuthFilter;
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
 * <b>The seller's own door to their local agent: start one screen read, then ask how it went.</b>
 *
 * <p>Session-authenticated, and the organisation comes from the principal — never from the body — so the only
 * accounts reachable are the ones this organisation owns. The body names an account and a kind of data; it
 * cannot name a recipe, a URL, a device or a page count, because none of those is a parameter here.
 *
 * <p>Deliberately NOT under {@code /api/helper-devices/**}: those routes are the helper's own, reachable only
 * with a device token. This is the product's route, and keeping them apart is what stops a seller's session
 * from being able to claim or settle work.
 */
@RestController
public class ScreenReadController {

    private final ScreenReadService service;

    public ScreenReadController(ScreenReadService service) {
        this.service = service;
    }

    /** What the client sends: which kind of data, and its own id for this press. */
    public record StartRequest(String dataType, String requestId) {
    }

    @PostMapping("/api/seller-accounts/{accountId}/screen-reads")
    public ScreenReadView start(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request,
                                @PathVariable UUID accountId, @RequestBody StartRequest body) {
        // The authorisation on this lane IS a person pressing, so the one caller that may not press is the
        // helper itself. The device routes require this attribute; this one refuses it — the mirror image, and
        // the reason a helper cannot give itself work.
        if (request.getAttribute(HelperDeviceAuthFilter.DEVICE_ID_ATTRIBUTE) != null) {
            throw ApiException.forbidden("이 요청은 사용자만 보낼 수 있습니다.");
        }
        return service.start(principal.orgId(), accountId, body.dataType(), body.requestId());
    }

    @GetMapping("/api/seller-accounts/{accountId}/screen-reads/readiness")
    public ScreenReadReadinessView readiness(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID accountId,
                                             @RequestParam("dataType") String dataType) {
        return service.readiness(principal.orgId(), accountId, dataType);
    }

    @GetMapping("/api/screen-reads/{jobId}")
    public ScreenReadView status(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID jobId) {
        return service.status(principal.orgId(), jobId);
    }
}
