package com.sellerops.review.response;

import com.sellerops.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 리뷰 응대 기준 — two routes and no org parameter, like the answer style beside it. */
@RestController
@RequestMapping("/api/review-response-rule")
public class ReviewResponseRuleController {

    private final ReviewResponseRuleService rules;

    public ReviewResponseRuleController(ReviewResponseRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public ReviewResponseRuleService.View get(@AuthenticationPrincipal AuthPrincipal principal) {
        return rules.view(principal.orgId());
    }

    @PutMapping
    public ReviewResponseRuleService.View save(@AuthenticationPrincipal AuthPrincipal principal,
                                               @RequestBody ReviewResponseRuleService.Request request) {
        return rules.save(principal.orgId(), request, principal.userId());
    }
}
