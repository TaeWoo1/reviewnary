package com.sellerops.inquiry.publish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * The seller closed 「처리는 완료됐지만 고객에게는 … 안내했어요」 for one record (V180). Written once, never changed: the same
 * record never brings the check back; a later record can. Closing sends nothing.
 */
@Getter
@Setter
@Entity
@Table(name = "inquiry_told_behind_closure",
        uniqueConstraints = @UniqueConstraint(name = "uq_inquiry_told_behind_closure",
                columnNames = {"work_item_id", "check_id"}))
public class InquiryToldBehindClosure {

    /** The seller told the customer some other way (a call, a message on the seller centre). */
    public static final String TOLD_SEPARATELY = "TOLD_SEPARATELY";
    /** Nothing more needs saying. */
    public static final String NOT_NEEDED = "NOT_NEEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Column(name = "check_id", nullable = false, updatable = false)
    private UUID checkId;

    @Column(name = "closure", nullable = false, updatable = false, length = 20)
    private String closure;

    @Column(name = "closed_by", nullable = false, updatable = false, length = 120)
    private String closedBy;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @PrePersist
    void onCreate() {
        if (closedAt == null) {
            closedAt = Instant.now();
        }
    }
}
