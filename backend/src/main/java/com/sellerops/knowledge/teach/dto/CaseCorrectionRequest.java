package com.sellerops.knowledge.teach.dto;

import jakarta.validation.constraints.Size;

/**
 * The seller saying a different action is right for this case — and, separately, whether that judgement should
 * become a standing rule.
 *
 * <p><b>Two independent decisions on one form, and they are not the same decision.</b> {@code remember} +
 * {@code scope} are 「다음에도 참고하기」: a {@code seller_guidance} row, context a later draft or investigation may
 * be SHOWN, which changes no conclusion. {@code applyToFuture} + {@code policyScope} are
 * 「앞으로 같은 문제도 이렇게 처리」: a {@code seller_operations_policy} row, a RULE that decides the handling of
 * future cases about the same problem.
 *
 * <p>Four fields rather than two reused ones, deliberately. The two acts have different consequences, different
 * stores, different lifecycles and different fences — a seller who wanted context remembered must not be able to
 * declare a rule by the same tick, and a shared {@code scope} would mean a guidance written for one product and a
 * rule written for the whole company could never be stated in one submission.
 *
 * @param correctedActionType one of the closed recommendation tokens, or null when only a note is being left
 * @param note                the seller's own sentence
 * @param remember            「다음에도 참고하기」 — write a guidance row
 * @param scope               the guidance's scope: {@code ORG} or {@code PRODUCT}
 * @param applyToFuture       「앞으로 같은 문제도 이렇게 처리」 — declare a standing rule. Requires an action
 * @param policyScope         the rule's scope: {@code ORG} or {@code PRODUCT}. Stated, never derived
 */
public record CaseCorrectionRequest(String correctedActionType, @Size(max = 2000) String note, boolean remember,
                                    String scope, boolean applyToFuture, String policyScope) {

    /** The shape every caller before Seller-declared Operations Policy v1 sent. */
    public CaseCorrectionRequest(String correctedActionType, String note, boolean remember, String scope) {
        this(correctedActionType, note, remember, scope, false, null);
    }
}
