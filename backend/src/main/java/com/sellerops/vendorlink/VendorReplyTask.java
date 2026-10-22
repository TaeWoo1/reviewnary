package com.sellerops.vendorlink;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>This PC's copy of one reply-delegation task</b> — contract {@code docs/vendor_server_contract_v1.md} §4. The server
 * holds the state of record; this is the last state the PC received, enough to keep its own reply road shut while the
 * task is open and to record a delegate's {@code POSTED} once.
 */
@Getter
@Setter
@Entity
@Table(name = "vendor_reply_task")
public class VendorReplyTask extends BaseEntity {

    /** The server's terminal states (contract §4.2). {@code UNKNOWN} is not one: it waits for the delegate. */
    public static final Set<String> ENDED = Set.of("POSTED", "FAILED", "CANCELLED", "INVALIDATED");

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "vendor_task_id", nullable = false, updatable = false)
    private UUID vendorTaskId;

    @Column(name = "vendor_store_id", nullable = false, updatable = false)
    private UUID vendorStoreId;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "state_version", nullable = false)
    private int stateVersion;

    @Column(name = "draft_version", nullable = false)
    private int draftVersion;

    @Column(name = "draft_fingerprint", nullable = false, length = 64)
    private String draftFingerprint;

    @Column(name = "approved_fingerprint", length = 64)
    private String approvedFingerprint;

    @Column(name = "edited_on_mobile", nullable = false)
    private boolean editedOnMobile;

    /**
     * The draft version on this PC that holds the text the seller approved for this task — the phone's edit if there
     * was one. Set when this PC first sees the task approved; what past-reply search and Answer Memory read.
     */
    @Column(name = "approved_draft_version")
    private Integer approvedDraftVersion;

    /** When a delegate's POSTED was recorded in this PC's reply lane; null until then. */
    @Column(name = "outcome_recorded_at")
    private Instant outcomeRecordedAt;
}
