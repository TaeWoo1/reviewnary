package com.sellerops.review.triage.corpus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriageFeedbackSnapshotRepository extends JpaRepository<TriageFeedbackSnapshot, UUID> {

    /**
     * Whether this org has already used this version string, <b>for either kind</b>.
     *
     * <p>Not {@code ...AndKind...}: the point of the check is that one version names one cut. A silver cut called
     * {@code 2026-10/1} and a correction cut of the same name are two sets a later reader would believe were one.
     */
    Optional<TriageFeedbackSnapshot> findByOrgIdAndVersion(UUID orgId, String version);

    List<TriageFeedbackSnapshot> findByOrgIdOrderByCutAtDesc(UUID orgId);

    List<TriageFeedbackSnapshot> findByOrgIdAndKindOrderByCutAtDesc(UUID orgId, SnapshotKind kind);
}
