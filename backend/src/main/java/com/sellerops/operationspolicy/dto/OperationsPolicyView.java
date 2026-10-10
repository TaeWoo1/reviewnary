package com.sellerops.operationspolicy.dto;

import com.sellerops.operationspolicy.OperationsPolicyScope;
import com.sellerops.operationspolicy.SellerOperationsPolicy;
import java.time.Instant;
import java.util.UUID;

/**
 * One standing rule, as the settings screen reads it.
 *
 * <p>Closed tokens plus the seller's own two strings. {@code problem} is the {@code aspect:problem} key — the same
 * string the repeated-problem screen shows — so a seller reading both does not have to believe that two different
 * labels mean the same thing.
 */
public record OperationsPolicyView(UUID id, String scope, UUID productId, String aspect, String problem,
                                   String problemKey, String action, String note, int version, boolean active,
                                   Instant declaredAt, Instant retiredAt) {

    public static OperationsPolicyView of(SellerOperationsPolicy policy) {
        return new OperationsPolicyView(policy.getId(), policy.getScope().name(), policy.getProductId(),
                policy.getAspect(), policy.getProblem(), policy.getSignatureKey(), policy.getAction().name(),
                policy.getNote(), policy.getVersion(), policy.isActive(), policy.getDeclaredAt(),
                policy.getRetiredAt());
    }

    /** The seller-facing name of the scope, for a screen that has not mapped the token itself. */
    public String scopeKo() {
        return OperationsPolicyScope.valueOf(scope).labelKo();
    }
}
