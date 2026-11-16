package com.sellerops.intakealert;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntakeAlertDeliveryRepository extends JpaRepository<IntakeAlertDelivery, UUID> {

    List<IntakeAlertDelivery> findBySettingIdOrderByCreatedAtDesc(UUID settingId, Pageable page);

    Optional<IntakeAlertDelivery> findByIdAndOrgIdAndSettingId(UUID id, UUID orgId, UUID settingId);

    List<IntakeAlertDelivery> findByStatusOrderByCreatedAtAsc(IntakeAlertDelivery.Status status, Pageable page);

    /** Take one PREPARED delivery for sending. Exactly one caller gets 1. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertDelivery d set d.status = com.sellerops.intakealert.IntakeAlertDelivery.Status.SENDING,
                   d.claimedAt = :now, d.updatedAt = :now
            where d.id = :id and d.status = com.sellerops.intakealert.IntakeAlertDelivery.Status.PREPARED
            """)
    int claimForSend(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * A delivery left SENDING by a crash or a stuck request: its result is unknown, so it becomes UNKNOWN and is never
     * sent again on its own.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertDelivery d set d.status = com.sellerops.intakealert.IntakeAlertDelivery.Status.UNKNOWN,
                   d.failureCode = 'INTERRUPTED', d.finishedAt = :now, d.updatedAt = :now
            where d.status = com.sellerops.intakealert.IntakeAlertDelivery.Status.SENDING and d.claimedAt < :staleBefore
            """)
    int markStaleUnknown(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    /** Cancel not-yet-sent deliveries among {@code ids}. SENDING and finished ones are left as they are. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertDelivery d set d.status = com.sellerops.intakealert.IntakeAlertDelivery.Status.CANCELLED,
                   d.failureCode = :code, d.finishedAt = :now, d.updatedAt = :now
            where d.id in :ids and d.status = com.sellerops.intakealert.IntakeAlertDelivery.Status.PREPARED
            """)
    int cancelPrepared(@Param("ids") java.util.Collection<UUID> ids, @Param("code") String code,
                       @Param("now") Instant now);

    @Query("""
            select d.id from IntakeAlertDelivery d
            where d.settingId = :settingId and d.status = com.sellerops.intakealert.IntakeAlertDelivery.Status.PREPARED
            """)
    List<UUID> preparedIds(@Param("settingId") UUID settingId);
}
