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
 * One organization's TalkTalk chatbot-API connection (docs/inquiry/talktalk_chat_lane_v1.md §3).
 *
 * <p>The send-API key is sealed and opened only by the backend; the webhook path token is stored as a hash and shown
 * once. {@link Mode} is recorded on the row so a fake connection can never be read as live support.
 */
@Getter
@Setter
@Entity
@Table(name = "talktalk_connections")
public class TalkTalkConnection extends BaseEntity {

    /** FAKE — sends are recorded by the in-process fake and leave nothing; LIVE — the official send API. */
    public enum Mode { FAKE, LIVE }

    public enum Status { CONNECTED, DISCONNECTED }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "partner_id", nullable = false, length = 64)
    private String partnerId;

    @Column(name = "send_key_sealed", nullable = false)
    private byte[] sendKeySealed;

    @Column(name = "send_key_key_id", nullable = false, length = 80)
    private String sendKeyKeyId;

    @Column(name = "webhook_token_hash", nullable = false, length = 64)
    private String webhookTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", nullable = false, length = 8)
    private Mode transportMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /**
     * Raised on every connect, reconnect, webhook rotation and disconnect. Events and sends are bound to the generation
     * they were made under; a send whose generation is not the connection's current one is never made (review item
     * 10: a reconnect with another partner or key must not lend its credentials to older work).
     */
    @Column(nullable = false)
    private int generation = 1;

    /** The first event that came through the documented trust boundary in THIS generation. */
    @Column(name = "first_trusted_event_at")
    private Instant firstTrustedEventAt;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "connected_by")
    private UUID connectedBy;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    @Override
    public String toString() {
        return "TalkTalkConnection(id=" + getId() + ", orgId=" + orgId + ", mode=" + transportMode + ", status=" + status
                + ", sendKey=<sealed>)";
    }
}
