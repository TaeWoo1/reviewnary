package com.sellerops.operationspolicy;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.operationscase.RecommendedActionType;
import com.sellerops.operationspolicy.dto.OperationsPolicyHistoryView;
import com.sellerops.operationspolicy.dto.OperationsPolicyRequest;
import com.sellerops.operationspolicy.dto.OperationsPolicySaveResult;
import com.sellerops.operationspolicy.dto.OperationsPolicyView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 처리 기준 — the rules this company handles repeated problems by.
 *
 * <p><b>The org comes from the JWT and never from a parameter</b>, so one company's rules are not addressable by
 * another; the same posture every settings route in this product has.
 *
 * <p><b>Declaring is one press and it is explicit.</b> There is no route that turns a correction, a guidance or a
 * memory into a policy, and no request body field that could carry one — «자동 승격 금지» is a property of this
 * surface's shape, not a rule somebody has to remember. {@code POST} with the same key revises the rule that
 * stands, which is what the single live row per (scope, key) makes it.
 *
 * <p><b>Saving re-decides matching open work, and the response says how much.</b> See
 * {@link OperationsPolicyRedecider} for the four fences that bound it — matching, open, deterministic, never
 * weaker.
 */
@RestController
@RequestMapping("/api/operations-policies")
public class SellerOperationsPolicyController {

    private final SellerOperationsPolicyService policies;
    private final OperationsPolicyRedecider redecider;

    public SellerOperationsPolicyController(SellerOperationsPolicyService policies,
                                            OperationsPolicyRedecider redecider) {
        this.policies = policies;
        this.redecider = redecider;
    }

    /** The rules that stand, or every revision's row when {@code includeRetired} is set. */
    @GetMapping
    public List<OperationsPolicyView> list(@AuthenticationPrincipal AuthPrincipal principal,
                                           @RequestParam(defaultValue = "false") boolean includeRetired) {
        return (includeRetired ? policies.all(principal.orgId()) : policies.standing(principal.orgId()))
                .stream().map(OperationsPolicyView::of).toList();
    }

    /**
     * What a seller may choose from — the closed vocabularies, derived rather than hand-kept.
     *
     * <p>The actions come from {@link OperationsPolicyFence#allowedActions()}, so the screen cannot offer an
     * action the service would refuse: a settings form that lists a choice the server rejects is a form that
     * teaches the seller their own product is broken.
     */
    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of(
                "aspects", com.sellerops.reviewissue.IssueVocabulary.aspects(),
                "problems", com.sellerops.reviewissue.IssueVocabulary.problems(),
                "actions", OperationsPolicyFence.allowedActions().stream().map(Enum::name).toList(),
                "scopes", List.of(OperationsPolicyScope.ORG.name(), OperationsPolicyScope.PRODUCT.name()));
    }

    /** Declare the rule, or revise the one that stands, then re-decide the open work it reaches. */
    @PostMapping
    public OperationsPolicySaveResult declare(@AuthenticationPrincipal AuthPrincipal principal,
                                              @Valid @RequestBody OperationsPolicyRequest request) {
        SellerOperationsPolicyService.Declared declared = policies.declare(
                new SellerOperationsPolicyService.Declaration(principal.orgId(),
                        OperationsPolicyScope.parse(request.scope()), request.productId(),
                        request.aspect(), request.problem(), action(request.action()), request.note(),
                        principal.userId()));
        return result(declared);
    }

    /** The seller takes the rule back; the cards it had decided stop carrying its answer. */
    @DeleteMapping("/{policyId}")
    public OperationsPolicySaveResult retire(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID policyId) {
        return result(policies.retire(principal.orgId(), policyId, principal.userId()));
    }

    /** One rule's whole history, oldest first. A retirement does not erase what the rule once said. */
    @GetMapping("/{policyId}/history")
    public List<OperationsPolicyHistoryView> history(@AuthenticationPrincipal AuthPrincipal principal,
                                                     @PathVariable UUID policyId) {
        return policies.history(principal.orgId(), policyId).stream()
                .map(OperationsPolicyHistoryView::of).toList();
    }

    /**
     * Re-decide only when the save actually moved something.
     *
     * <p>An unchanged re-declaration touches no case, which is the honest answer to «나는 아무것도 바꾸지 않았다»
     * and also what keeps a seller pressing save twice from writing two trails of re-decisions.
     */
    private OperationsPolicySaveResult result(SellerOperationsPolicyService.Declared declared) {
        OperationsPolicyRedecider.Report moved = declared.changed()
                ? redecider.redecide(declared.policy())
                : new OperationsPolicyRedecider.Report(0, 0);
        return new OperationsPolicySaveResult(OperationsPolicyView.of(declared.policy()), declared.changed(),
                moved.redecided(), moved.cleared());
    }

    /**
     * Parse the action the seller chose.
     *
     * <p>Parsed here rather than bound by Jackson as an enum so an unknown token is this sentence instead of a
     * deserialization error naming a Java type — the same reason {@code OperatorOutcome.parse} exists. Whether the
     * action is one a policy MAY name is the fence's question and is asked in the service.
     */
    private static RecommendedActionType action(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("이런 문제를 앞으로 어떻게 처리할지 선택해 주세요.");
        }
        try {
            return RecommendedActionType.valueOf(raw.strip());
        } catch (IllegalArgumentException unknown) {
            throw ApiException.badRequest("지원되지 않는 처리 방법입니다.");
        }
    }
}
