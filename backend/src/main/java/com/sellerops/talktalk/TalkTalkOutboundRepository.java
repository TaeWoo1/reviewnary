package com.sellerops.talktalk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TalkTalkOutboundRepository extends JpaRepository<TalkTalkOutbound, UUID> {

    Optional<TalkTalkOutbound> findByOrgIdAndCommandId(UUID orgId, String commandId);

    Optional<TalkTalkOutbound> findByIdAndOrgId(UUID id, UUID orgId);

    /** The org of one outbound, without loading it — so the connection lock is taken before the row is read. */
    @Query("select o.orgId from TalkTalkOutbound o where o.id = :id")
    Optional<UUID> orgIdOf(@Param("id") UUID id);

    List<TalkTalkOutbound> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    @Query("select o.id from TalkTalkOutbound o where o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.QUEUED "
            + "order by o.createdAt asc")
    List<UUID> queuedIds(Pageable page);

    /** Take one QUEUED outbound for sending. Exactly one caller gets 1. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkOutbound o set o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.SENDING,
                   o.claimedAt = :now, o.updatedAt = :now
            where o.id = :id and o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.QUEUED
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /** A send left SENDING by a crash or a stuck request: its result is unknown, and it is never sent again on its own. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkOutbound o set o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.UNKNOWN,
                   o.failureCode = 'INTERRUPTED', o.finishedAt = :now, o.updatedAt = :now
            where o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.SENDING and o.claimedAt < :staleBefore
            """)
    int markStaleUnknown(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    /** Cancel a conversation's not-yet-claimed automatic guidance. SENDING and finished rows are left as they are. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkOutbound o set o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.CANCELLED,
                   o.failureCode = :code, o.finishedAt = :now, o.updatedAt = :now
            where o.conversationId = :conversationId
              and o.kind = com.sellerops.talktalk.TalkTalkOutbound.Kind.AUTO_GUIDANCE
              and o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.QUEUED
            """)
    int cancelQueuedAuto(@Param("conversationId") UUID conversationId, @Param("code") String code,
                         @Param("now") Instant now);

    /** Cancel every not-yet-claimed automatic guidance of an organization (delegation turned off or changed). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkOutbound o set o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.CANCELLED,
                   o.failureCode = :code, o.finishedAt = :now, o.updatedAt = :now
            where o.orgId = :orgId
              and o.kind = com.sellerops.talktalk.TalkTalkOutbound.Kind.AUTO_GUIDANCE
              and o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.QUEUED
            """)
    int cancelQueuedAutoForOrg(@Param("orgId") UUID orgId, @Param("code") String code, @Param("now") Instant now);

    /**
     * Cancel every not-yet-claimed send of an organization, whatever its kind — the connection's generation changed
     * (reconnect, rotation, disconnect), so nothing made under the old one may borrow the new credentials.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkOutbound o set o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.CANCELLED,
                   o.failureCode = :code, o.finishedAt = :now, o.updatedAt = :now
            where o.orgId = :orgId and o.status = com.sellerops.talktalk.TalkTalkOutbound.Status.QUEUED
            """)
    int cancelAllQueuedForOrg(@Param("orgId") UUID orgId, @Param("code") String code, @Param("now") Instant now);

    /** The conversation's latest handover request, of either direction. */
    Optional<TalkTalkOutbound> findFirstByConversationIdAndKindInOrderByCreatedAtDesc(
            UUID conversationId, java.util.Collection<TalkTalkOutbound.Kind> kinds);

    long countByOrgIdAndStatusIn(UUID orgId, java.util.Collection<TalkTalkOutbound.Status> statuses);

    @Query("""
            select o from TalkTalkOutbound o where o.conversationId = :conversationId and o.textContent is not null
              and o.contentFingerprint = :fingerprint and o.echoSeenAt is null
              and o.status in (com.sellerops.talktalk.TalkTalkOutbound.Status.API_ACCEPTED,
                               com.sellerops.talktalk.TalkTalkOutbound.Status.FAKE_RECORDED,
                               com.sellerops.talktalk.TalkTalkOutbound.Status.UNKNOWN,
                               com.sellerops.talktalk.TalkTalkOutbound.Status.SENDING)
            order by o.createdAt desc
            """)
    List<TalkTalkOutbound> echoCandidates(@Param("conversationId") UUID conversationId,
                                          @Param("fingerprint") String fingerprint);

    boolean existsByConversationIdAndKindAndStatusIn(UUID conversationId, TalkTalkOutbound.Kind kind,
                                                     java.util.Collection<TalkTalkOutbound.Status> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkOutbound o set o.textContent = null, o.purgedAt = :now, o.updatedAt = :now
            where o.createdAt < :before and o.purgedAt is null
              and o.status <> com.sellerops.talktalk.TalkTalkOutbound.Status.QUEUED
              and o.status <> com.sellerops.talktalk.TalkTalkOutbound.Status.SENDING
            """)
    int purgeBefore(@Param("before") Instant before, @Param("now") Instant now);
}
