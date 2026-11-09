package com.sellerops.inquiry.followup;

import com.sellerops.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「기다리는 일」 and the checks whose day has come, for the Home (2026-11-03 sixth review). Read-only: no marketplace
 * READ or WRITE, no draft, no approval — see {@link InquiryFollowUpService}.
 */
@RestController
@RequestMapping("/api/inquiries")
public class InquiryFollowUpController {

    private final InquiryFollowUpService followUps;

    public InquiryFollowUpController(InquiryFollowUpService followUps) {
        this.followUps = followUps;
    }

    @GetMapping("/follow-ups")
    public InquiryFollowUpService.FollowUps followUps(@AuthenticationPrincipal AuthPrincipal principal) {
        return followUps.followUps(principal.orgId());
    }
}
