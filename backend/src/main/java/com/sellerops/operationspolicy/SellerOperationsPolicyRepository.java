package com.sellerops.operationspolicy;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerOperationsPolicyRepository extends JpaRepository<SellerOperationsPolicy, UUID> {

    /** Every rule the seller currently stands behind, newest first — the settings screen's one read. */
    List<SellerOperationsPolicy> findByOrgIdAndActiveTrueOrderByDeclaredAtDesc(UUID orgId);

    /** Including retired, for the screen that shows what the rule used to be. */
    List<SellerOperationsPolicy> findByOrgIdOrderByDeclaredAtDesc(UUID orgId);

    /**
     * The standing rules for a page of problems — one org-scoped batch, never a per-case lookup.
     *
     * <p>Both scopes come back together and the overlay picks; a query that pre-filtered to one scope would have
     * to know the case's product before it knew whether a product rule existed, which is one read too many.
     */
    List<SellerOperationsPolicy> findByOrgIdAndSignatureKeyInAndActiveTrue(UUID orgId,
                                                                           Collection<String> signatureKeys);

    /** The live ORG rule for one key, if the seller declared one. */
    Optional<SellerOperationsPolicy> findByOrgIdAndSignatureKeyAndProductIdIsNullAndActiveTrue(
            UUID orgId, String signatureKey);

    /** The live PRODUCT rule for one key, if the seller declared one. */
    Optional<SellerOperationsPolicy> findByOrgIdAndSignatureKeyAndProductIdAndActiveTrue(
            UUID orgId, String signatureKey, UUID productId);

    Optional<SellerOperationsPolicy> findByIdAndOrgId(UUID id, UUID orgId);
}
