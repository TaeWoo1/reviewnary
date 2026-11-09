package com.sellerops.inquiry.draft;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftReviewPendingRepository extends JpaRepository<DraftReviewPending, UUID> {

    Optional<DraftReviewPending> findByOrgIdAndWorkItemId(UUID orgId, UUID workItemId);

    long countByOrgId(UUID orgId);
}
