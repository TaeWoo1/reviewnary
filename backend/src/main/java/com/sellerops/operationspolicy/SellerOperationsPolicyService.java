package com.sellerops.operationspolicy;

import com.sellerops.common.ApiException;
import com.sellerops.operationscase.RecommendedActionType;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.ProductRepository;
import com.sellerops.reviewissue.IssueSignature;
import com.sellerops.reviewissue.IssueVocabulary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The one writer of {@link SellerOperationsPolicy}</b> — called only from an explicit seller press on
 * «앞으로 같은 문제도 이렇게 처리», and from nothing else.
 *
 * <p><b>No promotion, by construction.</b> There is no argument on any method here that could carry a correction,
 * a guidance row or a memory, and no collaborator that could read one. A policy is a person choosing an action for
 * a problem, and the absence of a path from the other three stores is the property
 * {@code SellerOperationsPolicyFenceTest} asserts on the source tree — the way {@code AnswerMemoryWriteFenceTest}
 * asserts its own.
 *
 * <p><b>Write-time validation, refusal with a named reason, never a rewrite</b> — the governance shape
 * {@code AnswerStyleSafetyFloor} established and for its reasons: a setting that appears saved but is quietly
 * narrowed is worse than one that was refused, because the seller goes on believing SellerOps follows a rule it
 * does not follow. Four things are checked and each one answers in the seller's language:
 *
 * <ol>
 *   <li><b>The key is the vocabulary's.</b> An {@code aspect} or {@code problem} outside {@code IssueVocabulary}
 *   is refused rather than stored — a rule keyed on a problem the extractor cannot produce would match nothing
 *   forever and read as a rule that is simply being ignored.</li>
 *   <li><b>PRODUCT scope must bind, or it is refused.</b> This is the whole no-silent-widening rule.
 *   {@code seller_guidance} derives its scope from whether the case's product happened to have an operator name,
 *   so a seller who chose 「이 상품만」 on an unnamed product silently got a company-wide row. Here the seller
 *   states the scope, and a PRODUCT policy whose product is missing, another org's, or unnamed is a 400 — never
 *   an ORG row.</li>
 *   <li><b>The action must still need a person.</b> {@link OperationsPolicyFence#refuse} — the two AUTO values of
 *   {@link RecommendedActionType} are refused, so a standing rule can never stop future work from being shown.</li>
 *   <li><b>The note is bounded and it is data.</b> Capped here; shown beside the recommendation and never put in
 *   a prompt's rule section.</li>
 * </ol>
 *
 * <p><b>Re-declaring is a revision, not a second rule.</b> V129 allows one live policy per (scope, key), so
 * {@link #declare} finds the standing row and revises it — bumping {@code version} only when the action or the
 * note actually changed, and appending a {@code CHANGED} row when it did. An unchanged re-declaration writes
 * nothing: a version that moved on a no-op would break the thing the version exists for, which is reading a case
 * back against the revision that decided it.
 */
@Service
public class SellerOperationsPolicyService {

    /** Long enough for the seller to say why, short enough that it cannot become a document. */
    static final int MAX_NOTE_CHARS = 1000;

    private final SellerOperationsPolicyRepository policies;
    private final SellerOperationsPolicyAuditRepository audits;
    private final ProductRepository products;

    public SellerOperationsPolicyService(SellerOperationsPolicyRepository policies,
                                         SellerOperationsPolicyAuditRepository audits,
                                         ProductRepository products) {
        this.policies = policies;
        this.audits = audits;
        this.products = products;
    }

    /** What the seller chose. {@code productId} is required for PRODUCT scope and refused for ORG. */
    public record Declaration(UUID orgId, OperationsPolicyScope scope, UUID productId, String aspect, String problem,
                              RecommendedActionType action, String note, UUID actorId) {
    }

    /**
     * Declare the rule, or revise the one that stands.
     *
     * @return the stored policy, and whether this call changed it — the caller re-decides open cases only when it did
     */
    @Transactional
    public Declared declare(Declaration request) {
        OperationsPolicyScope scope = require(request.scope());
        String aspect = requireVocabulary(request.aspect(), IssueVocabulary.aspects(), "문제 영역");
        String problem = requireVocabulary(request.problem(), IssueVocabulary.problems(), "문제 종류");
        RecommendedActionType action = requireAllowed(request.action());
        String note = requireNote(request.note());
        UUID productId = requireScopeBinding(request.orgId(), scope, request.productId());
        String key = IssueSignature.of(aspect, problem).signatureKey();

        Optional<SellerOperationsPolicy> standing = scope == OperationsPolicyScope.ORG
                ? policies.findByOrgIdAndSignatureKeyAndProductIdIsNullAndActiveTrue(request.orgId(), key)
                : policies.findByOrgIdAndSignatureKeyAndProductIdAndActiveTrue(request.orgId(), key, productId);

        if (standing.isPresent()) {
            SellerOperationsPolicy row = standing.get();
            RecommendedActionType from = row.getAction();
            boolean changed = from != action || !java.util.Objects.equals(row.getNote(), note);
            if (!changed) {
                // The seller pressed save on the rule they already have. Nothing moved, so nothing is written and
                // no case is re-decided: a version bump here would make every case's recorded revision a lie.
                return new Declared(row, false);
            }
            row.setAction(action);
            row.setNote(note);
            row.setVersion(row.getVersion() + 1);
            row.setDeclaredBy(request.actorId());
            row.setDeclaredAt(Instant.now());
            SellerOperationsPolicy saved = policies.save(row);
            audit(saved, SellerOperationsPolicyAudit.Kind.CHANGED, from, action, request.actorId());
            return new Declared(saved, true);
        }

        SellerOperationsPolicy row = new SellerOperationsPolicy();
        row.setOrgId(request.orgId());
        row.setScope(scope);
        row.setProductId(productId);
        row.setAspect(aspect);
        row.setProblem(problem);
        row.setSignatureKey(key);
        row.setAction(action);
        row.setNote(note);
        row.setVersion(1);
        row.setActive(true);
        row.setDeclaredBy(request.actorId());
        row.setDeclaredAt(Instant.now());
        SellerOperationsPolicy saved = policies.save(row);
        audit(saved, SellerOperationsPolicyAudit.Kind.DECLARED, null, action, request.actorId());
        return new Declared(saved, true);
    }

    /**
     * The seller takes the rule back.
     *
     * <p>Retiring, not deleting — {@code org_knowledge_sources}' lifecycle rather than {@code seller_guidance}'s
     * absence of one. The row stays, so a case decided under it is still readable; it decides no new case, and the
     * partial unique index frees the (scope, key) for a different rule.
     *
     * <p>Idempotent: retiring an already-retired policy writes nothing and appends nothing. A second RETIRED row
     * would say the seller withdrew a rule twice.
     */
    @Transactional
    public Declared retire(UUID orgId, UUID policyId, UUID actorId) {
        SellerOperationsPolicy row = policies.findByIdAndOrgId(policyId, orgId)
                .orElseThrow(() -> ApiException.notFound("해당 처리 기준을 찾을 수 없습니다."));
        if (!row.isActive()) {
            return new Declared(row, false);
        }
        row.setActive(false);
        row.setRetiredBy(actorId);
        row.setRetiredAt(Instant.now());
        SellerOperationsPolicy saved = policies.save(row);
        audit(saved, SellerOperationsPolicyAudit.Kind.RETIRED, saved.getAction(), null, actorId);
        return new Declared(saved, true);
    }

    @Transactional(readOnly = true)
    public List<SellerOperationsPolicy> standing(UUID orgId) {
        return policies.findByOrgIdAndActiveTrueOrderByDeclaredAtDesc(orgId);
    }

    /** Every revision's row, retired ones included — the screen that shows what the rule used to be. */
    @Transactional(readOnly = true)
    public List<SellerOperationsPolicy> all(UUID orgId) {
        return policies.findByOrgIdOrderByDeclaredAtDesc(orgId);
    }

    @Transactional(readOnly = true)
    public List<SellerOperationsPolicyAudit> history(UUID orgId, UUID policyId) {
        policies.findByIdAndOrgId(policyId, orgId)
                .orElseThrow(() -> ApiException.notFound("해당 처리 기준을 찾을 수 없습니다."));
        return audits.findByPolicyIdOrderByDecidedAtAsc(policyId);
    }

    /** The stored rule, and whether this call moved it. {@code false} means no open case needs re-deciding. */
    public record Declared(SellerOperationsPolicy policy, boolean changed) {
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────────────────────

    private static OperationsPolicyScope require(OperationsPolicyScope scope) {
        if (scope == null) {
            throw ApiException.badRequest("이 기준을 회사 전체에 적용할지, 이 상품에만 적용할지 선택해 주세요.");
        }
        return scope;
    }

    private static String requireVocabulary(String value, List<String> allowed, String fieldKo) {
        String wanted = value == null ? "" : value.strip();
        if (wanted.isEmpty() || !allowed.contains(wanted)) {
            throw ApiException.badRequest("기록된 " + fieldKo + " 중에서 선택해 주세요.");
        }
        return wanted;
    }

    private static RecommendedActionType requireAllowed(RecommendedActionType action) {
        Optional<OperationsPolicyFence.Protected> refused = OperationsPolicyFence.refuse(action);
        if (refused.isPresent()) {
            throw ApiException.badRequest(refused.get().reasonKo() + ".");
        }
        return action;
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String text = note.strip();
        if (text.length() > MAX_NOTE_CHARS) {
            throw ApiException.badRequest("설명이 너무 깁니다 (최대 " + MAX_NOTE_CHARS + "자).");
        }
        return text;
    }

    /**
     * The no-silent-widening check.
     *
     * <p>A PRODUCT policy must name a product of this org that an operator can actually see on a screen — the same
     * {@code OperatorProductName} test the inquiry lane uses before it scopes anything to a product. If it cannot,
     * this is a 400 that says so. The alternative, which {@code seller_guidance} takes, is to store an ORG row:
     * that is a rule the seller never declared, applied to every product they sell, with nothing in the response
     * to tell them.
     */
    private UUID requireScopeBinding(UUID orgId, OperationsPolicyScope scope, UUID productId) {
        if (scope == OperationsPolicyScope.ORG) {
            if (productId != null) {
                throw ApiException.badRequest("회사 전체 기준에는 상품을 지정할 수 없습니다.");
            }
            return null;
        }
        if (productId == null) {
            throw ApiException.badRequest("이 상품에만 적용하려면 상품을 지정해 주세요.");
        }
        return products.findById(productId)
                .filter(product -> orgId.equals(product.getOrgId()))
                .filter(product -> OperatorProductName.displayNameOrNull(product) != null)
                .map(product -> productId)
                .orElseThrow(() -> ApiException.badRequest(
                        "이 상품에는 기준을 저장할 수 없습니다. 상품 이름이 확인되는 상품을 선택해 주세요."));
    }

    private void audit(SellerOperationsPolicy policy, SellerOperationsPolicyAudit.Kind kind,
                       RecommendedActionType from, RecommendedActionType to, UUID actorId) {
        SellerOperationsPolicyAudit row = new SellerOperationsPolicyAudit();
        row.setOrgId(policy.getOrgId());
        row.setPolicyId(policy.getId());
        row.setKind(kind);
        row.setActionFrom(from == null ? null : from.name());
        row.setActionTo(to == null ? null : to.name());
        row.setVersionTo(policy.getVersion());
        row.setActorId(actorId);
        row.setDecidedAt(Instant.now());
        audits.save(row);
    }
}
