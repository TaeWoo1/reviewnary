package com.sellerops.knowledge.inventory;

import java.time.Instant;
import java.util.UUID;

/**
 * One knowledge document's citations <b>of one kind</b>, as the two draft lanes recorded them.
 *
 * <p>The kind travels with the count because the screen asks two different questions of the same
 * rows: how often a document has grounded a draft, and how many of the company's ORG_POLICY
 * citations name a rule that is no longer in the list. Folding the kind away at read time would make
 * the second question need its own query over the same table.
 *
 * <p>{@code citations} is a count of stored evidence relations — see {@link KnowledgeUsage} for what
 * that may and may not be read as.
 */
public record KnowledgeCitation(UUID sourceId, String kind, long citations, Instant lastUsedAt) {
}
