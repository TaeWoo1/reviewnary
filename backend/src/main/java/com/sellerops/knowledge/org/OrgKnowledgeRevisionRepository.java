package com.sellerops.knowledge.org;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert and read only — nothing updates or deletes a past revision (V164). */
public interface OrgKnowledgeRevisionRepository extends JpaRepository<OrgKnowledgeRevision, UUID> {

    List<OrgKnowledgeRevision> findAllByOrgIdAndSourceIdOrderByVersionAsc(UUID orgId, UUID sourceId);
}
