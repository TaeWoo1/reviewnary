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

/** One version of a reply prepared for a conversation. The head (highest version) is what a send must name. */
@Getter
@Setter
@Entity
@Table(name = "talktalk_drafts")
public class TalkTalkDraft extends BaseEntity {

    public enum AuthorKind { GUIDANCE, MODEL, SELLER_EDIT }

    public enum Basis { GROUNDED, NEEDS_HUMAN, SELLER_WRITTEN }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "trigger_message_id")
    private UUID triggerMessageId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_kind", nullable = false, length = 16)
    private AuthorKind authorKind;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    @Column(name = "content_fingerprint", nullable = false, length = 64)
    private String contentFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Basis basis;

    /** Comma-separated hold codes; empty when nothing holds the draft. */
    @Column(name = "hold_reasons", length = 400)
    private String holdReasons;

    /** The cited sources as JSON: kind, id, title, content fingerprint (no text). */
    @Column(name = "evidence_json", nullable = false, columnDefinition = "text")
    private String evidenceJson;

    @Column(name = "guidance_id")
    private UUID guidanceId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "purged_at")
    private Instant purgedAt;
}
