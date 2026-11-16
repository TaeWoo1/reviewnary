package com.sellerops.knowledge.defaults;

import com.sellerops.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기본 설정 질문지. The org and the actor come only from the authenticated principal. */
@RestController
@RequestMapping("/api/knowledge/defaults")
public class DefaultPolicyController {

    private final DefaultPolicyService service;

    public DefaultPolicyController(DefaultPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public DefaultPolicyViews.Overview overview(@AuthenticationPrincipal AuthPrincipal p) {
        return service.overview(p.orgId());
    }

    @PutMapping("/{key}/draft")
    public DefaultPolicyViews.Overview saveDraft(@AuthenticationPrincipal AuthPrincipal p, @PathVariable String key,
                                                 @RequestBody DefaultPolicyViews.AnswerRequest r) {
        return service.saveDraft(p.orgId(), p.userId(), key, r.number(), r.text());
    }

    @PostMapping("/{key}/confirm")
    public DefaultPolicyViews.Overview confirm(@AuthenticationPrincipal AuthPrincipal p, @PathVariable String key,
                                               @RequestBody DefaultPolicyViews.AnswerRequest r) {
        return service.confirm(p.orgId(), p.userId(), key, r.number(), r.text());
    }

    @PostMapping("/{key}/unconfirm")
    public DefaultPolicyViews.Overview unconfirm(@AuthenticationPrincipal AuthPrincipal p, @PathVariable String key) {
        return service.unconfirm(p.orgId(), p.userId(), key);
    }
}
