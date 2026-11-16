package com.sellerops.knowledge.defaults;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One questionnaire answer. The draft is what the seller is typing; the confirmed value is what answers customers.
 * Editing a confirmed answer changes only the draft, so the shop keeps answering with the confirmed value until the
 * seller confirms the new one.
 */
@Getter
@Setter
@Entity
@Table(name = "org_default_policy_answers")
public class DefaultPolicyAnswer extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "item_key", nullable = false, length = 40)
    private String itemKey;

    @Column(name = "draft_number")
    private Integer draftNumber;

    @Column(name = "draft_text", length = 300)
    private String draftText;

    @Column(name = "confirmed_number")
    private Integer confirmedNumber;

    @Column(name = "confirmed_text", length = 300)
    private String confirmedText;

    @Column(nullable = false)
    private boolean confirmed;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
