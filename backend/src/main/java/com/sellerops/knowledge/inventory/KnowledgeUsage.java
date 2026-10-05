package com.sellerops.knowledge.inventory;

import java.time.Instant;

/**
 * <b>How much a single knowledge document has been used as a draft's basis, and when last.</b>
 *
 * <p><b>Read this narrowly.</b> {@code citations} is the number of stored evidence relations that
 * name this document — the rows the two draft lanes already write, one per cited passage per draft
 * version. It is NOT a count of answers sent to customers, of customers who read it, or of drafts a
 * seller approved. A draft rewritten three times leaves the citations of three versions; a draft
 * nobody ever sent leaves its own. {@code lastUsedAt} is the latest of exactly those rows.
 *
 * <p>The screen's wording carries that distinction — the column is 「답변 근거」, not 「사용」 — and
 * {@code KnowledgeInventoryContractTest} holds it, because the moment this number is read as
 * delivery, a seller would believe a sentence was sent on evidence that only ever reached a draft.
 */
public record KnowledgeUsage(long citations, Instant lastUsedAt) {

    public static final KnowledgeUsage NONE = new KnowledgeUsage(0, null);

    /** The two lanes' rows for one document, added — a document cited in both was used in both. */
    public KnowledgeUsage plus(KnowledgeUsage other) {
        if (other == null) {
            return this;
        }
        Instant last = lastUsedAt == null ? other.lastUsedAt
                : other.lastUsedAt == null ? lastUsedAt
                        : lastUsedAt.isAfter(other.lastUsedAt) ? lastUsedAt : other.lastUsedAt;
        return new KnowledgeUsage(citations + other.citations, last);
    }
}
