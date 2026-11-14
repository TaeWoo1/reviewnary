package com.sellerops.auth.runtoken;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplyAgentRunRepository extends JpaRepository<ReplyAgentRun, UUID> {

    Optional<ReplyAgentRun> findByTokenHash(String tokenHash);

    /** Whether a run already handed this review to a person — drafting ahead does not run it again (review 2026-10-28). */
    boolean existsByOrgIdAndSubjectIdAndOutcome(UUID orgId, UUID subjectId, String outcome);

    /** The org's latest runs that ended with a person, newest first — the list the seller works through. */
    java.util.List<ReplyAgentRun> findTop100ByOrgIdAndOutcomeOrderByFinishedAtDesc(UUID orgId, String outcome);

    java.util.List<ReplyAgentRun> findByOrgIdAndFinishedAtAfter(UUID orgId, java.time.Instant after);

    /** The latest run of a review that ended with this outcome — a held draft to resume. */
    Optional<ReplyAgentRun> findFirstByOrgIdAndSubjectIdAndOutcomeOrderByFinishedAtDesc(UUID orgId, UUID subjectId,
                                                                                         String outcome);

    /** The latest run of a review, however it ended. */
    Optional<ReplyAgentRun> findFirstByOrgIdAndSubjectIdOrderByCreatedAtDesc(UUID orgId, UUID subjectId);

    /**
     * Whether a run of this subject is still open — started, not finished, token not expired (2026-11-09). A seller whose
     * screen stopped waiting presses again while the first run is still drafting; a second run would pay for the same
     * draft twice and race it to the save.
     */
    boolean existsByOrgIdAndSubjectIdAndFinishedAtIsNullAndExpiresAtAfter(UUID orgId, UUID subjectId,
                                                                          java.time.Instant now);

    /**
     * Take this org's review row for the rest of the transaction (2026-11-09, {@code docs/FINDINGS.md} §B126) — the lock
     * {@link ReplyRunTokenService#mintIfIdle} holds over its look-for-an-open-run and its insert. Empty when the review is
     * not this org's. Only inside a caller's transaction — outside one the lock would end with the statement.
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    @org.springframework.data.jpa.repository.Query(
            value = "select id from reviews where id = :reviewId and org_id = :orgId for update", nativeQuery = true)
    Optional<UUID> lockReviewRow(@org.springframework.data.repository.query.Param("orgId") UUID orgId,
                                 @org.springframework.data.repository.query.Param("reviewId") UUID reviewId);
}
