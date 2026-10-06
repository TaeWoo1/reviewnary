package com.sellerops.operationscase;

import com.sellerops.inquiry.draft.AnswerBasisState;
import com.sellerops.inquiry.draft.dto.KnowledgeGapView;
import com.sellerops.knowledge.KnowledgeTopic;
import com.sellerops.operationscase.investigation.CaseDraftPreparer;
import com.sellerops.operationscase.investigation.CaseInvestigationTools;
import java.util.UUID;

/**
 * <b>The company knowledge a case is missing</b> — stored on {@code proactive_case.knowledge_gap} as JSON, read by the
 * case screen to ask the seller for exactly that, and cleared once a grounded draft exists.
 *
 * <p>Written from the same assessment whether the investigation or the draft path found it, so the case says one
 * thing. The subject is the customer's noun («방수»), never their sentence.
 *
 * @param suggestedScope {@code PRODUCT} or {@code ORG} — a question about 배송·교환·결제·증빙 belongs to a company rule,
 *                       anything else about a named product to that product. The seller may choose otherwise.
 * @param candidateId    the Knowledge Inbox row filed for this ask, when the draft path filed one — answering here
 *                       closes it
 * @param precedentMemoryId the seller's own past answer the retrieval found when no product or policy knowledge did —
 *                       an id only, so the case never holds a second copy of the text; the case screen re-reads it and
 *                       starts the seller's answer from it. Never a basis: the gap stays a gap until the seller confirms.
 */
public record CaseKnowledgeGap(String basis, String missingSubject, String suggestedScope, String topic,
                               UUID candidateId, String source, UUID precedentMemoryId,
                               java.util.List<com.sellerops.inquiry.draft.dto.NeedCoverageView> needs) {

    /** Before Inquiry Decision v2 (and every row stored before it): no need list. */
    public CaseKnowledgeGap(String basis, String missingSubject, String suggestedScope, String topic,
                            UUID candidateId, String source, UUID precedentMemoryId) {
        this(basis, missingSubject, suggestedScope, topic, candidateId, source, precedentMemoryId, null);
    }

    /** A gap with no past-answer precedent — and the shape every case stored before Past Answer Prefill v1 reads as. */
    public CaseKnowledgeGap(String basis, String missingSubject, String suggestedScope, String topic,
                            UUID candidateId, String source) {
        this(basis, missingSubject, suggestedScope, topic, candidateId, source, null);
    }

    /**
     * <b>The subject as it may be shown to the seller</b>, or null when the stored one cannot be quoted.
     *
     * <p>The rule is {@link com.sellerops.knowledge.RetrievalQuery#quotableSubject}'s and is applied HERE —
     * at the record every screen reads — rather than at each screen, so a sentence, a title and a knowledge
     * heading built from one gap cannot disagree about whether this gap has a subject.
     *
     * <p>Why a stored row can hold one that is not quotable: this JSON is written once, when the case is
     * decided, and read for as long as the case is open. A row written before the extractor learned the rule
     * (2026-09-23) still says what it said then.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String quotableSubject() {
        return com.sellerops.knowledge.RetrievalQuery.quotableSubject(missingSubject);
    }

    public static CaseKnowledgeGap fromInvestigation(CaseInvestigationTools.KnowledgeAssessment knowledge) {
        return new CaseKnowledgeGap(knowledge.basis(), knowledge.missingSubject(), knowledge.suggestedScope(),
                knowledge.topic(), null, "INVESTIGATION", knowledge.precedentMemoryId(), knowledge.needs());
    }

    /** Null unless the draft path refused for lack of an answer basis — a switched-off model is not a knowledge gap. */
    public static CaseKnowledgeGap fromDraft(CaseDraftPreparer.Prepared prepared) {
        if (!AnswerBasisState.NO_ANSWER_BASIS.name().equals(prepared.answerBasis())) {
            return null;
        }
        return of(prepared.answerBasis(), prepared.gap(), null, "DRAFT");
    }

    /**
     * The gap the <b>deterministic resolution</b> established, read off the assessment it already made.
     *
     * <p>The resolution reaches {@code NEEDS_SELLER} exactly when the knowledge lanes searched and found nothing
     * ({@code InquiryGoalResolvers.knowledge}) — the same fact the draft path reports as {@code NO_ANSWER_BASIS},
     * and taken from the <b>same</b> {@code InquiryKnowledgeAssessor.Assessment}. So the two sources cannot
     * disagree about what is missing; they differ only in which of them got there first.
     *
     * <p>This has to exist because a rule-decided case runs neither the investigation nor the draft. Before it, a
     * case could recommend {@code ADD_KNOWLEDGE} on screen while carrying no gap — and every control that asks the
     * seller for knowledge keys off the gap, so the recommendation had nowhere to go.
     */
    public static CaseKnowledgeGap fromResolution(AnswerBasisState basis, KnowledgeGapView gap,
                                                  String missingSubject) {
        return of(basis == null ? null : basis.name(), gap, missingSubject, "RESOLUTION");
    }

    /** One shape, whoever found it — so the case says one thing about what it is missing. */
    private static CaseKnowledgeGap of(String basis, KnowledgeGapView gap, String missingSubject, String source) {
        String topic = gap == null ? null : gap.topic();
        String subject = gap == null ? missingSubject
                : gap.askedSubject() != null ? gap.askedSubject()
                : gap.missingSubject() != null ? gap.missingSubject()
                : missingSubject != null ? missingSubject
                : topic != null ? KnowledgeTopic.valueOf(topic).labelKo() : null;
        String scope = gap == null || gap.productId() == null || topic != null ? "ORG" : "PRODUCT";
        return new CaseKnowledgeGap(basis, subject, scope, topic,
                gap == null ? null : gap.candidateId(), source, gap == null ? null : gap.precedentMemoryId(),
                gap == null ? null : gap.needs());
    }
}
