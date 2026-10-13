package com.sellerops.coverage.catchup;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>How far back one store's review history should be read — a recorded choice, never a guess.</b>
 *
 * <p>The catch-up walk used to plan only forward from the coverage boundary, and refused to plan at all without
 * one, because a start it invented would be indistinguishable downstream from a measured one. That refusal still
 * holds for a <i>guessed</i> start. This answers with a <i>recorded</i> one: the seller's automatic-check setting
 * carries {@code history_from}, filled in by the product on their behalf (product-owner decision 2026-10-13:
 * one year, set automatically) and changeable by them.
 *
 * <p>An interface in this package rather than a dependency on the setting, so the walk does not learn about the
 * setting's package and a context without that lane simply plans no history ({@link #NONE}).
 */
@FunctionalInterface
public interface ReviewHistoryHorizon {

    /** No history is read backwards. The behaviour before 2026-10-13, and what a context without the lane gets. */
    ReviewHistoryHorizon NONE = (orgId, sellerAccountId) -> Optional.empty();

    /** The first KST day this store's history should reach, or empty when none was recorded. */
    Optional<LocalDate> historyFrom(UUID orgId, UUID sellerAccountId);
}
