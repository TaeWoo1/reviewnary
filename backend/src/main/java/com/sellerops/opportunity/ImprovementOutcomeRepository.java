package com.sellerops.opportunity;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImprovementOutcomeRepository extends JpaRepository<ImprovementOutcome, UUID> {

    Optional<ImprovementOutcome> findByOrgIdAndOpportunityId(UUID orgId, UUID opportunityId);

    List<ImprovementOutcome> findByOrgIdAndOpportunityIdIn(UUID orgId, Collection<UUID> opportunityIds);

    List<ImprovementOutcome> findByOrgIdAndIssueIdOrderByAppliedOnDesc(UUID orgId, UUID issueId);

    /**
     * The windows whose last day has passed and which have not been read yet.
     *
     * <p>{@code verdict = OBSERVING} is the whole of «not read yet»: V131's
     * {@code ck_improvement_outcome_settled} makes that equivalent to {@code evaluated_at is null}, so there is
     * one definition of pending rather than two columns that could disagree.
     */
    @Query("""
            select o from ImprovementOutcome o
            where o.orgId = :orgId and o.verdict = :observing and o.appliedOn <= :lastStartDay
            order by o.appliedOn asc
            """)
    List<ImprovementOutcome> dueForReading(@Param("orgId") UUID orgId,
                                           @Param("lastStartDay") LocalDate lastStartDay,
                                           @Param("observing") OutcomeVerdict observing);

    /**
     * What this company's remediations on one product actually did — the evidence an investigation may cite.
     *
     * <p>Settled only. An outcome still 확인 중 says nothing yet, and handing it to an investigation as evidence
     * would let 「아직 모릅니다」 be quoted as a finding.
     */
    @Query("""
            select o from ImprovementOutcome o
            where o.orgId = :orgId and o.verdict <> :observing
              and (o.productId = :productId or o.scope = :orgScope)
            order by o.appliedOn desc
            """)
    List<ImprovementOutcome> settledForProduct(@Param("orgId") UUID orgId,
                                               @Param("productId") UUID productId,
                                               @Param("observing") OutcomeVerdict observing,
                                               @Param("orgScope") OpportunityRules.Scope orgScope,
                                               org.springframework.data.domain.Pageable page);
}
