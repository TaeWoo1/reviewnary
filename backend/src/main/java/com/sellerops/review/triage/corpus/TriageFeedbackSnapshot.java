package com.sellerops.review.triage.corpus;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One cut: that it happened, what it was made of, and under which version string it may be quoted.
 *
 * <p><b>It holds no members.</b> A member row is identified by the {@code snapshot_version} stamp it already
 * carries, so this row is a manifest and not an index — the same reason {@code ReviewDeliveryTruthReader} merges
 * two ledgers at read time instead of writing a third: the copy is what drifts.
 *
 * <p><b>Append-only, by trigger.</b> Nothing in this package updates or deletes one, and V130 refuses it at the
 * database. An evaluation set that changes after a metric was computed against it makes the metric meaningless
 * ({@code docs/slices/production-triage-feedback-draft-v1.md} §3), and that is a property of the row rather than
 * of whichever code path happened to write it.
 */
@Getter
@Setter
@Entity
@Table(name = "triage_feedback_snapshot")
public class TriageFeedbackSnapshot extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SnapshotKind kind;

    /** The cutter's own version string. Unique per org across BOTH kinds — see V130 and {@link SnapshotKind}. */
    @Column(nullable = false, length = 40)
    private String version;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    /**
     * The classifier this correction set was gathered against, when every underlying prediction agrees on one.
     *
     * <p>Null is a real and frequent answer, and it is not a missing field: a correction made on a tier the RULE
     * produced has no prediction behind it (the pilot's own case, RUBRIC v2 §13.7), and a set spanning two
     * classifier versions has no single one to name. Reporting one of the two would make the set look measurable
     * against a version half of it never saw.
     */
    @Column(name = "classifier_version", length = 160)
    private String classifierVersion;

    @Column(name = "prompt_hash", length = 64)
    private String promptHash;

    @Column(name = "cut_by")
    private UUID cutBy;

    @Column(name = "cut_at", nullable = false)
    private Instant cutAt;

    /** The cutter's own sentence. Operator-authored — never customer content. */
    @Column(columnDefinition = "text")
    private String note;
}
