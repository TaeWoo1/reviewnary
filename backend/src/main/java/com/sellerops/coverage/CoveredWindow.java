package com.sellerops.coverage;

import java.time.LocalDate;

/**
 * <b>One stretch of days this organisation has actually read, end to end.</b>
 *
 * <p>Inclusive on both ends, and KST dates — the marketplace states its periods in the seller's own days, and a
 * boundary kept as an instant would move by nine hours on the way in or out.
 *
 * @param start the first day covered
 * @param end   the last day covered
 * @param source which lane proved it — kept so a plan can explain itself, never to rank one lane over another
 */
public record CoveredWindow(LocalDate start, LocalDate end, CoverageSource source) {

    public CoveredWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("a covered window needs both ends");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("a covered window cannot end before it starts");
        }
    }

    /** Where the proof came from. */
    public enum CoverageSource {
        /** A guided period import whose segment reconciled to COVERED. */
        GUIDED_IMPORT,
        /** A screen read that stated its period and did not reach its own row ceiling. */
        SCREEN_READ,
        /** Two or more of the above, merged. */
        MERGED
    }

    public long days() {
        return end.toEpochDay() - start.toEpochDay() + 1;
    }

    /** Whether {@code day} lies inside this window. */
    public boolean covers(LocalDate day) {
        return !day.isBefore(start) && !day.isAfter(end);
    }

    /**
     * Whether this window and {@code other} can be read as one stretch — overlapping, or merely touching.
     *
     * <p>Touching counts: 07-31 and 08-01 leave no day unread between them, and treating them as two stretches
     * would invent a gap out of the fact that two imports were run on different evenings.
     */
    public boolean joins(CoveredWindow other) {
        return !start.isAfter(other.end.plusDays(1)) && !other.start.isAfter(end.plusDays(1));
    }

    public CoveredWindow mergedWith(CoveredWindow other) {
        return new CoveredWindow(start.isBefore(other.start) ? start : other.start,
                end.isAfter(other.end) ? end : other.end,
                source == other.source ? source : CoverageSource.MERGED);
    }
}
