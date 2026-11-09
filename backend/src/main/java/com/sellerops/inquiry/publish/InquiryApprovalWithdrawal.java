package com.sellerops.inquiry.publish;

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
 * An approval the seller took back before the answer was registered (V177). Written once, never changed: the approval
 * as it stood, field for field, what the send said at that moment, and why it was taken back. The standing approval, its
 * intent and its execution are cleared after this row is written, in the same transaction.
 */
@Getter
@Setter
@Entity
@Table(name = "inquiry_approval_withdrawn")
public class InquiryApprovalWithdrawal {

    /** The approved version was written from another record than the one that stands. */
    public static final String SELLER_CHECK_CHANGED = "SELLER_CHECK_CHANGED";
    /**
     * Held at registration for something a person must fix — a policy conflict, a forbidden phrase, a changed figure —
     * and taken back to be fixed (2026-11-05 tenth review). Taking it back resolves nothing: the reply is checked again.
     */
    public static final String HELD_FOR_FIX = "HELD_FOR_FIX";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Column(name = "approval_id", nullable = false, updatable = false)
    private UUID approvalId;

    @Column(name = "approved_draft_version", nullable = false, updatable = false)
    private int approvedDraftVersion;

    @Column(name = "approved_fingerprint", nullable = false, updatable = false, length = 64)
    private String approvedFingerprint;

    @Column(name = "approval_command_id", nullable = false, updatable = false, length = 120)
    private String approvalCommandId;

    @Column(name = "approver", nullable = false, updatable = false, length = 120)
    private String approver;

    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    @Column(name = "seller_account_id", updatable = false)
    private UUID sellerAccountId;

    @Column(name = "channel_id", updatable = false)
    private UUID channelId;

    @Column(name = "target_external_id", updatable = false, length = 200)
    private String targetExternalId;

    @Column(name = "source_subtype", updatable = false, length = 32)
    private String sourceSubtype;

    @Column(name = "action_kind", updatable = false, length = 40)
    private String actionKind;

    @Column(name = "execution_status", updatable = false, length = 32)
    private String executionStatus;

    @Column(name = "execution_failure_reason", updatable = false, length = 40)
    private String executionFailureReason;

    @Column(name = "written_from_check_id", updatable = false)
    private UUID writtenFromCheckId;

    /** The record that stood when it was taken back; null for an answer held for another reason with no record (V180). */
    @Column(name = "standing_check_id", updatable = false)
    private UUID standingCheckId;

    /** The hold's codes when it was taken back for a hold a person must fix (V180); null for a changed record. */
    @Column(name = "hold_codes", updatable = false, length = 400)
    private String holdCodes;

    @Column(name = "reason", nullable = false, updatable = false, length = 40)
    private String reason;

    @Column(name = "seller_confirmed_not_registered", nullable = false, updatable = false)
    private boolean sellerConfirmedNotRegistered;

    @Column(name = "channel_read_at", updatable = false)
    private Instant channelReadAt;

    @Column(name = "withdrawn_by", nullable = false, updatable = false, length = 120)
    private String withdrawnBy;

    @Column(name = "withdrawn_at", nullable = false, updatable = false)
    private Instant withdrawnAt;

    @PrePersist
    void onCreate() {
        if (withdrawnAt == null) {
            withdrawnAt = Instant.now();
        }
    }
}
