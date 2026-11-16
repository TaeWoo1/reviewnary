package com.sellerops.talktalk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TalkTalkGuidanceRepository extends JpaRepository<TalkTalkGuidance, UUID> {

    Optional<TalkTalkGuidance> findFirstByOrgIdAndStatus(UUID orgId, TalkTalkGuidance.Status status);

    Optional<TalkTalkGuidance> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<TalkTalkGuidance> findFirstByOrgIdOrderByVersionDesc(UUID orgId);

    List<TalkTalkGuidance> findByOrgIdOrderByVersionDesc(UUID orgId);
}
