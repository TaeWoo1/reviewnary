package com.sellerops.inquiry.sellercheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * The seller's correction of what kind of inquiry this is (V178). Append-only: a new one supersedes the standing one,
 * which is otherwise never changed. {@code judgedKind} is what the screen showed as the service's reading when the
 * seller corrected it.
 */
@Getter
@Setter
@Entity
@Table(name = "inquiry_kind_correction")
public class InquiryKindCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private String kind;

    @Column(name = "judged_kind", updatable = false, length = 16)
    private String judgedKind;

    @Column(name = "corrected_by", nullable = false, updatable = false, length = 120)
    private String correctedBy;

    @Column(name = "corrected_at", nullable = false, updatable = false)
    private Instant correctedAt;

    /** The one field that changes: set once, when a later correction takes this one's place. */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @PrePersist
    void onCreate() {
        if (correctedAt == null) {
            correctedAt = Instant.now();
        }
    }
}
