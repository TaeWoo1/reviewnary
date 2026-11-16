package com.sellerops.knowledge.defaults;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefaultPolicySourceRepository extends JpaRepository<DefaultPolicySource, UUID> {

    List<DefaultPolicySource> findAllByOrgId(UUID orgId);

    Optional<DefaultPolicySource> findByOrgIdAndGroupKey(UUID orgId, String groupKey);
}
