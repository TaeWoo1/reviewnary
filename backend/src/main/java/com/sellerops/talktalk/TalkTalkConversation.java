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
 * One customer's TalkTalk conversation with one organization. The TalkTalk user value is sealed and found only by
 * {@code sha256(orgId:user)}; it is never an order identity.
 *
 * <p>{@link Owner} is the persisted handover ownership. It blocks automation on its own, because {@code standby} is
 * omitted when a customer clicks a bot button even while a partner owns the thread (handover_v1.md).
 */
@Getter
@Setter
@Entity
@Table(name = "talktalk_conversations")
public class TalkTalkConversation extends BaseEntity {

    public enum Owner { BOT, PARTNER }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    /** The TalkTalk account (partner) this conversation belongs to. Never answered through another partner. */
    @Column(name = "partner_id", nullable = false, length = 64)
    private String partnerId;

    @Column(name = "user_hash", nullable = false, length = 64)
    private String userHash;

    @Column(name = "user_sealed", nullable = false)
    private byte[] userSealed;

    @Column(name = "user_key_id", nullable = false, length = 80)
    private String userKeyId;

    /** 「고객 A1B2」 — a hash prefix, the only name a screen shows. */
    @Column(nullable = false, length = 16)
    private String alias;

    @Enumerated(EnumType.STRING)
    @Column(name = "thread_owner", nullable = false, length = 8)
    private Owner threadOwner = Owner.BOT;

    @Column(name = "thread_owner_at")
    private Instant threadOwnerAt;

    /** The last automation decision, as a code (AUTO_QUEUED, or why a person must answer). */
    @Column(name = "automation_note", length = 60)
    private String automationNote;

    @Column(name = "needs_human", nullable = false)
    private boolean needsHuman;

    @Column(name = "human_reason", length = 60)
    private String humanReason;

    @Column(name = "last_product_name", length = 300)
    private String lastProductName;

    @Column(name = "last_inbound_at")
    private Instant lastInboundAt;

    @Column(name = "last_outbound_at")
    private Instant lastOutboundAt;

    @Column(name = "last_auto_at")
    private Instant lastAutoAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Override
    public String toString() {
        return "TalkTalkConversation(id=" + getId() + ", alias=" + alias + ", owner=" + threadOwner + ")";
    }
}
