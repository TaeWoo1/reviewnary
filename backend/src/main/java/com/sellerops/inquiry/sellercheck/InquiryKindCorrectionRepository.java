package com.sellerops.inquiry.sellercheck;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryKindCorrectionRepository extends JpaRepository<InquiryKindCorrection, UUID> {
    Optional<InquiryKindCorrection> findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByCorrectedAtDesc(
            UUID orgId, UUID workItemId);

    List<InquiryKindCorrection> findByOrgIdAndWorkItemIdOrderByCorrectedAtAsc(UUID orgId, UUID workItemId);
}
