package com.sellerops.knowledge.currency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeSupersessionRepository extends JpaRepository<KnowledgeSupersession, UUID> {

    List<KnowledgeSupersession> findAllByOrgId(UUID orgId);

    Optional<KnowledgeSupersession> findByIdAndOrgId(UUID id, UUID orgId);
}
