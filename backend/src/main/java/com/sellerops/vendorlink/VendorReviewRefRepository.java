package com.sellerops.vendorlink;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorReviewRefRepository extends JpaRepository<VendorReviewRef, UUID> {

    Optional<VendorReviewRef> findByVendorReviewId(UUID vendorReviewId);

    Optional<VendorReviewRef> findByReviewId(UUID reviewId);
}
