package com.sellerops.operationspolicy.dto;

import com.sellerops.operationspolicy.SellerOperationsPolicyAudit;
import java.time.Instant;

/**
 * One entry in a rule's trail. Closed tokens only — no actor name, no prose, no customer text.
 *
 * <p>{@code actionFrom} is null on the first declaration (there was no standing rule to leave) and
 * {@code actionTo} is null exactly on a retirement (a withdrawal states no action).
 */
public record OperationsPolicyHistoryView(String kind, String actionFrom, String actionTo, int version,
                                          Instant decidedAt) {

    public static OperationsPolicyHistoryView of(SellerOperationsPolicyAudit row) {
        return new OperationsPolicyHistoryView(row.getKind().name(), row.getActionFrom(), row.getActionTo(),
                row.getVersionTo(), row.getDecidedAt());
    }
}
