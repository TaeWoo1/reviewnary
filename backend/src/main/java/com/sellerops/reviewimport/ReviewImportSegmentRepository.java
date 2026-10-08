package com.sellerops.reviewimport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewImportSegmentRepository extends JpaRepository<ReviewImportSegment, UUID> {

    /** Date order is the display order and survives split/merge (ordinal would need renumbering). */
    List<ReviewImportSegment> findByPlanIdOrderBySegmentStartAsc(UUID planId);

    /** The coverage rollup ignores superseded (split-parent) segments. */
    List<ReviewImportSegment> findByPlanIdAndSupersededFalseOrderBySegmentStartAsc(UUID planId);

    Optional<ReviewImportSegment> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Every stretch of days this organisation has <b>proved</b> it read for one channel.
     *
     * <p>Coverage asks across plans, not within one: this store's 07-01 … 09-02 is three segments from three
     * different evenings' plans, and a rollup that stayed inside a plan would report the newest plan's fortnight
     * as the whole of what is held. {@code COVERED} and not superseded is the same filter the per-plan rollup
     * uses — the split parent of a reconciled segment is not a second proof of the same days.
     */
    @Query("""
            select s from ReviewImportSegment s, ReviewImportPlan p
            where s.planId = p.id and s.orgId = :orgId and p.channelId = :channelId
              and s.coverageState = com.sellerops.reviewimport.SegmentCoverageState.COVERED
              and s.superseded = false
            order by s.segmentStart asc
            """)
    List<ReviewImportSegment> findCoveredForChannel(@Param("orgId") UUID orgId, @Param("channelId") UUID channelId);
}
