package com.sellerops.opportunity;

import com.sellerops.opportunity.dto.OpportunityOutcomeView;

/**
 * Entity → view, in the package that owns the entity.
 *
 * <p>Separate from the record so {@code dto} keeps importing nothing from this package. Every other view in
 * {@code opportunity.dto} is built the same way, by the service rather than by the record.
 */
final class OpportunityOutcomeViews {

    private OpportunityOutcomeViews() {
    }

    static OpportunityOutcomeView of(ImprovementOutcome row, OpportunityKind kind) {
        return new OpportunityOutcomeView(
                kind == null ? null : kind.name(),
                kind == null ? null : kind.labelKo(),
                row.getScope().name(),
                row.getAppliedOn(),
                // While the window is open this is the day it WILL close — the one date a seller asks for.
                row.getObservedThrough() != null ? row.getObservedThrough()
                        : ImprovementOutcomeRules.observeThrough(row.getAppliedOn()),
                row.getBaselineEvidence(), row.getBaselineReviews(),
                row.getObservedEvidence(), row.getObservedReviews(),
                row.getVerdict().name(), row.getVerdict().labelKo(), row.getReason().labelKo(),
                row.getVerdict().settled());
    }
}
