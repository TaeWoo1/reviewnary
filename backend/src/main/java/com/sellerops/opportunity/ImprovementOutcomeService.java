package com.sellerops.opportunity;

import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Anchor one improvement at the moment it is applied, and read the window once it closes.</b>
 *
 * <p>Two methods carry the package: {@link #anchor} runs inside the seller's 적용 press and freezes the premise
 * of a measurement that will not be readable for four weeks; {@link #read} runs in the issue memory's existing
 * automatic pass and settles the ones whose window has ended. Nothing in between — no scheduler of its own, no
 * daily recompute, no model.
 *
 * <p><b>Why anchoring has to happen at the press.</b> The baseline is «what this problem looked like in the four
 * weeks before the seller acted», and four weeks later that window is gone: evidence can still arrive late for
 * past days, reviews can be imported for them, and the issue can be re-extracted. Computing the baseline at
 * reading time would measure the fix against a before-picture assembled after the fact. Freezing it at the press
 * costs two counts and makes the number mean something.
 *
 * <p><b>Why the window is read exactly once.</b> A verdict is about one fixed window; recomputing it later would
 * be a different measurement under the same name, and the one after that would be different again. V131's
 * {@code ck_improvement_outcome_settled} makes «settled» and «has an evaluation instant» the same fact, and
 * {@link ImprovementOutcomeRepository#dueForReading} reads only the unsettled.
 *
 * <p><b>It concludes nothing about the issue.</b> Reading a window writes a verdict on this row and touches
 * nothing else: no lifecycle transition, no case, no policy, no triage tier, no guidance.
 * {@code ImprovementOutcomeFenceTest} asserts those absences — an outcome is a fact the next judgement may
 * CITE, never a rule that changes it.
 */
@Service
public class ImprovementOutcomeService {

    private static final ZoneOffset DAY_ZONE = ZoneOffset.UTC;
    /** Bounded like every other past-evidence read: a few, newest first. Never a corpus. */
    static final int MAX_FOR_INVESTIGATION = 3;

    private final ImprovementOutcomeRepository outcomes;
    private final ImprovementOpportunityRepository decisions;
    private final ReviewIssueEvidenceRepository evidence;
    private final ReviewRepository reviews;
    private final Clock clock;

    @Autowired
    public ImprovementOutcomeService(ImprovementOutcomeRepository outcomes,
                                     ImprovementOpportunityRepository decisions,
                                     ReviewIssueEvidenceRepository evidence, ReviewRepository reviews) {
        this(outcomes, decisions, evidence, reviews, Clock.systemUTC());
    }

    ImprovementOutcomeService(ImprovementOutcomeRepository outcomes, ImprovementOpportunityRepository decisions,
                              ReviewIssueEvidenceRepository evidence, ReviewRepository reviews, Clock clock) {
        this.outcomes = outcomes;
        this.decisions = decisions;
        this.evidence = evidence;
        this.reviews = reviews;
        this.clock = clock;
    }

    /**
     * Freeze the premise for one application. Idempotent: a second 적용 of the same opportunity returns the
     * standing anchor rather than re-measuring a baseline against a later day.
     *
     * @param scope     the reach of what the seller did — PRODUCT for a product's own shelf, ORG for a company
     *                  rule. The population the result is counted in, and the reason a product fix is not
     *                  diluted by the whole catalogue
     * @param productId the product, required for PRODUCT scope and refused for ORG by V131's check
     */
    @Transactional
    public ImprovementOutcome anchor(UUID orgId, UUID opportunityId, UUID issueId,
                                     OpportunityRules.Scope scope, UUID productId, LocalDate appliedOn) {
        Optional<ImprovementOutcome> standing = outcomes.findByOrgIdAndOpportunityId(orgId, opportunityId);
        if (standing.isPresent()) {
            return standing.get();
        }
        LocalDate from = ImprovementOutcomeRules.baselineFrom(appliedOn);
        LocalDate to = ImprovementOutcomeRules.baselineTo(appliedOn);
        UUID countIn = scope == OpportunityRules.Scope.PRODUCT ? productId : null;
        ImprovementOutcome row = new ImprovementOutcome();
        row.setOrgId(orgId);
        row.setOpportunityId(opportunityId);
        row.setIssueId(issueId);
        row.setScope(scope);
        row.setProductId(countIn);
        row.setAppliedOn(appliedOn);
        row.setBaselineFrom(from);
        row.setBaselineTo(to);
        row.setBaselineEvidence((int) evidenceIn(orgId, issueId, countIn, from, to));
        row.setBaselineReviews((int) reviewsIn(orgId, countIn, from, to));
        row.setObserveDays(ImprovementOutcomeRules.OBSERVE_DAYS);
        row.setVerdict(OutcomeVerdict.OBSERVING);
        row.setReason(OutcomeReason.WINDOW_OPEN);
        return outcomes.save(row);
    }

    /**
     * Settle every window that has closed as of {@code referenceDate}.
     *
     * <p>Idempotent on the same reference date, and idempotent forever after it: a settled row is never
     * selected again. Returns how many were read, so the pass that calls it can report a number the way
     * {@code ReviewIssueLifecycleService.AutomaticPassResult} does.
     */
    @Transactional
    public int read(UUID orgId, LocalDate referenceDate) {
        LocalDate lastStartDay = referenceDate.minusDays(ImprovementOutcomeRules.OBSERVE_DAYS - 1L);
        List<ImprovementOutcome> due = outcomes.dueForReading(orgId, lastStartDay, OutcomeVerdict.OBSERVING);
        for (ImprovementOutcome row : due) {
            LocalDate through = ImprovementOutcomeRules.observeThrough(row.getAppliedOn());
            long observedEvidence = evidenceIn(orgId, row.getIssueId(), row.getProductId(),
                    row.getAppliedOn(), through);
            long observedReviews = reviewsIn(orgId, row.getProductId(), row.getAppliedOn(), through);
            ImprovementOutcomeRules.Reading reading = ImprovementOutcomeRules.read(
                    row.getBaselineEvidence(), row.getBaselineReviews(), observedEvidence, observedReviews);
            row.setObservedEvidence((int) observedEvidence);
            row.setObservedReviews((int) observedReviews);
            row.setObservedThrough(through);
            row.setVerdict(reading.verdict());
            row.setReason(reading.reason());
            row.setEvaluatedAt(Instant.now(clock));
        }
        outcomes.saveAll(due);
        return due.size();
    }

    @Transactional(readOnly = true)
    public Map<UUID, ImprovementOutcome> byOpportunity(UUID orgId, Collection<UUID> opportunityIds) {
        if (opportunityIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ImprovementOutcome> out = new HashMap<>();
        for (ImprovementOutcome row : outcomes.findByOrgIdAndOpportunityIdIn(orgId, opportunityIds)) {
            out.put(row.getOpportunityId(), row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ImprovementOutcome> forIssue(UUID orgId, UUID issueId) {
        return outcomes.findByOrgIdAndIssueIdOrderByAppliedOnDesc(orgId, issueId);
    }

    /**
     * The settled results of this company's remediations that could have reached one product — what package C
     * hands an investigation.
     *
     * <p>An ORG-scoped remediation is included for every product: a shipping rule the seller rewrote in August
     * is as much a fact about this product's August as about any other's. A PRODUCT-scoped one is included only
     * for its own product, which is the same no-widening rule {@code SellerPolicyOverlay.applies} holds.
     */
    @Transactional(readOnly = true)
    public List<ImprovementOutcome> settledForProduct(UUID orgId, UUID productId) {
        if (productId == null) {
            return List.of();
        }
        return outcomes.settledForProduct(orgId, productId, OutcomeVerdict.OBSERVING,
                OpportunityRules.Scope.ORG, PageRequest.of(0, MAX_FOR_INVESTIGATION));
    }

    /**
     * A settled outcome plus the KIND of thing the seller did — the shape an investigation needs.
     *
     * <p>The kind is the one fact {@code improvement_outcome} does not carry and cannot: it belongs to the
     * decision, and duplicating it here would be a second copy of the opportunity's own identity. Joined on the
     * way out instead, in one batched read.
     */
    public record Settled(UUID issueId, OpportunityKind kind, LocalDate appliedOn, OutcomeVerdict verdict,
                          OutcomeReason reason, int evidenceBefore, Integer evidenceAfter) {
    }

    /** {@link #settledForProduct}, with each row's kind joined on. */
    @Transactional(readOnly = true)
    public List<Settled> settledSummaryForProduct(UUID orgId, UUID productId) {
        List<ImprovementOutcome> rows = settledForProduct(orgId, productId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, OpportunityKind> kinds = new HashMap<>();
        for (ImprovementOpportunity row : decisions.findAllById(
                rows.stream().map(ImprovementOutcome::getOpportunityId).toList())) {
            if (orgId.equals(row.getOrgId())) {
                kinds.put(row.getId(), row.getKind());
            }
        }
        return rows.stream()
                .map(r -> new Settled(r.getIssueId(), kinds.get(r.getOpportunityId()), r.getAppliedOn(),
                        r.getVerdict(), r.getReason(), r.getBaselineEvidence(), r.getObservedEvidence()))
                .toList();
    }

    private long evidenceIn(UUID orgId, UUID issueId, UUID productId, LocalDate from, LocalDate to) {
        return productId == null
                ? evidence.countByOrgIdAndIssueIdAndOccurredOnBetween(orgId, issueId, from, to)
                : evidence.countByOrgIdAndIssueIdAndProductIdAndOccurredOnBetween(
                        orgId, issueId, productId, from, to);
    }

    /**
     * The denominator, over the same calendar days the evidence count covers.
     *
     * <p>{@code occurred_on} is the UTC day bucket of {@code reviews.received_at}, so the half-open instant
     * range is derived from the same arithmetic rather than from a second timezone decision — otherwise the
     * numerator and the denominator would describe windows one day apart at the edges.
     */
    private long reviewsIn(UUID orgId, UUID productId, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(DAY_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(DAY_ZONE).toInstant();
        return productId == null
                ? reviews.countByOrgIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(orgId, start, end)
                : reviews.countByOrgIdAndProductIdAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                        orgId, productId, start, end);
    }
}
