package com.sellerops.reviewimport;

/**
 * The conclusion about a segment's DATA — the coverage axis, kept strictly separate from
 * {@link SegmentExecutionState}. These are the only three values (decision, 2026-07-24):
 *
 * <ul>
 *   <li>{@code UNVERIFIED} — not yet covered: no successful export has reconciled this range (initial).</li>
 *   <li>{@code COVERED} — the segment's scope was exported and ingested successfully. Includes a valid
 *       EMPTY export (COVERED with zero rows). "Covered" means the SCOPE was exported successfully; it is
 *       NOT by itself a claim that every expected row was reconciled — completeness is tracked separately
 *       (a segment's {@code rows_reconciled} flag, true only when the file matched the screen's stated
 *       total). An export that did not match that total never reaches COVERED.</li>
 *   <li>{@code MISSING} — a coverage conclusion that this range cannot be covered: earlier than the
 *       earliest date NAVER lets the seller select, or repeatedly unreconcilable. It is surfaced honestly
 *       and the segment stays reachable; it is NOT the same as a {@link SegmentExecutionState#FAILED} attempt.</li>
 * </ul>
 */
public enum SegmentCoverageState {
    UNVERIFIED,
    COVERED,
    MISSING
}
