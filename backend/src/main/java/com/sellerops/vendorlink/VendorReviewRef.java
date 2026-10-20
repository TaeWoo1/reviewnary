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
 * <b>A local review and the vendor server's record of it</b> — contract §3.2 · §4.3. Holds what the review lane has no
 * column for: the server's review id and body fingerprint (a reply task names both), the display state (hidden or
 * removed shows only there) and the export's one attachment address.
 */
@Getter
@Setter
@Entity
@Table(name = "vendor_review_ref")
public class VendorReviewRef extends BaseEntity {

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "vendor_store_id", nullable = false, updatable = false)
    private UUID vendorStoreId;

    @Column(name = "vendor_review_id", nullable = false, updatable = false)
    private UUID vendorReviewId;

    @Column(name = "body_fingerprint", nullable = false, length = 64)
    private String bodyFingerprint;

    @Column(name = "display_state", length = 40)
    private String displayState;

    @Column(name = "attachment_url", length = 1000)
    private String attachmentUrl;

    @Column(name = "attachment_kind", length = 16)
    private String attachmentKind;

    /** When the seller's own 'no reply' for this review was told to the server (V137); null until then. */
    @Column(name = "no_reply_reported_at")
    private Instant noReplyReportedAt;
}
