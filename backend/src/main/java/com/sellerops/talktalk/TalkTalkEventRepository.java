package com.sellerops.talktalk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TalkTalkEventRepository extends JpaRepository<TalkTalkEvent, UUID> {

    @Query("select e.id from TalkTalkEvent e where e.state = com.sellerops.talktalk.TalkTalkEvent.State.ACCEPTED "
            + "order by e.receivedAt asc, e.createdAt asc")
    List<UUID> acceptedIds(Pageable page);

    /** Take one ACCEPTED event for processing. Exactly one caller gets 1. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkEvent e set e.state = com.sellerops.talktalk.TalkTalkEvent.State.PROCESSING, e.updatedAt = :now
            where e.id = :id and e.state = com.sellerops.talktalk.TalkTalkEvent.State.ACCEPTED
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /** An event left PROCESSING by a crash goes back to ACCEPTED — processing is idempotent per event row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkEvent e set e.state = com.sellerops.talktalk.TalkTalkEvent.State.ACCEPTED, e.updatedAt = :now
            where e.state = com.sellerops.talktalk.TalkTalkEvent.State.PROCESSING and e.updatedAt < :staleBefore
            """)
    int releaseStale(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TalkTalkEvent e set e.textContent = null, e.productName = null, e.userSealed = null,
                   e.purgedAt = :now, e.updatedAt = :now
            where e.receivedAt < :before and e.purgedAt is null
            """)
    int purgeBefore(@Param("before") Instant before, @Param("now") Instant now);

    long countByOrgId(UUID orgId);
}
