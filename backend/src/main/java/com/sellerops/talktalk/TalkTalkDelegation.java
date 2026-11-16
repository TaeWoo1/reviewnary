package com.sellerops.talktalk;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One owner decision about the TalkTalk automatic guidance — append-only; the latest row is the current state.
 * This row is the record server_operation_boundary_v1 §3.2-2 requires: the seller approved the condition itself.
 */
@Getter
@Setter
@Entity
@Table(name = "talktalk_delegations")
public class TalkTalkDelegation extends BaseEntity {

    public static final String SCOPE = "HOLIDAY_AND_CUTOFF_GUIDANCE";

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 40)
    private String scope = SCOPE;

    @Column(name = "guidance_id")
    private UUID guidanceId;

    @Column(name = "guidance_fingerprint", length = 64)
    private String guidanceFingerprint;

    @Column(name = "preview_fingerprint", length = 64)
    private String previewFingerprint;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;
}
