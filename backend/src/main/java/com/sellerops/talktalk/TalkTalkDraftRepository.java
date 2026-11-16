package com.sellerops.talktalk;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TalkTalkDraftRepository extends JpaRepository<TalkTalkDraft, UUID> {

    Optional<TalkTalkDraft> findFirstByConversationIdOrderByVersionDesc(UUID conversationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkDraft d set d.textContent = null, d.purgedAt = :now, d.updatedAt = :now
            where d.createdAt < :before and d.purgedAt is null
            """)
    int purgeBefore(@Param("before") Instant before, @Param("now") Instant now);
}
