package com.sellerops.knowledge.defaults;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the questionnaire screen reads. */
public final class DefaultPolicyViews {

    private DefaultPolicyViews() {
    }

    /**
     * One question. {@code status}: EMPTY (no answer), DRAFT (typed, not confirmed — not used in answers), CONFIRMED,
     * CHANGED (confirmed, and the seller has since typed a different draft; the confirmed value still answers).
     * A {@code shared} item is read from the TalkTalk shipping guidance and changed there.
     */
    public record Item(String key, String group, String groupLabel, String label, String question,
                       String kind, String placeholder, String legal, boolean shared, String status,
                       Integer draftNumber, String draftText, Integer confirmedNumber, String confirmedText,
                       String sentence, String draftSentence, String warning, Instant confirmedAt) {
    }

    /** A document the questionnaire wrote: what customers' answers can stand on. */
    public record Document(String group, String groupLabel, UUID sourceId, String title, boolean active,
                           int version) {
    }

    public record Overview(List<Item> items, int confirmed, int total, List<Document> documents) {
    }

    public record AnswerRequest(Integer number, String text) {
    }
}
