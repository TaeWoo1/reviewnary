package com.sellerops.reviewimport.unattended;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.device.HelperDeviceAuthFilter;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.ApiException;
import com.sellerops.reviewimport.ReviewImportLaunch;
import com.sellerops.reviewimport.ReviewImportLaunchService;
import com.sellerops.reviewimport.ReviewImportSegmentPlanner.DateRange;
import com.sellerops.reviewimport.dto.ReviewImportLaunchView;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>The one thing the 72h shadow run's cloud agent may do that an ordinary helper may not: authorize its own
 * next NAVER review export.</b>
 *
 * <p>It exists so that the unattended lane does not need {@code /api/imports/reviews/plans/**} on
 * {@code HelperDeviceAuthFilter.ALLOWED}. That prefix carries range preview, range selection, plan creation,
 * plan extension, plan merge, segment split, segment file import and per-segment launch for <b>any</b> channel
 * the organisation has — opening it would hand every paired helper on the host the whole import surface, when
 * what the run needs is one sentence: «give me the next NAVER review launch for myself».
 *
 * <p><b>The request names nothing.</b> No body, no query, no path variable. Not the organisation (it comes
 * from the validated token), not the device (same), not the channel (fixed below), not the account (resolved
 * here and refused when ambiguous), not the plan, not the segment, not a date, not a file. There is therefore
 * no field through which a caller could reach another organisation, another channel, another data type, or a
 * file import — the absence of the field is the fence, which is the same reason
 * {@code ScheduledAsideJob} holds no URL column.
 *
 * <p><b>It mints; it does not execute.</b> The returned ticket is the existing single-use
 * {@code review_import_launch} — same 16-hex ref, same expiry, same consumption on ingest. Everything after
 * this call is the path the seller's own UI already uses: {@code GET .../launches/{ref}/scope} then
 * {@code POST .../launches/{ref}/ingest}, both already on the allow-list, and the canonical
 * {@code UploadFormat → FileParser → ReviewRowMapper → IngestionService} chain behind them. This class adds no
 * parser, no second ingest path and no marketplace call of any kind.
 *
 * <p><b>Channel is a constant, not a parameter.</b> {@code NAVER} and reviews, because that is the only
 * acquisition this capability was approved for. A Cafe24 or Coupang launch is not reachable from here at all —
 * not refused at run time, but absent from the code.
 */
@RestController
public class UnattendedReviewExportController {

    /** Not configurable. A deployment that wants another channel's unattended export needs another decision. */
    static final String CHANNEL_CODE = "NAVER";

    private final UnattendedReviewExportProperties properties;
    private final ReviewImportLaunchService launchService;
    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;

    public UnattendedReviewExportController(UnattendedReviewExportProperties properties,
                                            ReviewImportLaunchService launchService,
                                            SellerAccountRepository accounts,
                                            ChannelRepository channels) {
        this.properties = properties;
        this.launchService = launchService;
        this.accounts = accounts;
        this.channels = channels;
    }

    @PostMapping("/api/helper-devices/review-export/next-launch")
    public ReviewImportLaunchView nextLaunch(@AuthenticationPrincipal AuthPrincipal principal,
                                             HttpServletRequest request) {
        UUID deviceId = deviceOf(request);
        UUID orgId = principal == null ? null : principal.orgId();
        if (!properties.admits(orgId, deviceId)) {
            // One message for «capability off», «organisation not named» and «device not named». Which of the
            // three it was is a fact about the deployment, and the caller is not who reads deployment facts.
            throw ApiException.forbidden("이 배포에서는 자동 리뷰 가져오기가 허용되지 않았습니다.");
        }
        ReviewImportLaunch ticket = launchService.mintNextForAccount(orgId, naverAccountOf(orgId).getId());
        DateRange required = launchService.requiredDatesOf(ticket);
        return ReviewImportLaunchView.from(ticket,
                required == null ? null : required.start(),
                required == null ? null : required.end());
    }

    /**
     * The organisation's single NAVER API account. <b>Ambiguity is a refusal.</b> Two connected NAVER accounts
     * mean two seller centres and two review histories, and picking one would be this capability guessing which
     * store a 72h run should drive; a file-upload account has no seller centre to drive at all.
     */
    private SellerAccount naverAccountOf(UUID orgId) {
        Channel channel = channels.findByCode(CHANNEL_CODE)
                .orElseThrow(() -> ApiException.badRequest("네이버 채널이 이 배포에 없습니다."));
        List<SellerAccount> candidates = accounts.findAllByOrgIdAndChannelId(orgId, channel.getId()).stream()
                .filter(a -> !a.isFileUpload())
                .toList();
        if (candidates.isEmpty()) {
            throw ApiException.badRequest("연결된 네이버 계정이 없습니다.");
        }
        if (candidates.size() > 1) {
            throw ApiException.conflict("네이버 계정이 둘 이상이라 자동으로 고를 수 없습니다.");
        }
        return candidates.get(0);
    }

    /**
     * Which device is asking. Set by {@link HelperDeviceAuthFilter} from the token it validated — absent means
     * a seller's browser session reached this route, and that is a refusal rather than a convenience: the
     * seller already has the ordinary UI path, and this route exists only for the unattended one.
     */
    private static UUID deviceOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(HelperDeviceAuthFilter.DEVICE_ID_ATTRIBUTE);
        if (!(attribute instanceof UUID deviceId)) {
            throw ApiException.forbidden("이 요청은 연결된 도우미만 보낼 수 있습니다.");
        }
        return deviceId;
    }
}
