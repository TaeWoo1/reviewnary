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
}
