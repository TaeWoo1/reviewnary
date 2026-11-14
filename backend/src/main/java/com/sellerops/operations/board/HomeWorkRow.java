package com.sellerops.operations.board;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One row of the seller's work, as the server reads it (2026-11-09, demo handoff §7.12). The fields are the ones the
 * Home's {@code HomeWorkRow} was built from on the screen; the screen's wording is still the screen's — this carries
 * facts, not sentences.
 *
 * <p>{@code owner} is the identity: the screen that owns the customer item ({@code /inquiries/{id}} or
 * {@code /reviews/reply/{id}}). A case and the raw item it is about share it, and the case wins.
 *
 * <p>{@code bucket} is the one 할 일 group this row is counted in; {@code past} says it is 지난 일 instead and is not
 * counted there. Text fields are the sources' own masked previews — nothing here reads a body.
 */
public record HomeWorkRow(
        String key,
        String owner,
        String to,
        String kind,
        String subject,
        UUID subjectId,
        UUID caseId,
        UUID workItemId,
        String state,
        String delegatedState,
        String bucket,
        boolean past,
        boolean praise,
        boolean safety,
        String safetyReading,
        String safetyKind,
        String channelCode,
        String channelNameKo,
        Integer rating,
        String reviewTier,
        String title,
        String said,
        String context,
        Instant since,
        boolean dueToday,
        LocalDate nextCheckOn,
        String waitingOn,
        String sellerCheckAsk,
        List<String> missingInformation,
        UUID replyAccountId,
        String replyActionRef,
        /**
         * The other work on the same customer item, beyond the row's own (2026-11-10 review of §7.12). One item is one
         * row, so a check due today on an inquiry whose reply is still the seller's rides here instead of standing as a
         * second row — with its own action, day and identifiers, nothing merged into the row's. Empty for most rows.
         */
        List<Work> also,
        /**
         * The record this row was read from, as its own screen serves it (2026-11-10, Home FE connection): the case
         * row, the undecided review, the seller's reply work, the queue's inquiry, or the follow-up. The Home draws a
         * row with the same function that drew it from that record before, so a row reads the same whichever read
         * made it. Nothing in it is new to the screen — each is what that screen's own endpoint already returns.
         */
        Origin origin) {

    /**
     * One piece of work on the item: what to do ({@code CHECK_AGAIN} · {@code TOLD_BEHIND}), the work item and record
     * it belongs to, its day, whether that day has come, and {@code to} — the screen that holds the record (the
     * inquiry's own), where its button goes.
     */
    public record Work(String action, UUID workItemId, UUID checkId, String waitingOn, LocalDate nextCheckOn,
                       boolean dueToday, Instant since, String note, String to) {
    }

    /** Exactly one of the record fields is set, the one {@code kind} names. */
    public record Origin(String kind,
                         com.sellerops.operationscase.dto.CustomerOperationsHomeView.DecisionRow caseRow,
                         com.sellerops.operations.dto.OperationsHomeView.AttentionReview attention,
                         Committed committed,
                         com.sellerops.inquiry.queue.dto.InquiryQueueItem inquiry,
                         com.sellerops.inquiry.followup.InquiryFollowUpService.FollowUpRow followUp) {
    }

    /** The seller's own reply work on one account: the account's facts beside the item, as the review read has them. */
    public record Committed(UUID accountId, String channelCode, String channelNameKo,
                            com.sellerops.attention.dto.OperatorVocItem item) {
    }

    public HomeWorkRow {
        also = also == null ? List.of() : List.copyOf(also);
    }

    /** A row with no other work on its item. */
    public HomeWorkRow(String key, String owner, String to, String kind, String subject, UUID subjectId, UUID caseId,
                       UUID workItemId, String state, String delegatedState, String bucket, boolean past,
                       boolean praise, boolean safety, String safetyReading, String safetyKind, String channelCode,
                       String channelNameKo, Integer rating, String reviewTier, String title, String said,
                       String context, Instant since, boolean dueToday, LocalDate nextCheckOn, String waitingOn,
                       String sellerCheckAsk, List<String> missingInformation, UUID replyAccountId,
                       String replyActionRef) {
        this(key, owner, to, kind, subject, subjectId, caseId, workItemId, state, delegatedState, bucket, past, praise,
                safety, safetyReading, safetyKind, channelCode, channelNameKo, rating, reviewTier, title, said, context,
                since, dueToday, nextCheckOn, waitingOn, sellerCheckAsk, missingInformation, replyAccountId,
                replyActionRef, List.of(), null);
    }

    HomeWorkRow withPast(boolean value) {
        return new HomeWorkRow(key, owner, to, kind, subject, subjectId, caseId, workItemId, state, delegatedState, bucket,
                value, praise, safety, safetyReading, safetyKind, channelCode, channelNameKo, rating, reviewTier, title,
                said, context, since, dueToday, nextCheckOn, waitingOn, sellerCheckAsk, missingInformation,
                replyAccountId, replyActionRef, also, origin);
    }

    /**
     * <b>A draft the check before approval held is not 「승인 가능」</b> (2026-11-14 review): 승인 is refused until the seller
     * fixes it or decides, so a row offered as approvable sent them to a button that would not go. While the hold stands
     * undecided the row is 판단 필요, never folded among the thank-yous; once it is fixed or decided the row is read from
     * the draft again and returns to its own bucket. Only an approvable row moves — a safety mark or a seller-check ask
     * keeps its place.
     */
    HomeWorkRow heldDraft(boolean held) {
        if (!held || !"approvable".equals(bucket)) {
            return this;
        }
        return new HomeWorkRow(key, owner, to, kind, subject, subjectId, caseId, workItemId, "DRAFT_HELD", delegatedState,
                "needsLook", past, false, safety, safetyReading, safetyKind, channelCode, channelNameKo, rating,
                reviewTier, title, said, context, since, dueToday, nextCheckOn, waitingOn, sellerCheckAsk,
                missingInformation, replyAccountId, replyActionRef, also, origin);
    }

    /** The same row, with the record it was read from. */
    HomeWorkRow withOrigin(Origin value) {
        return new HomeWorkRow(key, owner, to, kind, subject, subjectId, caseId, workItemId, state, delegatedState, bucket,
                past, praise, safety, safetyReading, safetyKind, channelCode, channelNameKo, rating, reviewTier, title,
                said, context, since, dueToday, nextCheckOn, waitingOn, sellerCheckAsk, missingInformation,
                replyAccountId, replyActionRef, also, value);
    }

    /**
     * The same row carrying one more piece of work. The row's own bucket, state, time and place in the order stay as
     * they are (no new priority rule); a due piece makes the row today's work — the existing rule that a check whose
     * day has come is never 지난 일.
     */
    HomeWorkRow withAlso(Work work) {
        List<Work> more = new java.util.ArrayList<>(also);
        more.add(work);
        return new HomeWorkRow(key, owner, to, kind, subject, subjectId, caseId, workItemId, state, delegatedState, bucket,
                past, praise, safety, safetyReading, safetyKind, channelCode, channelNameKo, rating, reviewTier, title,
                said, context, since, dueToday || work.dueToday(), nextCheckOn, waitingOn, sellerCheckAsk,
                missingInformation, replyAccountId, replyActionRef, more, origin);
    }

    /** Whether this row is in 「오늘 확인」: a check whose day has come, its own or one riding on it. */
    public boolean checkToday() {
        return "checkAgain".equals(bucket) || alsoDueToday();
    }

    /** Whether a piece of this row's other work is due today. */
    public boolean alsoDueToday() {
        return also.stream().anyMatch(Work::dueToday);
    }
}
