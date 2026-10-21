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

    @Column(name = "last_pulled_at")
    private Instant lastPulledAt;
}
