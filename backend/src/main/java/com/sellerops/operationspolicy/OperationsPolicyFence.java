package com.sellerops.operationspolicy;

import com.sellerops.operationscase.RecommendedActionType;
import com.sellerops.operationscase.RequiredAuthority;
import java.util.Optional;

/**
 * <b>What a seller-declared operations policy is not allowed to reach.</b>
 *
 * <p>This is the first asset in the product where something a seller typed changes what a judgement concludes, so
 * it gets the governance shape {@code AnswerStyleSafetyFloor} already established for the other one: a closed list
 * of protected properties, checked at <b>write time</b> so the seller is told which choice was refused and why,
 * and a <b>refusal rather than a rewrite</b> — nothing here quietly narrows a policy into something acceptable,
 * because a seller left believing SellerOps follows a rule it does not follow is worse than one who was refused.
 *
 * <p><b>Five properties, and the first one is the whole design.</b>
 *
 * <ol>
 *   <li>{@link Protected#HUMAN_APPROVAL} — a policy may only choose a handling that still needs the seller.
 *   {@code NO_ACTION} and {@code MONITOR_REPEAT_ISSUE} are the two {@link RequiredAuthority#AUTO} values of
 *   {@link RecommendedActionType}, and a standing rule that reached them would be a rule that stops future work
 *   from being shown — it removes a look rather than adding one, which is the discipline every other derived
 *   signal in this runtime keeps ({@code OperationsCaseRules}: «can add a look, never remove one»). A seller who
 *   wants a repeated problem to stop surfacing has the issue lifecycle and the opportunity dismissal for that,
 *   both per-object and both reversible.</li>
 *   <li>{@link Protected#NO_EXECUTION} — a policy names a recommendation, never an execution. Approval, the
 *   single-use submission mint, the Action Window and every WRITE path are reached by a seller's own press and
 *   nothing here produces one. Structural rather than checked: this package holds no write collaborator at all.</li>
 *   <li>{@link Protected#NO_MONEY_WITHOUT_A_PERSON} — {@code REFUND_OR_COMPENSATION} and
 *   {@code CANCEL_OR_EXCHANGE} may be a policy's action, because recommending them is not doing them; both carry
 *   HUMAN authority and the case still waits. What is refused is the combination that would make money move on a
 *   schedule, and (1) is what makes that unreachable.</li>
 *   <li>{@link Protected#NO_TRIAGE_OVERRIDE} — the tier a review ranks at, the queue's ordering and counts, and
 *   {@code review.reply_state} are not a policy's to move. Structural: the overlay is read in the case layer and
 *   {@code ReviewTriageRules}, its SQL twin and the reply state have no caller here.</li>
 *   <li>{@link Protected#NO_EVIDENCE_PRIORITY} — a policy may not reorder what grounds an answer.
 *   {@code AnswerStyleSafetyFloor.CURRENT_EVIDENCE_PRIORITY} already refuses a seller instruction that does this
 *   from the style side, and the two must agree: {@code KnowledgeAuthority} ranks are the product owner's and a
 *   policy carries no authority field to change them with.</li>
 * </ol>
 *
 * <p><b>Three of the five are structural and one is checked.</b> That is deliberate and it is the stronger
 * arrangement: a property enforced by the absence of a collaborator cannot be regressed by a new branch, while
 * {@link #refuse} exists for the one property a legitimate field ({@code action}) could otherwise violate.
 * {@code SellerOperationsPolicyFenceTest} asserts the structural ones on the source tree, the way
 * {@code AnswerMemoryWriteFenceTest} asserts its own absence.
 */
public final class OperationsPolicyFence {

    /** What a refused choice was trying to reach. Reported so the seller can see the reason. */
    public enum Protected {

        HUMAN_APPROVAL("앞으로의 건을 사람 확인 없이 끝내도록 정할 수는 없습니다. "
                + "처리 방법은 정할 수 있고, 그 처리를 할지는 그때 판매자님이 정하십니다"),

        NO_EXECUTION("정책은 무엇을 할지 추천할 뿐이고, 채널로 보내는 일은 그때 판매자님이 직접 하십니다"),

        NO_MONEY_WITHOUT_A_PERSON("환불·교환은 추천까지만 자동이고, 실행은 사람 확인을 거칩니다"),

        NO_TRIAGE_OVERRIDE("리뷰가 확인 필요인지 참고인지는 정책으로 바꿀 수 없습니다"),

        NO_EVIDENCE_PRIORITY("어떤 근거를 먼저 믿을지는 정책으로 바꿀 수 없습니다");

        private final String reasonKo;

        Protected(String reasonKo) {
            this.reasonKo = reasonKo;
        }

        /** The seller-facing sentence. Carries no internal token and names no table. */
        public String reasonKo() {
            return reasonKo;
        }
    }

    private OperationsPolicyFence() {
    }

    /**
     * The protected property this action would reach, or empty when the action is one a policy may name.
     *
     * <p>Derived from {@link RecommendedActionType#authority()} rather than from a second list of action names, so
     * a new member of that enum is governed the day it is added: an AUTO action is refused because it is AUTO, not
     * because somebody remembered to write it down here.
     */
    public static Optional<Protected> refuse(RecommendedActionType action) {
        if (action == null) {
            return Optional.of(Protected.HUMAN_APPROVAL);
        }
        return action.authority() == RequiredAuthority.HUMAN
                ? Optional.empty()
                : Optional.of(Protected.HUMAN_APPROVAL);
    }

    /** The actions a seller may choose, for the settings screen. Derived, never a hand-kept list. */
    public static java.util.List<RecommendedActionType> allowedActions() {
        return java.util.Arrays.stream(RecommendedActionType.values())
                .filter(action -> refuse(action).isEmpty())
                .toList();
    }
}
