package com.sellerops.inquiry.sellercheck;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquirySellerCheckRepository extends JpaRepository<InquirySellerCheck, UUID> {

    Optional<InquirySellerCheck> findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByRecordedAtDesc(UUID orgId,
                                                                                                         UUID workItemId);

    List<InquirySellerCheck> findByOrgIdAndWorkItemIdOrderByRecordedAtAsc(UUID orgId, UUID workItemId);

    /** The org's current records with these outcomes — what is still open after a reply (V176 index). */
    List<InquirySellerCheck> findByOrgIdAndSupersededAtIsNullAndOutcomeIn(UUID orgId, java.util.Collection<String> outcomes);

    /** The current records of these work items. */
    List<InquirySellerCheck> findByWorkItemIdInAndSupersededAtIsNull(java.util.Collection<UUID> workItemIds);

    /** The current record of the inquiry's work item — what the evidence collector, which holds the inquiry, reads. */
    @Query("select c from InquirySellerCheck c, com.sellerops.inquiry.workitem.InquiryWorkItem w "
            + "where w.id = c.workItemId and w.inquiryId = :inquiryId and c.orgId = :orgId and c.supersededAt is null "
            + "order by c.recordedAt desc")
    List<InquirySellerCheck> findCurrentByInquiry(@Param("orgId") UUID orgId, @Param("inquiryId") UUID inquiryId);
}
