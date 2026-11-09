package com.sellerops.replyhold;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * The seller read a hold's reasons and went on (V166; 2026-11-01 review: a changed source or an unproven figure is shown,
 * and goes on with an explicit confirmation that is recorded). Bound to one version, one basis, one approval state and
 * one finding: a new policy change or a different finding asks again. A banned phrase, a policy conflict or a condition
 * that must not apply takes no decision — {@link ReplyHoldService#decide} refuses it — and a promise nothing backs takes
 * only an {@link ReplyHoldService.Decision#EXCEPTION}, which names its words ({@code excepted}; V167). Append-only.
 */
@Getter
@Setter
@Entity
@Table(name = "reply_hold_confirmation")
public class ReplyHoldConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReplyDraftHold.Lane lane;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "draft_version", nullable = false)
    private int draftVersion;

    @Column(nullable = false)
    private boolean approved;

    @Column(nullable = false, length = 200)
    private String codes;

    @Column(name = "finding_key", nullable = false, length = 64)
    private String findingKey;

    @Column(nullable = false, length = 300)
    private String basis;

    /** Rows before V167 are confirmations. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReplyHoldService.Decision decision = ReplyHoldService.Decision.CONFIRM;

    /** For an exception: the unbacked promise words it was decided for, comma-separated. */
    @Column(length = 500)
    private String excepted;

    @Column(name = "confirmed_by", nullable = false, length = 80)
    private String confirmedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
