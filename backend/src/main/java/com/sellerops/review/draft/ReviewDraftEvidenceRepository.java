package com.sellerops.review.draft;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read the citations of one review draft version, in the order they were shown to the drafter. */
public interface ReviewDraftEvidenceRepository extends JpaRepository<ReviewDraftEvidence, UUID> {

    List<ReviewDraftEvidence> findByOrgIdAndReviewIdAndDraftVersionOrderByOrdinalAsc(
            UUID orgId, UUID reviewId, int draftVersion);

    /**
     * The review lane's twin of {@code InquiryDraftEvidenceRepository#citationsBySource} — same
     * grouping, same meaning, same prohibition on reading it as "delivered to a customer".
     *
     * <p>It is a second query rather than a union because the two tables are keyed by different
     * objects and neither lane may learn the other's schema to count its own rows.
     */
    @Query("""
            select e.sourceId, e.kind, count(e), max(e.createdAt)
            from ReviewDraftEvidence e
            where e.orgId = :orgId and e.sourceId is not null
            group by e.sourceId, e.kind
            """)
    List<Object[]> citationsBySource(@Param("orgId") UUID orgId);
}
