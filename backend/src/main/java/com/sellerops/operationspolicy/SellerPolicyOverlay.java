package com.sellerops.operationspolicy;

import com.sellerops.operationscase.RecommendedActionType;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The seller's own standing rule for this case, or nothing.</b>
 *
 * <p>The read side of Seller-declared Operations Policy v1, and the whole of what the case runtime consults. It
 * answers one question — «has this seller already decided how a problem like this one is handled?» — from one
 * indexed lookup over the issue memory's own keys plus one batched read of their rules.
 *
 * <p><b>It is a lookup, not a judgement.</b> Nothing here reads a review's words, a rating, a customer sentence,
 * a correction, a guidance or a memory. It reads which closed-vocabulary problems the extraction already recorded
 * this review as evidence for, and whether the seller declared a rule for one of them. No model, no marketplace,
 * no retrieval.
 *
 * <p><b>PRODUCT beats ORG, and nothing else breaks a tie.</b> A rule the seller wrote for this product is more
 * specific than one they wrote for the company, so it wins — the same narrowing {@code KnowledgeSpineScope} and
 * {@code RepeatedIssueWorkspaceService} already use («the product's shelf before a company-wide rule»). Among
 * rules of the same scope the match is unique by construction: V129's two partial unique indexes allow exactly
 * one live rule per (scope, key). Where a review evidences several problems and the seller has a rule for more
 * than one, the <b>severest problem's</b> rule applies, then the most recently declared — severity is a fixed
 * property of the problem vocabulary and not a judgement about this review, so that ordering is stable and
 * explainable to the seller.
 *
 * <p><b>Reviews only, today.</b> The key is {@code aspect:problem} from the review issue memory, and an inquiry
 * has no such key — nothing extracts a closed problem signature from a customer question. So {@link #forReview}
 * is the only entry point and there is deliberately no inquiry one: a policy that matched an inquiry on something
 * other than this vocabulary would be a second definition of «같은 문제».
 */
@Component
public class SellerPolicyOverlay {

    /**
     * What the seller's rule says about one case, and which revision said it.
     *
     * @param action  the handling to recommend — always a HUMAN-authority value ({@link OperationsPolicyFence})
     * @param note    the seller's own sentence, or null. DATA: displayed, never prompted with, never parsed
     * @param scope   whether the rule was written for this product or for the company
     * @param problem the issue key the rule matched, e.g. {@code 배송:지연} — what to tell the seller it matched on
     */
    public record Applied(UUID policyId, int version, RecommendedActionType action, String note,
                          OperationsPolicyScope scope, String problem) {
    }

    private final SellerOperationsPolicyRepository policies;
    private final ReviewIssueEvidenceRepository evidence;

    public SellerPolicyOverlay(SellerOperationsPolicyRepository policies, ReviewIssueEvidenceRepository evidence) {
        this.policies = policies;
        this.evidence = evidence;
    }

    /**
     * The rule that applies to this review, if the seller declared one.
     *
     * <p>Empty is the common and honest answer: no evidenced problem, no rule for the problems it has, or a rule
     * written for a different product. A review whose issues the seller dismissed yields nothing either — that is
     * decided one level down, in {@code signatureKeysOfReview}.
     *
     * @param productId the review's product, or null. A null product can only match an ORG rule — it names no
     *                  product a product rule could be about, the same fence {@code KnowledgeSpineScope} applies
     */
    @Transactional(readOnly = true)
    public Optional<Applied> forReview(UUID orgId, UUID reviewId, UUID productId) {
        if (orgId == null || reviewId == null) {
            return Optional.empty();
        }
        List<String> keys = evidence.signatureKeysOfReview(orgId, reviewId);
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        return policies.findByOrgIdAndSignatureKeyInAndActiveTrue(orgId, keys).stream()
                .filter(policy -> orgId.equals(policy.getOrgId()))
                .filter(policy -> applies(policy, productId))
                .max(Comparator
                        // PRODUCT outranks ORG: the narrower rule is the one the seller wrote about this thing.
                        .comparingInt((SellerOperationsPolicy p) ->
                                p.getScope() == OperationsPolicyScope.PRODUCT ? 1 : 0)
                        // Then the severest problem — a fixed property of the vocabulary, not of this review.
                        .thenComparingInt(SellerPolicyOverlay::severityRank)
                        // Then the rule the seller wrote most recently.
                        .thenComparing(SellerOperationsPolicy::getDeclaredAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(SellerPolicyOverlay::applied);
    }

    /**
     * Whether a rule's scope covers this case.
     *
     * <p>A PRODUCT rule matches only its own product, and a review with no product matches no PRODUCT rule at
     * all. This is the read-side half of the no-silent-widening rule: the write side refuses to store a PRODUCT
     * policy it cannot bind, and this refuses to stretch one that is bound to something else.
     */
    private static boolean applies(SellerOperationsPolicy policy, UUID productId) {
        if (policy.getScope() == OperationsPolicyScope.ORG) {
            return true;
        }
        return productId != null && productId.equals(policy.getProductId());
    }

    /**
     * Severest first, for a comparator that takes the maximum.
     *
     * <p>{@code IssueSeverity.rank()} is the product's one severity order (HIGH = 0, severest first) and it is
     * negated rather than restated: a second ordering of the same three values is how the policy screen and the
     * issue screen would come to disagree about which problem is worse.
     */
    private static int severityRank(SellerOperationsPolicy policy) {
        try {
            return -com.sellerops.reviewissue.IssueVocabulary.severityOf(policy.getProblem()).rank();
        } catch (IllegalArgumentException unknownProblem) {
            // A vocabulary edit removed the problem this rule was written for. The rule still stands — the seller
            // declared it — but it cannot claim a severity the vocabulary no longer defines, so it sorts below
            // every live problem (LOW negates to -2) and any rule with one outranks it.
            return -3;
        }
    }

    private static Applied applied(SellerOperationsPolicy policy) {
        return new Applied(policy.getId(), policy.getVersion(), policy.getAction(), policy.getNote(),
                policy.getScope(), policy.getSignatureKey());
    }
}
