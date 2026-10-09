package com.sellerops.autocheck;

/**
 * <b>What the settings screen shows about automatic review checking — three words, no vocabulary.</b>
 *
 * <p>No grant, no permission, no device, no scope, no interval. A seller sees whether it is on, and — only when
 * it is standing still — one sentence about why. The pause reason crosses as its closed token so the frontend
 * owns the sentence; everything else about the row (who switched it on, when it is next due, which recipe) is
 * not a thing a seller has any use for.
 *
 * @param supported whether this account's channel has a review screen the product can read at all. False means
 *                  the setting is not drawn — never that it is off, which would read as a choice someone made
 * @param enabled   whether the seller currently wants automatic checking
 * @param paused    {@code PAUSED_DEVICE} / {@code PAUSED_AUTH}, or null while it is simply working
 */
public record ReviewAutoCheckView(boolean supported, boolean enabled, String paused) {

    public static final ReviewAutoCheckView UNSUPPORTED = new ReviewAutoCheckView(false, false, null);

    public static ReviewAutoCheckView of(ReviewAutoCheck row) {
        return new ReviewAutoCheckView(true, row.on(),
                row.on() && row.getPausedReason() != null ? row.getPausedReason().name() : null);
    }
}
