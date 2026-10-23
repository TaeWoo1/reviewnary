package com.sellerops.attention.reply.dto;

/**
 * One phrase the review panel marks in the saved draft (owner decision 2026-10-23, the approved draft-panel mockup).
 *
 * @param kind        {@code FORBIDDEN} — the company never uses it; the reply is fixed before it is approved or sent —
 *                    or {@code AVOID} — a preference; the seller may keep it and approve
 * @param phrase      the seller's own phrase, as they listed it
 * @param replacement what to write instead, for {@code AVOID}; null when none was given
 * @param sentence    the draft's own sentence that carries it, or null when it could not be cut out
 */
public record ReviewReplyPhraseFindingView(String kind, String phrase, String replacement, String sentence) {
}
