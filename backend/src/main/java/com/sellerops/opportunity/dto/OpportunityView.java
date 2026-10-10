package com.sellerops.opportunity.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One improvement opportunity as the seller sees it: what repeated, why this is suggested, what the
 * evidence is, and what can be done next.
 *
 * <p>Identity is {@code (issueId, kind)} — an opportunity is derived, so it has no id of its own and
 * needs none: the same issue and the same rule always name the same opportunity.
 *
 * @param whyKo fact sentences only — evidence count and span, the issue's own change judgement, the
 *     product, and the knowledge check. Never a cause, never an expected effect.
 * @param recommendationKo the suggestion in the seller's words, phrased as something to REVIEW
 * @param evidenceTo the issue's evidence surface — every quote behind this opportunity lives there
 * @param knowledge null for a product improvement review (no sentence to a customer answers it)
 * @param draft present only while ACCEPTED
 * @param history what the seller has done about this opportunity, oldest first. Empty means nothing
 *     has been done — the decision itself is {@code status}, and this is how it got there.
 * @param appliedAt when the seller carried it out, or null. The date the outcome window is anchored on
 * @param outcome what happened to the reviews afterwards, or null while nothing has been applied. Carried on
 *     the opportunity because the question «did it work» belongs beside the thing that was done; the issue's own
 *     surface reads the same rows through {@code /api/opportunities/outcomes} so a result cannot be lost when
 *     the rules stop deriving this kind
 */
public record OpportunityView(UUID issueId, String kind, String kindLabelKo,
                              String status, String statusLabelKo,
                              String issueTitle, String aspect, String problem, String severity,
                              long evidenceCount, LocalDate firstEvidenceOn, LocalDate lastEvidenceOn,
                              List<String> changeLabelsKo,
                              UUID productId, String productName,
                              List<String> whyKo, String recommendationKo,
                              String evidenceTo,
                              OpportunityKnowledgeView knowledge,
                              String nextActionKo,
                              OpportunityDraftView draft,
                              List<OpportunityEventView> history,
                              Instant decidedAt,
                              Instant appliedAt,
                              OpportunityOutcomeView outcome) {

    /**
     * The pre-outcome shape, for every caller that predates Learning &amp; Outcome Loop v1.
     *
     * <p>Kept because an opportunity nobody applied has no outcome and no application date, and those two nulls
     * are the honest answer rather than a gap — a delegating constructor says so once instead of at every
     * construction site.
     */
    public OpportunityView(UUID issueId, String kind, String kindLabelKo,
                           String status, String statusLabelKo,
                           String issueTitle, String aspect, String problem, String severity,
                           long evidenceCount, LocalDate firstEvidenceOn, LocalDate lastEvidenceOn,
                           List<String> changeLabelsKo,
                           UUID productId, String productName,
                           List<String> whyKo, String recommendationKo,
                           String evidenceTo,
                           OpportunityKnowledgeView knowledge,
                           String nextActionKo,
                           OpportunityDraftView draft,
                           List<OpportunityEventView> history,
                           Instant decidedAt) {
        this(issueId, kind, kindLabelKo, status, statusLabelKo, issueTitle, aspect, problem, severity,
                evidenceCount, firstEvidenceOn, lastEvidenceOn, changeLabelsKo, productId, productName, whyKo,
                recommendationKo, evidenceTo, knowledge, nextActionKo, draft, history, decidedAt, null, null);
    }
}
