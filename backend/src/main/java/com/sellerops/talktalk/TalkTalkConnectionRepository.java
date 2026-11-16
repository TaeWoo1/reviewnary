package com.sellerops.talktalk;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TalkTalkConnectionRepository extends JpaRepository<TalkTalkConnection, UUID> {

    Optional<TalkTalkConnection> findByOrgId(UUID orgId);

    /**
     * The org's connection row, locked for the rest of the transaction. Every change that can stop an automatic
     * guidance (delegation, guidance confirm/retire, disconnect) and the dispatcher's final recheck take it, so a
     * change either commits before the recheck reads it or waits until the send is already handed to the transport.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TalkTalkConnection c where c.orgId = :orgId")
    Optional<TalkTalkConnection> lockByOrgId(@Param("orgId") UUID orgId);

    Optional<TalkTalkConnection> findByWebhookTokenHash(String webhookTokenHash);

    /**
     * Only the org id of the connection a token names — so the caller's first load of the row is the locked one. Loading
     * the entity first and locking after would hand back the cached, possibly stale instance, and saving it would undo
     * a rotation or reconnect that committed in between (review item 12, intake addendum).
     */
    @Query("select c.orgId from TalkTalkConnection c where c.webhookTokenHash = :hash")
    Optional<UUID> orgIdByTokenHash(@Param("hash") String hash);
}
