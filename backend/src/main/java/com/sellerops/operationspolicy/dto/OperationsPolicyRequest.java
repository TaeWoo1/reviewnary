package com.sellerops.operationspolicy.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * «앞으로 같은 문제도 이렇게 처리» — what the seller chose, as it arrives on the wire.
 *
 * <p><b>Every field is the seller's own statement and none is inferred.</b> {@code scope} is stated rather than
 * derived from whether {@code productId} happened to resolve — that derivation is what made
 * {@code seller_guidance} widen a product rule into a company-wide one without telling anyone, and the service
 * refuses an unbindable PRODUCT request instead of quietly doing the same.
 *
 * <p>There is deliberately <b>no correction id, no guidance id and no case id</b> on this record. A policy is not
 * a promotion of anything: it exists because a person chose an action for a problem, and a field that let a
 * correction travel in here is all it would take for «자동 승격 금지» to stop being true.
 *
 * @param scope     {@code ORG} or {@code PRODUCT}
 * @param productId required for {@code PRODUCT}, refused for {@code ORG}
 * @param aspect    an {@code IssueVocabulary} aspect, e.g. 배송
 * @param problem   an {@code IssueVocabulary} problem, e.g. 지연
 * @param action    a {@code RecommendedActionType}; the two AUTO values are refused by the fence
 * @param note      the seller's own sentence about why. Optional, data, never prompted with
 */
public record OperationsPolicyRequest(String scope, UUID productId, String aspect, String problem, String action,
                                      @Size(max = 1000) String note) {
}
