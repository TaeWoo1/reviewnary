package com.sellerops.operationspolicy.dto;

/**
 * What saving a rule did, including <b>how much open work it moved</b>.
 *
 * <p>The counts are here because a rule the seller declares is a statement about the cards in front of them as
 * well as about the future, and a screen that said only «저장했습니다» would leave them to discover the
 * re-decision by scrolling. {@code redecided} is how many open cards now carry this rule's handling;
 * {@code cleared} is how many stopped carrying a withdrawn one.
 *
 * <p>{@code changed} is false when the seller pressed save on the rule they already had — nothing was written, no
 * version moved and no case was touched.
 */
public record OperationsPolicySaveResult(OperationsPolicyView policy, boolean changed, int redecided, int cleared) {
}
