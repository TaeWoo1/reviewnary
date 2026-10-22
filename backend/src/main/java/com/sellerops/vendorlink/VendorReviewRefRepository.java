package com.sellerops.vendorlink;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VendorReviewRefRepository extends JpaRepository<VendorReviewRef, UUID> {

    Optional<VendorReviewRef> findByVendorReviewId(UUID vendorReviewId);

    Optional<VendorReviewRef> findByReviewId(UUID reviewId);

    long countByVendorStoreId(UUID vendorStoreId);

    /**
     * A store's reviews whose seller decided 'no reply' on this PC and the server has not heard it: the current triage
     * is {@code NO_ACTION} and a person set it ({@code SELLER:} — never an automated judgement, 2026-10-21 owner
     * decision).
     */
    @Query("select r from VendorReviewRef r where r.vendorStoreId = :store and r.noReplyReportedAt is null "
            + "and exists (select 1 from ReviewTriage t where t.reviewId = r.reviewId "
            + "and t.disposition = com.sellerops.attention.triage.TriageDisposition.NO_ACTION "
            + "and t.decidedBy like 'SELLER:%')")
    List<VendorReviewRef> sellerNoReplyUnreported(@Param("store") UUID vendorStoreId);
}
