package com.sellerops.vendorlink;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>One vendor-server store, bound to the organisation on this PC whose seller pulled it first</b> — contract
 * {@code docs/vendor_server_contract_v1.md} §3.2. A binding is made only by a seller's own pull; the scheduled pull
 * follows bindings and never makes one.
 */
@Getter
@Setter
@Entity
@Table(name = "vendor_store_binding")
public class VendorStoreBinding extends BaseEntity {

    @Column(name = "vendor_store_id", nullable = false, updatable = false)
    private UUID vendorStoreId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private UUID channelId;

    /** The label the vendor operator gave the store; not a channel identifier. */
    @Column(name = "store_label", nullable = false, length = 200)
    private String storeLabel;

    /** The last feed seq this PC finished storing. Pulls resume after it. */
    @Column(name = "review_cursor", nullable = false)
    private long reviewCursor;

    /** The last feed seq the server confirmed this PC's {@code ack} for (V138). Behind the cursor, the ack is owed. */
    @Column(name = "acked_cursor", nullable = false)
    private long ackedCursor;

    /** The last reply-task change seq this PC applied (V137). */
    @Column(name = "task_cursor", nullable = false)
    private long taskCursor;

    /** The draft fingerprints this PC last reported for the store (policy-check); null before the first report. */
    @Column(name = "reported_draft_basis", length = 200)
    private String reportedDraftBasis;

    /** When this PC last asked the server for this store's reviews and got an answer. */
    @Column(name = "last_pulled_at")
    private Instant lastPulledAt;

    /** The seller stopped receiving this store (V139): no pull touches it until they start it again. */
    @Column(name = "paused", nullable = false)
    private boolean paused;

    /** When a delegate last uploaded this store's export, as the server last said (V139) — the age of the source. */
    @Column(name = "last_upload_at")
    private Instant lastUploadAt;

    /** Why the last pull of this store failed — a closed code — or null after one that succeeded (V139). */
    @Column(name = "last_failure", length = 32)
    private String lastFailure;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;
}
