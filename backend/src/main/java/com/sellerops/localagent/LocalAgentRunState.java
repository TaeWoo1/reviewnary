package com.sellerops.localagent;

import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;

/**
 * <b>What the seller's screen says the local agent is doing, in the words the seller can act on.</b>
 *
 * <p>The job row already records what happened in the vocabulary the substrate needs — status, outcome,
 * identity verdict, delivery completeness. That vocabulary is correct and too wide to put in front of a person:
 * {@code SETTLED} is not an answer to 「됐어?」, and {@code STORE_UNRESOLVED} is not something a seller can fix
 * by being told its name. This enum is the one place that translation happens, so two screens cannot disagree
 * about what a job's state means.
 *
 * <p>The first two values are about the DESK, not about any job, and they are in the same enum deliberately:
 * from the seller's side «there is nobody to ask» and «it is running» are answers to the same question, and a
 * screen that had to combine two unrelated states to find out would get the combination wrong somewhere.
 */
public enum LocalAgentRunState {

    /**
     * No helper is linked to this account, so nothing can be asked. The one thing that helps is linking it
     * once — see {@code AsideHelperDevices} for why this is the device grant and not the browser pairing.
     */
    UNPAIRED,

    /** A helper is linked and idle: a read can be started right now, with no further approval. */
    READY,

    /** Queued or claimed — the helper has work and has not reported yet. */
    RUNNING,

    /** The read happened, its store was proved, and its rows reached canonical storage. */
    SUCCESS,

    /** The read happened and stored rows, but cannot promise nothing older was missed behind its one page. */
    PARTIAL,

    /**
     * The channel's own sign-in wall. The seller signs in on their own browser; nothing in this product can,
     * and nothing in it may step around a sign-in, a 2FA prompt or a CAPTCHA.
     */
    AUTH_REQUIRED,

    /**
     * Everything else that ended without a reading: the page was not this store, the helper never answered, the
     * surface was unreadable, the lease ran out. One value, because the seller's next move is the same for all
     * of them — try again, and if it keeps happening, say so.
     */
    FAILED;

    /** The state of a desk with no job on it. */
    public static LocalAgentRunState idle(boolean helperLinked) {
        return helperLinked ? READY : UNPAIRED;
    }

    /**
     * The state of one job.
     *
     * <p>A job that is still waiting is RUNNING even if the helper has not picked it up yet: from the seller's
     * side «queued on your own machine» and «being read» are the same wait, and the difference is bounded by a
     * TTL they never see. An EXPIRED job never ran, which is the FAILED bucket, not a timeout they can read.
     */
    public static LocalAgentRunState of(ScheduledAsideJob job) {
        if (job == null) {
            return FAILED;
        }
        ScheduledAsideJobStatus status = job.getStatus();
        if (status == ScheduledAsideJobStatus.QUEUED || status == ScheduledAsideJobStatus.CLAIMED) {
            return RUNNING;
        }
        if (status != ScheduledAsideJobStatus.SETTLED) {
            return FAILED;
        }
        AsideJobOutcome outcome = job.getOutcome();
        if (outcome == AsideJobOutcome.AUTH_REQUIRED) {
            return AUTH_REQUIRED;
        }
        if (outcome != AsideJobOutcome.OBSERVED) {
            return FAILED;
        }
        // Observed is not yet delivered. A marketplace read that never passed the store fence stored nothing,
        // and calling that SUCCESS because the page was readable is the exact lie the fence exists to prevent.
        if (job.getRecipe() != null && job.getRecipe().readsMarketplace()) {
            if (job.getIdentityVerdict() != com.sellerops.responsibility.IdentityVerdict.MATCH) {
                return FAILED;
            }
            return job.getDeliveryCompleteness() == com.sellerops.responsibility.SourceCompleteness.PARTIAL
                    ? PARTIAL : SUCCESS;
        }
        return SUCCESS;
    }
}
