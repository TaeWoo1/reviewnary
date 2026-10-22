package com.sellerops.vendorlink;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorReplyTaskRepository extends JpaRepository<VendorReplyTask, UUID> {

    Optional<VendorReplyTask> findByVendorTaskId(UUID vendorTaskId);

    Optional<VendorReplyTask> findFirstByOrgIdAndReviewIdOrderByCreatedAtDesc(UUID orgId, UUID reviewId);

    /** A store's tasks in one state — the sent-back ones this PC checks again. */
    List<VendorReplyTask> findByVendorStoreIdAndState(UUID vendorStoreId, String state);

    boolean existsByOrgIdAndReviewIdAndStateNotIn(UUID orgId, UUID reviewId, Collection<String> states);
}
