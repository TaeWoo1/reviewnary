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
 * One webhook delivery, durably accepted before the 200 is returned. Only normalized fields are kept; the raw body
 * leaves only its SHA-256. The protocol has no event id, so {@code bodyHash} is evidence for a duplicate, never a key.
 */
@Getter
@Setter
@Entity
@Table(name = "talktalk_events")
public class TalkTalkEvent extends BaseEntity {

    public enum State { ACCEPTED, PROCESSING, PROCESSED, IGNORED, FAILED }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "connection_generation", nullable = false)
    private int connectionGeneration;

    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "body_hash", nullable = false, length = 64)
    private String bodyHash;

    @Column(name = "user_hash", length = 64)
    private String userHash;

    @Column(name = "user_sealed")
    private byte[] userSealed;

    @Column(name = "user_key_id", length = 80)
    private String userKeyId;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    @Column(name = "input_type", length = 24)
    private String inputType;

    @Column(nullable = false)
    private boolean standby;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "thread_owner_id")
    private Long threadOwnerId;

    @Column(name = "handover_control", length = 24)
    private String handoverControl;

    @Column(name = "handover_target")
    private Long handoverTarget;

    @Column(name = "product_name", length = 300)
    private String productName;

    @Column(name = "trusted_source", nullable = false)
    private boolean trustedSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Column(length = 60)
    private String note;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "purged_at")
    private Instant purgedAt;
}
