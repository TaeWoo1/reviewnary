package com.sellerops.operationspolicy;

import com.sellerops.common.BaseEntity;
import com.sellerops.operationscase.RecommendedActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>«앞으로 같은 문제도 이렇게 처리»</b> — one standing rule the seller declared for one repeated problem (V129).
 *
 * <p><b>It is a rule, and that is what makes it different from everything beside it.</b> {@code seller_guidance}
 * is context a later judgement may be SHOWN; {@code review_triage_corrections} is the seller's word about one
 * review; {@code answer_memory} is what the company once said. None of them changes what a judgement concludes,
 * and none of them is read here: a policy row exists only because a person chose this action for this problem on a
 * screen that said so, and the write path has no argument that could carry a correction.
 *
 * <p><b>The key is the issue memory's own.</b> {@link #signatureKey} is {@code IssueSignature.signatureKey()} —
 * {@code aspect:problem} over the closed {@code IssueVocabulary}, the same string {@code review_issues} is indexed
 * by. So «같은 문제» means the same thing on this screen as on the repeated-problem screen, and matching a case to a
 * policy is an indexed lookup over rows the extraction after every ingest already wrote.
 *
 * <p><b>It sets one field and the fence says which.</b> {@code proactive_case.recommended_action_type} — the
 * handling of work that still comes to the seller. See {@link OperationsPolicyFence} for the five things it may
 * not reach; the first of them is why {@link #action} can only be a HUMAN-authority value.
 */
@Getter
@Setter
@Entity
@Table(name = "seller_operations_policy")
public class SellerOperationsPolicy extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Null exactly when {@link #scope} is ORG — the check constraint makes the pair inseparable. */
    @Column(name = "product_id")
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private OperationsPolicyScope scope;

    @Column(name = "aspect", nullable = false, length = 40)
    private String aspect;

    @Column(name = "problem", nullable = false, length = 40)
    private String problem;

    /** {@code aspect:problem}. Derived at write time; never supplied by a caller. */
    @Column(name = "signature_key", nullable = false, length = 64)
    private String signatureKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private RecommendedActionType action;

    /**
     * The seller's own sentence about why. DATA everywhere downstream: shown beside the recommendation, never
     * concatenated into a prompt's rule section, never parsed for facts, never matched on.
     */
    @Column(name = "note", columnDefinition = "text")
    private String note;

    /**
     * Which revision this is. Bumped only when the action or the note actually changes, exactly like
     * {@code org_knowledge_sources.version}: a case decided last month must be readable against the revision it
     * was decided under, and a version that moved on a no-op edit cannot do that.
     */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /** Retiring, not deleting. A retired policy decides no new case and stays answerable for the ones it did. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "declared_by")
    private UUID declaredBy;

    @Column(name = "declared_at", nullable = false)
    private Instant declaredAt;

    @Column(name = "retired_by")
    private UUID retiredBy;

    @Column(name = "retired_at")
    private Instant retiredAt;

    /** True when this policy may decide a new case — the one question every read path here asks. */
    public boolean stands() {
        return active;
    }
}
