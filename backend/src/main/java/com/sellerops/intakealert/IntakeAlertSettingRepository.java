package com.sellerops.intakealert;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntakeAlertSettingRepository extends JpaRepository<IntakeAlertSetting, UUID> {

    Optional<IntakeAlertSetting> findByOrgIdAndUserId(UUID orgId, UUID userId);

    List<IntakeAlertSetting> findByEnabledTrue();

    List<IntakeAlertSetting> findByOrgIdAndEnabledTrue(UUID orgId);

    /**
     * Take the digest slot: succeeds (1) for exactly one caller per interval, however many workers try at once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IntakeAlertSetting s set s.lastDigestAt = :now
            where s.id = :id and (s.lastDigestAt is null or s.lastDigestAt <= :dueBefore)
            """)
    int takeDigestSlot(@Param("id") UUID id, @Param("now") java.time.Instant now,
                       @Param("dueBefore") java.time.Instant dueBefore);
}
