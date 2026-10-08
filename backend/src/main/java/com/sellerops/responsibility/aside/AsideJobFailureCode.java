package com.sellerops.responsibility.aside;

import java.util.Set;

/**
 * <b>Where a screen read stopped — one of these words, or nothing.</b>
 *
 * <p>{@link AsideJobOutcome} says what became of the job. This says where it stopped, and the two are not the
 * same question: on 2026-10-08 the first historical catch-up reported {@code SURFACE_UNREADABLE}, which is
 * also what a missing grid reports, and what a refused reading reports. The actual cause was a selector that
 * was not a selector. Three different fixes behind one word, and the operator had to read a helper log to
 * tell them apart.
 *
 * <p><b>A closed set, validated on the way in.</b> The report endpoint takes a string and keeps it only if it
 * is one of these, so the column cannot become a channel for text from a helper — the same posture as the
 * content digest, which is kept only if it is 64 hex characters.
 *
 * <p>Kept as a string set rather than an enum because the helper's vocabulary may grow a word before this
 * backend knows it, and the honest answer for an unknown word is to drop it, not to refuse the report of a
 * job that really did stop.
 */
public final class AsideJobFailureCode {

    /** What the NAVER historical window lane can say. Each one is a different thing to go and fix. */
    public static final Set<String> KNOWN = Set.of(
            "SURFACE_UNEXPECTED",
            "DATE_CONTROL_CANDIDATES_UNREADABLE",
            "RANGE_CONTROLS_NOT_FOUND",
            "RANGE_CONTROLS_AMBIGUOUS",
            "QUERY_CONTROL_NOT_FOUND",
            "QUERY_CONTROL_AMBIGUOUS",
            "RANGE_ORDER_UNKNOWN",
            "RANGE_NOT_SETTABLE",
            "RANGE_MISMATCH",
            "READ_UNSETTLED",
            "READING_REFUSED",
            "WINDOW_INVALID",
            "RUNTIME_FAULT");

    private AsideJobFailureCode() {
    }

    /** The word, or null for anything this backend does not recognise. Never throws: the report still lands. */
    public static String of(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return KNOWN.contains(trimmed) ? trimmed : null;
    }
}
