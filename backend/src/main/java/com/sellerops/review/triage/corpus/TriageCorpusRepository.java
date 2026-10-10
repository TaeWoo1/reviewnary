package com.sellerops.review.triage.corpus;

import com.sellerops.review.triage.feedback.CorrectionDisposition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The one read that assembles a cut set's rows, and the only place three of the feedback tables are joined.
 *
 * <p>It is a {@link Repository} and not a {@code JpaRepository} on purpose: there is no {@code save}, no
 * {@code delete} and no {@code findAll} on this interface, so no caller in this package can write a disposition
 * or a correction through it. Writing a disposition is {@code TriageFeedbackService}'s, and stays there.
 *
 * <p><b>The prediction is deliberately NOT joined here.</b> A correction of a tier the rule produced has no
 * prediction behind it (RUBRIC v2 §13.7's pilot case) — most of the pilot's rows are exactly that — so the join
 * would have to be an outer one, and an outer join onto an unrelated entity beside two implicit joins is JPQL
 * Hibernate declines to validate. {@code predictionId} comes back on the row and
 * {@link TriageCorpusService} resolves the ones that exist in a second indexed read, which is also the shape
 * that makes «no prediction» a value rather than a dropped row.
 */
public interface TriageCorpusRepository extends Repository<CorrectionDisposition, UUID> {

    /**
     * Every member of one cut correction set, as the columns a corpus row is made of.
     *
     * <p><b>The review arrives as its channel-side id and leaves as a fingerprint.</b> The raw
     * {@code external_id} is selected because {@code ReviewIdFingerprint.of} needs it, and it is digested in
     * {@link TriageCorpusService} before anything can hold it — the same boundary
     * {@code tools/review-triage-calibration/derive-labels.mjs} draws for the gold set.
     */
    @Query("""
            select r.externalId, c.shownTier, c.shownSource, c.correctedTier, c.correctedReasonCode,
                   c.correctedTags, c.predictionId
            from CorrectionDisposition d, TriageCorrection c, Review r
            where d.orgId = :orgId and d.snapshotVersion = :version
              and c.id = d.correctionId and r.id = c.reviewId and r.orgId = :orgId
            order by r.externalId asc
            """)
    List<Object[]> correctionSnapshotRows(@Param("orgId") UUID orgId, @Param("version") String version);
}
