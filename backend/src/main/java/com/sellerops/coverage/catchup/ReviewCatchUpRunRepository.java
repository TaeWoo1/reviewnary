package com.sellerops.coverage.catchup;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewCatchUpRunRepository extends JpaRepository<ReviewCatchUpRun, UUID> {

    /** The one live intent for this row, if any — the unique index makes «one» a schema fact, not a hope. */
    Optional<ReviewCatchUpRun> findFirstBySellerAccountIdAndDataTypeAndStateIn(
            UUID sellerAccountId, String dataType, java.util.Collection<ReviewCatchUpState> states);

    /** The most recently finished walk for this row — asked before replanning history after a stop. */
    Optional<ReviewCatchUpRun> findFirstBySellerAccountIdAndDataTypeAndFinishedAtIsNotNullOrderByFinishedAtDesc(
            UUID sellerAccountId, String dataType);

    /** The most recent walk that stopped for {@code stopReason} — the calendar boundary a history walk met. */
    Optional<ReviewCatchUpRun> findFirstBySellerAccountIdAndDataTypeAndStopReasonAndFinishedAtIsNotNullOrderByFinishedAtDesc(
            UUID sellerAccountId, String dataType, String stopReason);

    /** The same press, re-finding its own intent instead of starting a second one. */
    Optional<ReviewCatchUpRun> findByOrgIdAndClientRequestId(UUID orgId, String clientRequestId);
}
