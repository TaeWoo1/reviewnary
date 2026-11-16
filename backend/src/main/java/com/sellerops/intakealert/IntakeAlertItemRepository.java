package com.sellerops.intakealert;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntakeAlertItemRepository extends JpaRepository<IntakeAlertItem, UUID> {

    List<IntakeAlertItem> findBySettingIdAndStatus(UUID settingId, IntakeAlertItem.Status status);

    List<IntakeAlertItem> findByDeliveryIdOrderByCreatedAtAsc(UUID deliveryId);

    long countBySettingIdAndStatus(UUID settingId, IntakeAlertItem.Status status);

    /** Claim pending items into a delivery. A row another worker already claimed is skipped, never taken twice. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertItem i set i.status = com.sellerops.intakealert.IntakeAlertItem.Status.INCLUDED,
                   i.deliveryId = :deliveryId, i.updatedAt = :now
            where i.id in :ids and i.status = com.sellerops.intakealert.IntakeAlertItem.Status.PENDING
            """)
    int claim(@Param("ids") Collection<UUID> ids, @Param("deliveryId") UUID deliveryId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertItem i set i.status = com.sellerops.intakealert.IntakeAlertItem.Status.DROPPED_HANDLED,
                   i.updatedAt = :now
            where i.id in :ids and i.status = com.sellerops.intakealert.IntakeAlertItem.Status.PENDING
            """)
    int dropHandled(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /** Reviews of this org that arrived in [from, to] and have no item for this setting yet. */
    @Query("""
            select r.id from Review r
            where r.orgId = :orgId and r.createdAt >= :from and r.createdAt <= :to
              and not exists (select 1 from IntakeAlertItem i where i.settingId = :settingId
                              and i.subjectKind = com.sellerops.intakealert.IntakeAlertItem.Kind.REVIEW
                              and i.subjectId = r.id)
            order by r.createdAt asc
            """)
    List<UUID> missingReviews(@Param("orgId") UUID orgId, @Param("settingId") UUID settingId,
                              @Param("from") Instant from, @Param("to") Instant to, Pageable page);

    @Query("""
            select q.id from Inquiry q
            where q.orgId = :orgId and q.createdAt >= :from and q.createdAt <= :to
              and not exists (select 1 from IntakeAlertItem i where i.settingId = :settingId
                              and i.subjectKind = com.sellerops.intakealert.IntakeAlertItem.Kind.INQUIRY
                              and i.subjectId = q.id)
            order by q.createdAt asc
            """)
    List<UUID> missingInquiries(@Param("orgId") UUID orgId, @Param("settingId") UUID settingId,
                                @Param("from") Instant from, @Param("to") Instant to, Pageable page);

    /** Close every queued item of this recipient (a settings change). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertItem i set i.status = com.sellerops.intakealert.IntakeAlertItem.Status.CANCELLED,
                   i.cancelReason = :reason, i.updatedAt = :now
            where i.settingId = :settingId and i.status = com.sellerops.intakealert.IntakeAlertItem.Status.PENDING
              and (:kind is null or i.subjectKind = :kind)
            """)
    int cancelQueued(@Param("settingId") UUID settingId, @Param("kind") IntakeAlertItem.Kind kind,
                     @Param("reason") IntakeAlertItem.CancelReason reason, @Param("now") Instant now);

    /** Close the items of deliveries that were cancelled before sending. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertItem i set i.status = com.sellerops.intakealert.IntakeAlertItem.Status.CANCELLED,
                   i.cancelReason = :reason, i.updatedAt = :now
            where i.deliveryId in :deliveryIds
            """)
    int cancelInDeliveries(@Param("deliveryIds") Collection<UUID> deliveryIds,
                           @Param("reason") IntakeAlertItem.CancelReason reason, @Param("now") Instant now);

    @Query("""
            select distinct i.deliveryId from IntakeAlertItem i
            where i.settingId = :settingId and i.deliveryId is not null
              and (:kind is null or i.subjectKind = :kind)
            """)
    List<UUID> deliveryIdsOf(@Param("settingId") UUID settingId, @Param("kind") IntakeAlertItem.Kind kind);

    List<IntakeAlertItem> findBySettingIdOrderByCreatedAtDesc(UUID settingId, Pageable page);

    long countBySettingId(UUID settingId);
}
