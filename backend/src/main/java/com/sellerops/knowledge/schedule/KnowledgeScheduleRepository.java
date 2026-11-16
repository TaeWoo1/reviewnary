package com.sellerops.knowledge.schedule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeScheduleRepository extends JpaRepository<KnowledgeSchedule, UUID> {

    List<KnowledgeSchedule> findAllByOrgIdOrderByCreatedAtAsc(UUID orgId);

    Optional<KnowledgeSchedule> findByIdAndOrgId(UUID id, UUID orgId);
}
