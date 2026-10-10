package com.sellerops.operationspolicy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerOperationsPolicyAuditRepository
        extends JpaRepository<SellerOperationsPolicyAudit, UUID> {

    /** One policy's history, oldest first — the trail reads forward where it is the subject. */
    List<SellerOperationsPolicyAudit> findByPolicyIdOrderByDecidedAtAsc(UUID policyId);
}
