package com.sellerops.responsibility.aside;

import java.util.UUID;

/**
 * <b>Whether this organisation's seller has asked for this store's screen to be read automatically.</b>
 *
 * <p>The seam exists so that the one choke point every helper job passes through
 * ({@link ScheduledAsideJobService#dispatch}) can ask the question without knowing where the answer is kept.
 * The answer lives in a product setting the seller owns ({@code review_auto_check}); this package must not
 * learn that table, for the same reason it holds no opinion about the catch-up lane that drives it.
 *
 * <p><b>Absent means refused.</b> A deployment assembled without an implementation has no seller setting to
 * read, so a {@link AsideTrigger#SCHEDULED} dispatch in it is a read nobody asked for — and the lane fails
 * closed rather than falling back to «the deployment probably meant yes».
 */
public interface AutoCheckAuthority {

    /**
     * Is automatic reading of this account's screen currently on, for what this recipe reads?
     *
     * <p>A verdict about <b>now</b>: a setting the seller turned off a minute ago answers false a minute later,
     * because the row is read on each ask and nothing is cached.
     */
    boolean allows(UUID orgId, UUID sellerAccountId, AsideRecipe recipe);
}
