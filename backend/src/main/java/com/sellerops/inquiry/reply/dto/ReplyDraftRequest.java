package com.sellerops.inquiry.reply.dto;

/**
 * Seller reply-draft save request. The seller edits only {@code title} and {@code
 * comments}; {@code answerStatus} is backend-fixed. {@code baseVersion} is required
 * — the version the edit is based on ({@code 0} for the first save) — and is used
 * for optimistic-concurrency (a stale base is rejected with 409).
 */
public record ReplyDraftRequest(
        String title,
        String comments,
        Integer baseVersion,
        /**
         * The seller's record (2026-11-03) the screen was showing when this was saved, or null. The version names it, and
         * is checked against the record that stands before it is approved or sent — a save time proves nothing.
         */
        java.util.UUID sellerCheckId,
        /**
         * The kind correction (2026-11-05) the screen was showing when this was saved, or null when none stood. The
         * version names it, and is not approved while another correction stands.
         */
        java.util.UUID kindCorrectionId) {

    public ReplyDraftRequest(String title, String comments, Integer baseVersion) {
        this(title, comments, baseVersion, null, null);
    }

    public ReplyDraftRequest(String title, String comments, Integer baseVersion, java.util.UUID sellerCheckId) {
        this(title, comments, baseVersion, sellerCheckId, null);
    }
}
