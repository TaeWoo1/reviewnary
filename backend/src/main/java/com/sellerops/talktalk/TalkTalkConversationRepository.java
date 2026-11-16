package com.sellerops.talktalk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TalkTalkConversationRepository extends JpaRepository<TalkTalkConversation, UUID> {

    Optional<TalkTalkConversation> findByOrgIdAndConnectionIdAndPartnerIdAndUserHash(UUID orgId, UUID connectionId,
                                                                                      String partnerId, String userHash);

    long countByOrgIdAndNeedsHumanTrue(UUID orgId);

    /**
     * The conversation row, locked for the transaction. Lock order everywhere in the lane: the org's connection row
     * first ({@link TalkTalkConnectionRepository#lockByOrgId}), then this.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select c from TalkTalkConversation c where c.id = :id and c.orgId = :orgId")
    Optional<TalkTalkConversation> lockByIdAndOrgId(@org.springframework.data.repository.query.Param("id") UUID id,
                                                     @org.springframework.data.repository.query.Param("orgId") UUID orgId);

    Optional<TalkTalkConversation> findByIdAndOrgId(UUID id, UUID orgId);

    List<TalkTalkConversation> findByOrgIdOrderByLastInboundAtDesc(UUID orgId, Pageable page);
}
