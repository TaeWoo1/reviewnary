package com.sellerops.inquiry.publish;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryApprovalWithdrawalRepository extends JpaRepository<InquiryApprovalWithdrawal, UUID> {
    List<InquiryApprovalWithdrawal> findAllByOrgIdAndWorkItemIdOrderByWithdrawnAtAsc(UUID orgId, UUID workItemId);

    long countByWorkItemId(UUID workItemId);
}
