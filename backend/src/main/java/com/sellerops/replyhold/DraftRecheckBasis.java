package com.sellerops.replyhold;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** The basis an org's waiting drafts were last swept under (V165): the same basis is not swept twice. */
@Getter
@Setter
@Entity
@Table(name = "draft_recheck_basis")
public class DraftRecheckBasis {

    @Id
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 300)
    private String basis;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
}
