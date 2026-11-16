package com.sellerops.talktalk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TalkTalkMessageRepository extends JpaRepository<TalkTalkMessage, UUID> {

    List<TalkTalkMessage> findByConversationIdOrderByOccurredAtAscCreatedAtAsc(UUID conversationId);

    Optional<TalkTalkMessage> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<TalkTalkMessage> findFirstByConversationIdAndAuthorOrderByOccurredAtDescCreatedAtDesc(
            UUID conversationId, TalkTalkMessage.Author author);

    /** The latest customer message that is not a suspected duplicate — what "newer message" is measured against. */
    Optional<TalkTalkMessage> findFirstByConversationIdAndAuthorAndDuplicateSuspectFalseOrderByOccurredAtDescCreatedAtDesc(
            UUID conversationId, TalkTalkMessage.Author author);

    boolean existsByConversationIdAndAuthorAndBodyHashAndOccurredAtAfterAndIdNot(
            UUID conversationId, TalkTalkMessage.Author author, String bodyHash, Instant after, UUID id);

    boolean existsByConversationIdAndAuthorAndOccurredAtAfter(UUID conversationId, TalkTalkMessage.Author author,
                                                             Instant after);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkMessage m set m.textContent = null, m.purgedAt = :now, m.updatedAt = :now
            where m.occurredAt < :before and m.purgedAt is null
            """)
    int purgeBefore(@Param("before") Instant before, @Param("now") Instant now);
}
