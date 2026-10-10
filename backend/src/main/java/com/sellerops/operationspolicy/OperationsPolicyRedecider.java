package com.sellerops.operationspolicy;

import com.sellerops.operationscase.CaseEventActor;
import com.sellerops.operationscase.CaseEventKind;
import com.sellerops.operationscase.OperationsCase;
import com.sellerops.operationscase.OperationsCaseEvent;
import com.sellerops.operationscase.OperationsCaseEventRepository;
import com.sellerops.operationscase.OperationsCaseProcessor;
import com.sellerops.operationscase.OperationsCaseRepository;
import com.sellerops.operationscase.OperationsCaseStatus;
import com.sellerops.operationscase.OperationsSubjectKind;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A rule changed, so the open work decided under the old one is decided again — and nothing else is.</b>
 *
 * <p>Without this, declaring a rule would be a promise about the future that said nothing about the queue the
 * seller is looking at: the cards in front of them would keep the recommendation they were opened with until their
 * subject's own state happened to change, and `OperationsSignal.signature` is derived from the subject's rating,
 * body and product — so for most reviews that is never.
 *
 * <h2>Four fences, and each one is a line this class does not cross</h2>
 *
 * <ul>
 *   <li><b>Matching only.</b> The re-decision touches open cases whose review is evidence for the policy's own
 *   {@code aspect:problem} key, and the policy's scope must cover them. Every other case is left exactly as it
 *   was — a rule about 배송:지연 is not an occasion to re-decide a 표면:오염 card.</li>
 *   <li><b>Open only.</b> {@code PREPARED} cases. A closed case is never reopened and never restamped: what was
 *   decided was decided under the revision the trail records, and a rule written today does not retroactively
 *   change what the company did last month.</li>
 *   <li><b>Deterministic only.</b> {@link OperationsCaseProcessor#applyPolicy} is the same method a run uses, and
 *   the whole of what runs here. <b>No model call</b>, no marketplace call, no retrieval — which is also why a
 *   seller may change a rule as often as they like without it costing anything.</li>
 *   <li><b>Never weaker.</b> {@code applyPolicy} writes one field and the policy's action is HUMAN-authority by
 *   {@link OperationsPolicyFence}, so a re-decision cannot close a card, lower an authority, approve, mint or
 *   send. A case that was waiting for the seller is still waiting for the seller afterwards.</li>
 * </ul>
 *
 * <h2>A retirement is a re-decision too, and it has to be</h2>
 *
 * <p>When the seller takes a rule back, {@code applyPolicy} finds nothing and returns false — so the case keeps
 * the recommendation the retired rule gave it. That would leave the seller looking at their own withdrawn rule's
 * answer with nothing saying so. {@link #clear} is therefore run for the cases a RETIRED policy had decided
 * ({@code decidedBy == SELLER}): the recommendation is removed and the card returns to «판매자 확인 필요», which is
 * what a case with no rule and no investigation honestly is. It is <b>not</b> re-investigated here — spending a
 * model call per card because a seller edited a setting is not a cost they asked for, and the next run will
 * investigate it under the normal budget.
 */
@Component
public class OperationsPolicyRedecider {

    /**
     * How many open cases one policy change may re-decide.
     *
     * <p>Bounded because this runs inside the seller's own request. A key with more open cards than this keeps the
     * rest for the next run, which decides them with the same method and the same result — the bound costs
     * freshness on a long tail, never correctness.
     */
    static final int MAX_PER_CHANGE = 200;

    private final OperationsCaseRepository cases;
    private final OperationsCaseEventRepository events;
    private final OperationsCaseProcessor processor;

    public OperationsPolicyRedecider(OperationsCaseRepository cases, OperationsCaseEventRepository events,
                                     OperationsCaseProcessor processor) {
        this.cases = cases;
        this.events = events;
        this.processor = processor;
    }

    /** How many open cases this change moved, and how many it cleared. */
    public record Report(int redecided, int cleared) {

        static final Report NONE = new Report(0, 0);

        public int touched() {
            return redecided + cleared;
        }
    }

    /**
     * Re-decide the open cases this policy change reaches.
     *
     * @param policy the policy as it now stands — active after a declare/revise, inactive after a retire
     */
    @Transactional
    public Report redecide(SellerOperationsPolicy policy) {
        if (policy == null) {
            return Report.NONE;
        }
        List<OperationsCase> open = policy.getScope() == OperationsPolicyScope.PRODUCT
                ? cases.findOpenReviewCasesForIssueKeyAndProduct(policy.getOrgId(),
                        OperationsCaseStatus.PREPARED, policy.getSignatureKey(), policy.getProductId())
                : cases.findOpenReviewCasesForIssueKey(policy.getOrgId(),
                        OperationsCaseStatus.PREPARED, policy.getSignatureKey());
        int redecided = 0;
        int cleared = 0;
        for (OperationsCase c : open.stream().limit(MAX_PER_CHANGE).toList()) {
            if (c.getSubjectKind() != OperationsSubjectKind.REVIEW) {
                continue;
            }
            if (policy.stands()) {
                // The overlay re-reads from scratch, so a case whose product no longer matches, or whose issue the
                // seller has since dismissed, correctly stops being decided by this rule.
                if (processor.applyPolicy(c, c.getLastRunId())) {
                    events.save(OperationsCaseEvent.of(c, c.getLastRunId(), CaseEventActor.SELLER,
                            CaseEventKind.POLICY_REDECIDED, provenance(policy, "APPLIED")));
                    cases.save(c);
                    redecided++;
                } else if (clear(c, policy)) {
                    cleared++;
                }
            } else if (clear(c, policy)) {
                cleared++;
            }
        }
        return new Report(redecided, cleared);
    }

    /**
     * Take a withdrawn rule's answer off a card.
     *
     * <p>Only a card the SELLER layer decided — a case the agent investigated keeps its conclusion, because that
     * conclusion was never this rule's. The recommendation goes and {@code decidedBy} returns to {@code RULE},
     * which is what the case is again: work the rating could not settle and nothing has since decided. The
     * disposition and the authority are untouched, so the card is still the seller's move.
     */
    private boolean clear(OperationsCase c, SellerOperationsPolicy policy) {
        if (c.getDecidedBy() != com.sellerops.operationscase.CaseDecider.SELLER) {
            return false;
        }
        c.setRecommendedActionType(null);
        c.setDecidedBy(com.sellerops.operationscase.CaseDecider.RULE);
        events.save(OperationsCaseEvent.of(c, c.getLastRunId(), CaseEventActor.SELLER,
                CaseEventKind.POLICY_REDECIDED, provenance(policy, "CLEARED")));
        cases.save(c);
        return true;
    }

    /** Metadata only — the policy, its revision and what happened. No seller sentence, no customer sentence. */
    private static String provenance(SellerOperationsPolicy policy, String outcome) {
        return "{\"policyId\":\"" + policy.getId() + "\",\"policyVersion\":" + policy.getVersion()
                + ",\"scope\":\"" + policy.getScope().name() + "\",\"problem\":\"" + policy.getSignatureKey()
                + "\",\"outcome\":\"" + outcome + "\"}";
    }
}
