package com.sellerops.talktalk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: nothing in the lane updates or deletes a delegation row. */
public interface TalkTalkDelegationRepository extends JpaRepository<TalkTalkDelegation, UUID> {

    Optional<TalkTalkDelegation> findFirstByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<TalkTalkDelegation> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable page);
}
