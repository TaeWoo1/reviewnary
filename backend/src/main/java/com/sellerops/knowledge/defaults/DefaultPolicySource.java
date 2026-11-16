package com.sellerops.knowledge.defaults;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Which shop-standard document the questionnaire wrote for one group, so it rewrites that one and no other. */
@Getter
@Setter
@Entity
@Table(name = "org_default_policy_sources")
public class DefaultPolicySource extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "group_key", nullable = false, length = 20)
    private String groupKey;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;
}
