package com.sellerops.operations.dto;

import com.sellerops.attention.dto.OperatorVocItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The review work 확인할 일 owes the seller — every piece of it, from the reads that already define it
 * (UI/UX v2 Phase 3).
 *
 * <ul>
 *   <li>{@code attention}: every undecided 확인 필요 review (the Home's own predicate and row mapping, without the
 *   Home's three-row cap), and {@code attentionTotal}, how many exist.</li>
 *   <li>{@code committed}: per account with a reply flow, the seller's own reply to-do in the two states that still
 *   need them — {@code DRAFT_NEEDED} and {@code AWAITING_APPROVAL} — exactly as {@code OperatorAttentionService
 *   .replyWork} defines them. {@code APPROVED} is left out because 실행 대기 already owns it; a dismissed review is
 *   already out, by that read's own predicate.</li>
 * </ul>
 *
 * <p>Nothing new is decided here: no rank, no filter, no state. It exists because those items used to be reachable
 * only from the 리뷰 screen's 「내 답변 작업」, which is a record screen.
 */
public record ReviewWorkView(
        long attentionTotal,
        List<OperationsHomeView.AttentionReview> attention,
        List<AccountWork> committed,
        /**
         * The reviews in {@code committed} that report a safety or legal problem ({@code ReviewSafetySignal}) — an
         * {@code OperatorVocItem} is metadata only and does not carry it; an {@code attention} row carries its own.
         */
        Set<UUID> safetyReviewIds,
        /** Of {@code safetyReviewIds}, those that read as a worry (우려) rather than an occurrence (발생). */
        Set<UUID> safetyWorryReviewIds,
        /**
         * The reviews in {@code committed} that went to the delegate lane and came back to the seller — the lane's own
         * state ({@code AWAITING_APPROVAL} on the phone, {@code RECHECK_NEEDED}, {@code HELD}, {@code FAILED}). This
         * PC's draft read cannot see the lane, so without it every one of them reads as a plain 승인 대기.
         */
        Map<UUID, String> delegatedStates,
        /** Of {@code safetyReviewIds}, each one's kind of harm — what its label names (2026-10-25). */
        Map<UUID, String> safetyKinds) {

    public ReviewWorkView(long attentionTotal, List<OperationsHomeView.AttentionReview> attention,
                          List<AccountWork> committed, Set<UUID> safetyReviewIds, Set<UUID> safetyWorryReviewIds,
                          Map<UUID, String> delegatedStates) {
        this(attentionTotal, attention, committed, safetyReviewIds, safetyWorryReviewIds, delegatedStates, Map.of());
    }

    /**
     * One account's share. {@code recentlyReported} is the same read's history — replies the seller reported
     * posting — carried so 확인할 일 can keep the list the 리뷰 screen used to show, as history rather than as work.
     */
    public record AccountWork(UUID accountId, String channelCode, String channelNameKo, String coverage,
                              List<OperatorVocItem> todo, List<OperatorVocItem> recentlyReported) {
    }
}
