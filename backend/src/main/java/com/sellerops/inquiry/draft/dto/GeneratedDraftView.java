package com.sellerops.inquiry.draft.dto;

import com.sellerops.inquiry.reply.dto.ReplyDraftView;
import java.util.List;
import java.util.UUID;

/**
 * The result of asking for one reply draft: what was written, what it was grounded in, and — when
 * nothing was written — which basis is missing.
 *
 * <p><b>{@code draft} may be null, and that is a real answer.</b> Since 2026-08-26 a draft is
 * produced only when current evidence applies to the question. In
 * {@code AnswerBasisState.NO_ANSWER_BASIS} the screen shows 「답변 기준이 필요합니다」 and the seller
 * writes the reply themselves; SellerOps does not compose a fluent paragraph that commits to nothing
 * in order to have something in the box.
 *
 * <p><b>Answer basis and operational state are different questions</b> (product-owner, 2026-08-27).
 * {@code answerBasis} says what the EVIDENCE supports; {@code unavailableMessage} says whether the
 * machinery ran. A vendor timeout on a fully grounded question used to be reported as
 * {@code NO_ANSWER_BASIS}, which told a seller their library was missing something when it was not.
 *
 * <p>{@code draft.version} and {@code draft.contentFingerprint} are the pair an approval will later
 * bind to. They are returned here so the screen that shows the draft is showing the exact version it
 * would send, rather than a preview that a subsequent read might resolve differently.
 *
 * @param draft            the saved append-only version, or null when none was written
 * @param authorKind       {@code MODEL}, or null when nothing was written
 * @param knowledgeState   which of the four library states the retrieval reached
 * @param knowledgeNote    that state as the one sentence shown above the draft
 * @param answerBasis      {@code GROUNDED} / {@code NEEDS_CLARIFICATION} / {@code NO_ANSWER_BASIS} —
 *                         the projection of {@code knowledgeState} and the spec applicability
 * @param answerBasisNote  that state as the sentence a seller reads
 * @param answerBasisAction what the seller could do about a missing basis, or null
 * @param productId        the canonical product the retrieval was scoped to, or null
 * @param evidence         the passages actually put in front of the drafter, in order
 * @param companyContextUsed whether the seller's registered 회사 정보 was put in front of the
 *                         drafter as wording context (Seller Context v1-B). Metadata only — the
 *                         text itself is never returned here — and never a basis: it is true only
 *                         for a MODEL draft that was already grounded by the evidence rules
 * @param unavailableMessage why no draft exists for an OPERATIONAL reason — the day's AI budget,
 *                         the capability being off, a vendor that did not answer, or a 상세페이지
 *                         read that failed. <b>Its presence changes what the screen may say.</b>
 *                         Not having looked successfully is not the same as having looked and found
 *                         nothing, so when this is set the screen shows the operational sentence and
 *                         must NOT show 「답변 기준이 필요합니다」 — that sentence belongs to the one
 *                         case where retrieval actually settled with no usable evidence
 * @param unavailableKind  that reason as a closed word — HELD_BY_CHECK · WAITING_FOR_CHECK · NOT_RUN — or null
 * @param openQuestions    what this draft did not answer and left to the seller (tenth review) — empty when it answers
 *                         everything asked; while one is open the reply is not sent
 * @param rewrite          the one rewrite (2026-11-02), as a closed word — null when the first draft was not rewritten,
 *                         {@code PASSED} when the rewrite passed every check, {@code REFUSED} when a check refused it too,
 *                         {@code PENDING} when the rewrite is held for its check's budget (검토 대기 — not a refusal),
 *                         {@code NOT_WRITTEN} when the rewrite call wrote nothing. A held rewrite resumed later still says
 *                         {@code PASSED} or {@code REFUSED} (2026-11-02, fifth review). An evaluation reads it to tell a
 *                         first try from a second; the screen does not
 * @param sellerCheckAsk   what the seller is asked to check on the seller centre (2026-11-02 seller-check review) — the
 *                         system's reading of the inquiry, never a verified one: {@code ORDER_ACTION} (something to be done
 *                         to this order — a refund, a cancellation), {@code ORDER_STATE} (how this order stands), or null
 *                         when no order need is left open. The screen asks for a record ({@code …/seller-check}) on it
 */
