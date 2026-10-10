package com.sellerops.review.triage.corpus;

import java.time.Instant;
import java.util.List;

/**
 * A cut correction set as it leaves the product — the manifest and its rows in one document.
 *
 * <p><b>{@link #usage} travels with the rows, and that is the point.</b>
 * {@code docs/slices/production-triage-feedback-draft-v1.md} §3 lists three uses and three refusals for this
 * set; a file that carried the rows without them would be handed to someone next quarter who had no way of
 * knowing it is not gold. The gold set does the same thing with its own {@code _comment} block, for the same
 * reason.
 *
 * @param contract            {@code triage-feedback-corpus/v1} — stamped so a reader can tell which shape they
 *                            hold without guessing from the fields
 * @param rowCount            the manifest's count, which is {@code rows.size() + withheldRowCount}. Reported
 *                            rather than recomputed from {@code rows}: a set that quietly exported fewer rows
 *                            than it was cut with would report a better metric over a smaller population
 * @param withheldRowCount    members that were cut but cannot be exported, because the review carries no
 *                            well-formed channel-side id to fingerprint. Named, never dropped silently
 */
public record TriageCorpusDocument(String contract, String kind, String kindLabelKo, String version,
                                   Instant cutAt, int rowCount, int withheldRowCount, String classifierVersion,
                                   String promptHash, String note, List<String> usage,
                                   List<TriageCorpusRow> rows) {

    public static final String CONTRACT = "triage-feedback-corpus/v1";

    /**
     * What this set may and may not be used for — §3, verbatim in substance, in the file itself.
     *
     * <p>Not a comment and not documentation: a {@code List<String>} on the exported object, so it is present in
     * every copy anybody makes of it.
     */
    public static final List<String> USAGE = List.of(
            "MAY: measure a candidate classifier, alongside — never instead of — the human gold set.",
            "MAY: locate where the current rule fails, as a source of hypotheses.",
            "MAY: trigger a drift audit when its error rate moves.",
            "MUST NOT become gold. Every row is one seller's judgment on their own review, made while looking "
                    + "at the model's answer (review-eval RUBRIC §7.1).",
            "MUST NOT be used for fine-tuning or few-shot selection without a separate, explicit decision that "
                    + "names this version.",
            "MUST NOT grow. This cut is frozen; a later cut is a different version string.",
            "Drift is confirmed on a freshly labeled audit sample, never on the correction stream itself.");
}
