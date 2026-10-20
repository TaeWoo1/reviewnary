package com.sellerops.vendorlink;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorReplyTaskRepository extends JpaRepository<VendorReplyTask, UUID> {

    Optional<VendorReplyTask> findByVendorTaskId(UUID vendorTaskId);

    Optional<VendorReplyTask> findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(UUID orgId, UUID reviewId);

    boolean existsByOrgIdAndReviewIdAndStateNotIn(UUID orgId, UUID reviewId, Collection<String> states);
}
