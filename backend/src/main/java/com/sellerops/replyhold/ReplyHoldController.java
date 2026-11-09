package com.sellerops.replyhold;

import com.sellerops.auth.AuthPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The drafts held after a recheck, with their reasons, and the seller's 「확인하고 진행」 — org-scoped from the JWT. The
 * reply screens show a hold above the reply it is on (2026-11-01 review: 보류 상태 · 이유 · 다음 행동이 함께 보여야).
 */
@RestController
@RequestMapping("/api/reply-holds")
public class ReplyHoldController {

    /**
     * Which reply, the version the seller read the reasons on, the check they read ({@code checkId} of the hold shown) and
     * what they decided — {@code CONFIRM} when absent.
     */
    public record ConfirmRequest(ReplyDraftHold.Lane lane, UUID subjectId, Integer draftVersion, String checkId,
                                 ReplyHoldService.Decision decision) {
    }

    private final ReplyHoldService holds;

    public ReplyHoldController(ReplyHoldService holds) {
        this.holds = holds;
    }

    @GetMapping
    public List<ReplyHoldService.HoldView> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return holds.list(principal.orgId());
    }

    /** The hold standing on one reply, or an empty body when none does. */
    @GetMapping("/{lane}/{subjectId}")
    public ReplyHoldService.HoldView one(@AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable ReplyDraftHold.Lane lane, @PathVariable UUID subjectId) {
        return holds.holdFor(principal.orgId(), lane, subjectId);
    }

    /**
     * Go on past a hold: 「확인하고 진행」, or 「이번 건만 예외」 for a promise nothing backs. Refused (409) when it must be
     * fixed first, when the decision is not the one this finding takes, or when the check the seller read is no longer
     * the current one. Null body when none stands.
     */
    @PostMapping("/confirm")
    public ReplyHoldService.HoldView confirm(@AuthenticationPrincipal AuthPrincipal principal,
                                             @RequestBody ConfirmRequest request) {
        if (request == null || request.lane() == null || request.subjectId() == null || request.draftVersion() == null
                || request.checkId() == null || request.checkId().isBlank()) {
            throw com.sellerops.common.ApiException.badRequest("확인할 답변을 알 수 없어요.");
        }
        return holds.decide(principal.orgId(), request.lane(), request.subjectId(), request.draftVersion(),
                request.checkId(), request.decision() == null ? ReplyHoldService.Decision.CONFIRM : request.decision(),
                principal.userId());
    }
}
