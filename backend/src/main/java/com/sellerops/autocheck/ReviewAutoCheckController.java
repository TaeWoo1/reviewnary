package com.sellerops.autocheck;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.device.HelperDeviceAuthFilter;
import com.sellerops.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>The one setting: 「새 리뷰를 자동으로 확인합니다」.</b>
 *
 * <p>A GET and a PUT with a single boolean. No interval, no device, no channel, no scope and no consent token —
 * a client cannot ask for anything but on or off, because there is nothing else to ask for.
 *
 * <p>The organisation comes from the session principal, and a helper's own token is refused here: a device must
 * never be able to switch on the lane that gives it work.
 */
@RestController
public class ReviewAutoCheckController {

    private final ReviewAutoCheckService service;

    public ReviewAutoCheckController(ReviewAutoCheckService service) {
        this.service = service;
    }

    public record AutoCheckRequest(Boolean enabled) {
    }

    @GetMapping("/api/seller-accounts/{accountId}/review-auto-check")
    public ReviewAutoCheckView view(@AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable UUID accountId) {
        return service.view(principal.orgId(), accountId);
    }

    @PutMapping("/api/seller-accounts/{accountId}/review-auto-check")
    public ReviewAutoCheckView set(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request,
                                   @PathVariable UUID accountId, @RequestBody AutoCheckRequest body) {
        if (request.getAttribute(HelperDeviceAuthFilter.DEVICE_ID_ATTRIBUTE) != null) {
            throw ApiException.forbidden("이 요청은 사용자만 보낼 수 있습니다.");
        }
        if (body == null || body.enabled() == null) {
            throw ApiException.badRequest("자동 확인을 켤지 끌지 알 수 없습니다.");
        }
        return service.set(principal.orgId(), accountId, principal.userId(), body.enabled());
    }
}
