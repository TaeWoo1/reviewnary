package com.sellerops.operationspolicy;

import com.sellerops.common.BaseEntity;
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
 * Append-only trail of the seller's own policy decisions — one row per thing they did.
 *
 * <p>The third instance of the pattern {@code V99__seller_triage_correction.sql} named
 * ({@code review_reply_approval_audit}, {@code review_triage_correction_audit}), not a fourth pattern and not a
 * generic event platform. A retirement does not delete what a policy once decided, so the trail answers «what was
 * our rule in March» after the rule has changed twice.
 *
 * <p>Append-only is enforced by a database trigger (V129), the same way {@code operations_case_event} is: a
 * property the schema holds rather than one the writer remembers.
 */
@Getter
@Setter
@Entity
@Table(name = "seller_operations_policy_audit")
public class SellerOperationsPolicyAudit extends BaseEntity {

    /** DECLARED (first), CHANGED (a different action or note), RETIRED (the seller took the rule back). */
    public enum Kind { DECLARED, CHANGED, RETIRED }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    /** Null on DECLARED — there was no standing rule to leave. */
    @Column(name = "action_from", length = 32)
    private String actionFrom;

    /** Null exactly on RETIRED. A retirement states no action, and inventing one would put a rule in the trail
     * the seller withdrew. */
    @Column(name = "action_to", length = 32)
    private String actionTo;

    @Column(name = "version_to", nullable = false)
    private int versionTo;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;
}
