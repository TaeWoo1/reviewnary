package com.sellerops.knowledge.defaults;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefaultPolicyAnswerRepository extends JpaRepository<DefaultPolicyAnswer, UUID> {

    List<DefaultPolicyAnswer> findAllByOrgId(UUID orgId);

    Optional<DefaultPolicyAnswer> findByOrgIdAndItemKey(UUID orgId, String itemKey);
}
