package com.sellerops.replyhold;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ReplyDraftHoldRepository extends JpaRepository<ReplyDraftHold, UUID> {

    Optional<ReplyDraftHold> findByOrgIdAndLaneAndSubjectId(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId);

    List<ReplyDraftHold> findAllByOrgIdOrderByCreatedAtAsc(UUID orgId);

    @Transactional
    void deleteByOrgIdAndLaneAndSubjectId(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId);
}
