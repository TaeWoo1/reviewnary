package com.sellerops.knowledge.currency;

import com.sellerops.common.BaseEntity;
import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>「이 자료가 저 자료를 대신함」 — for one attribute under one condition, never for the whole document</b> (owner decision
 * 1, 2026-11-14; {@code DESIGN_conflict.md} §2). A new shipping notice replacing an old one's delivery days leaves the old
 * one's return rule standing.
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_supersession")
public class KnowledgeSupersession extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "newer_source_id", nullable = false)
    private UUID newerSourceId;

    @Column(name = "older_source_id", nullable = false)
    private UUID olderSourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribute", nullable = false, length = 40)
    private ClaimAttribute attribute;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_condition", nullable = false, length = 40)
    private ClaimCondition condition = ClaimCondition.GENERAL;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;
}
