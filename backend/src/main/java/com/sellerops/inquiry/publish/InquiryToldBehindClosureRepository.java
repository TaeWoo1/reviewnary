package com.sellerops.inquiry.publish;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryToldBehindClosureRepository extends JpaRepository<InquiryToldBehindClosure, UUID> {
    Optional<InquiryToldBehindClosure> findByWorkItemIdAndCheckId(UUID workItemId, UUID checkId);

    List<InquiryToldBehindClosure> findByOrgIdAndCheckIdIn(UUID orgId, Collection<UUID> checkIds);
}
