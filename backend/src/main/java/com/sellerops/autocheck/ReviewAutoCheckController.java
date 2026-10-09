package com.sellerops.autocheck;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.device.HelperDeviceAuthFilter;
import com.sellerops.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * <b>이미 연결해 둔 계정들에 자동 확인을 켠다 — 이 조직의 것만, 한 번.</b>
     *
     * <p>이 기능이 있기 전에 연결한 계정을 위한 one-time 이동이고, 범위는 호출한 세션의 조직이다. 데이터베이스
     * 전체를 훑는 경로는 이 lane에 없다 — 그래서 fixture·benchmark 조직이 섞여 있는 백엔드에서도 그 조직이
     * 집히지 않고, 「건너뛸 조직 목록」을 제품 코드에 둘 이유도 생기지 않는다.
     *
     * <p>Idempotent: 행이 이미 있는 계정은 대상이 아니다. 판매자가 껐던 계정은 꺼진 채로 남는다.
     */
    @PostMapping("/api/seller-accounts/review-auto-check/backfill")
    public BackfillResult backfill(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        if (request.getAttribute(HelperDeviceAuthFilter.DEVICE_ID_ATTRIBUTE) != null) {
            throw ApiException.forbidden("이 요청은 사용자만 보낼 수 있습니다.");
        }
        return new BackfillResult(service.backfillForOrg(principal.orgId()));
    }

    /** How many accounts were switched on. A count, and nothing about which. */
    public record BackfillResult(int switchedOn) {
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
