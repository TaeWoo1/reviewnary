package com.sellerops.autocheck;

/**
 * <b>Why the automatic check is standing still — and who ends it.</b>
 *
 * <p>Neither value is a refusal and neither touches the setting. They differ in exactly one way, and that way
 * is the product behaviour: one is waited out, the other is waited <em>for</em>.
 */
public enum AutoCheckPause {

    /**
     * <b>No usable helper.</b> The seller's Mac is off, the helper was uninstalled, its token reached its 180th
     * day, or they revoked the device. Nothing for a person to do and nothing to tell them: the next tick
     * resolves a desk again and the check carries on by itself the moment one is there.
     */
    PAUSED_DEVICE,

    /**
     * <b>The channel's own sign-in wall.</b> The window that met it is remembered, not skipped.
     *
     * <p><b>Not retried on the timer.</b> A wall does not open with time, and a lane that re-walked into it
     * every hour would be a product generating noise about a thing only the seller can resolve. It is the
     * seller signing in — in their own browser, with their own credentials, their own MFA — that continues the
     * same window, as the same run, through the one resume event
     * ({@code ReviewCatchUpOrchestrator#resumeAfterSignIn}).
     */
    PAUSED_AUTH
}
