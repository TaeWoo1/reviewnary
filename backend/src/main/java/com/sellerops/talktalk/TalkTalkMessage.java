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

/** One line of a conversation, as the screen shows it. Text is purged after the retention period. */
@Getter
@Setter
@Entity
@Table(name = "talktalk_messages")
public class TalkTalkMessage extends BaseEntity {

    /**
     * CUSTOMER — the customer; SERVICE — this service's own send; PARTNER_CENTER — an agent in 파트너센터 (echo
     * sourceId 1); BOT_ECHO — an echo of a bot message that matches none of our sends.
     */
    public enum Author { CUSTOMER, SERVICE, PARTNER_CENTER, BOT_ECHO }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "outbound_id")
    private UUID outboundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Author author;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    @Column(name = "input_type", length = 24)
    private String inputType;

    @Column(nullable = false)
    private boolean standby;

    @Column(name = "duplicate_suspect", nullable = false)
    private boolean duplicateSuspect;

    @Column(name = "body_hash", length = 64)
    private String bodyHash;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "purged_at")
    private Instant purgedAt;
}