public record GeneratedDraftView(ReplyDraftView draft, String authorKind, String knowledgeState,
                                 String knowledgeNote, String answerBasis, String answerBasisNote,
                                 String answerBasisAction, UUID productId,
                                 List<DraftEvidenceView> evidence, boolean companyContextUsed,
                                 String unavailableMessage, KnowledgeGapView knowledgeGap,
                                 String unavailableKind, List<String> openQuestions, String rewrite,
                                 String sellerCheckAsk) {

    public GeneratedDraftView(ReplyDraftView draft, String authorKind, String knowledgeState, String knowledgeNote,
                              String answerBasis, String answerBasisNote, String answerBasisAction, UUID productId,
                              List<DraftEvidenceView> evidence, boolean companyContextUsed, String unavailableMessage,
                              KnowledgeGapView knowledgeGap, String unavailableKind, List<String> openQuestions,
                              String rewrite) {
        this(draft, authorKind, knowledgeState, knowledgeNote, answerBasis, answerBasisNote, answerBasisAction, productId,
                evidence, companyContextUsed, unavailableMessage, knowledgeGap, unavailableKind, openQuestions, rewrite,
                null);
    }

    /** The same view, naming what the seller is asked to check (null leaves it as it is). */
    public GeneratedDraftView withSellerCheckAsk(String ask) {
        return ask == null ? this : new GeneratedDraftView(draft, authorKind, knowledgeState, knowledgeNote,
                answerBasis, answerBasisNote, answerBasisAction, productId, evidence, companyContextUsed,
                unavailableMessage, knowledgeGap, unavailableKind, openQuestions, rewrite, ask);
    }

    public GeneratedDraftView(ReplyDraftView draft, String authorKind, String knowledgeState, String knowledgeNote,
                              String answerBasis, String answerBasisNote, String answerBasisAction, UUID productId,
                              List<DraftEvidenceView> evidence, boolean companyContextUsed, String unavailableMessage,
                              KnowledgeGapView knowledgeGap, String unavailableKind, List<String> openQuestions) {
        this(draft, authorKind, knowledgeState, knowledgeNote, answerBasis, answerBasisNote, answerBasisAction, productId,
                evidence, companyContextUsed, unavailableMessage, knowledgeGap, unavailableKind, openQuestions, null);
    }

    /** The same view, saying how the one rewrite went. */
    public GeneratedDraftView withRewrite(String outcome) {
        return outcome == null ? this : new GeneratedDraftView(draft, authorKind, knowledgeState, knowledgeNote,
                answerBasis, answerBasisNote, answerBasisAction, productId, evidence, companyContextUsed,
                unavailableMessage, knowledgeGap, unavailableKind, openQuestions, outcome, sellerCheckAsk);
    }

    public GeneratedDraftView(ReplyDraftView draft, String authorKind, String knowledgeState, String knowledgeNote,
                              String answerBasis, String answerBasisNote, String answerBasisAction, UUID productId,
                              List<DraftEvidenceView> evidence, boolean companyContextUsed, String unavailableMessage,
                              KnowledgeGapView knowledgeGap, String unavailableKind) {
        this(draft, authorKind, knowledgeState, knowledgeNote, answerBasis, answerBasisNote, answerBasisAction, productId,
                evidence, companyContextUsed, unavailableMessage, knowledgeGap, unavailableKind, List.of());
    }

    /** {@code unavailableKind} is read off the sentence — {@code InquiryDraftComposer#unavailableKind}. */
    public GeneratedDraftView(ReplyDraftView draft, String authorKind, String knowledgeState, String knowledgeNote,
                              String answerBasis, String answerBasisNote, String answerBasisAction, UUID productId,
                              List<DraftEvidenceView> evidence, boolean companyContextUsed, String unavailableMessage,
                              KnowledgeGapView knowledgeGap) {
        this(draft, authorKind, knowledgeState, knowledgeNote, answerBasis, answerBasisNote, answerBasisAction, productId,
                evidence, companyContextUsed, unavailableMessage, knowledgeGap,
                com.sellerops.inquiry.draft.InquiryDraftComposer.unavailableKind(unavailableMessage));
    }
}
