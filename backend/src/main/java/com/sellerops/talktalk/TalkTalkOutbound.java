package com.sellerops.talktalk;

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
 * One thing this service asks TalkTalk to do — a reply, an automatic guidance, a handover.
 *
 * <p><b>Statuses never overstate.</b> FAKE_RECORDED: the fake transport kept it, nothing left the server.
 * API_ACCEPTED: the send API answered {@code success=true, resultCode=00} — not the customer receiving or reading it.
 * UNKNOWN: the result could not be read; never retried automatically (the protocol has no idempotency key).
 */
@Getter
@Setter
@Entity
@Table(name = "talktalk_outbound")
public class TalkTalkOutbound extends BaseEntity {

    public enum Kind { MANUAL_REPLY, AUTO_GUIDANCE, HANDOVER_PASS, HANDOVER_TAKE }

    public enum Status { QUEUED, SENDING, FAKE_RECORDED, API_ACCEPTED, FAILED, UNKNOWN, CANCELLED, BLOCKED }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    @Column(name = "content_fingerprint", nullable = false, length = 64)
    private String contentFingerprint;

    @Column(name = "command_id", nullable = false, length = 80)
    private String commandId;

    @Column(name = "draft_id")
    private UUID draftId;

    @Column(name = "draft_version")
    private Integer draftVersion;

    @Column(name = "trigger_message_id")
    private UUID triggerMessageId;

    @Column(name = "guidance_id")
    private UUID guidanceId;

    @Column(name = "guidance_fingerprint", length = 64)
    private String guidanceFingerprint;

    @Column(name = "delegation_id")
    private UUID delegationId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", nullable = false, length = 8)
    private TalkTalkConnection.Mode transportMode;

    /** The connection generation this send was made under; any other generation blocks it. */
    @Column(name = "connection_generation", nullable = false)
    private int connectionGeneration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "result_code", length = 16)
    private String resultCode;

    /** For a resend after UNKNOWN: the outbound the seller confirmed did not arrive. */
    @Column(name = "acknowledged_unknown_of")
    private UUID acknowledgedUnknownOf;

    /** The hold codes of a seller-written draft the seller confirmed sending as they are (the one-off exception). */
    @Column(name = "hold_confirmed", length = 400)
    private String holdConfirmed;

    @Column(name = "echo_seen_at")
    private Instant echoSeenAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    public boolean finished() {
        return status != Status.QUEUED && status != Status.SENDING;
    }
}
