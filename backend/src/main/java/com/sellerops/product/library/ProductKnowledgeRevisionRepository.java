package com.sellerops.product.library;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert and read only — nothing updates or deletes a past revision (V190). */
public interface ProductKnowledgeRevisionRepository extends JpaRepository<ProductKnowledgeRevision, UUID> {

    List<ProductKnowledgeRevision> findAllByOrgIdAndSourceIdOrderByVersionAsc(UUID orgId, UUID sourceId);
}
