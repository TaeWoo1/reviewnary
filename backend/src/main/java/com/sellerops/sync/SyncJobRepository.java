package com.sellerops.sync;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {
    List<SyncJob> findTop20ByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /** Bounded filter window for the run-history search (filtering happens in-service). */
    List<SyncJob> findTop200ByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /**
     * The org's most recent REVIEW imports, newest first — filtered in SQL, then limited.
     *
     * <p><b>The order of those two matters.</b> The sibling reads above fetch a fixed window and
     * leave filtering to the caller, so a busy org can push its review imports out of the window
     * before the filter ever runs — the seller then sees an empty history for imports that exist.
     * Here the predicate is part of the query and {@code pageable} bounds the result after it, so
     * the newest N review imports are the newest N review imports.
     *
     * <p><b>The predicate is exact, not heuristic.</b> Only two writers set {@code uploadType} at all —
     * {@code FileUploadConnector} ({@code jobType='FILE_UPLOAD'}) and {@code AgentReviewHandoffService}
     * ({@code jobType='AGENT_HANDOFF'}, the Coupang WING screen read) — so this pair selects precisely the
     * review imports and nothing else. The agent handoff belongs here for the reason the upload does: the
     * seller asking "did my review import work" does not distinguish a file from a screen, and a history that
     * showed one and hid the other would answer that question wrongly. It deliberately does NOT
     * filter on {@code dataType} or {@code sellerAccountId}: an upload carries {@code null} for both
     * (see {@code FileUploadConnector.uploadDescriptor}), which is exactly why the existing
     * run-history filters cannot see uploads.
     *
     * <p>Ordering is <b>deterministic</b>: {@code finishedAt} where the import ended, falling back to
     * {@code createdAt} while it has not, with {@code id desc} as the tiebreaker. Sorting on the
     * import's own end instant is what keeps the list consistent with the dates it shows — two
     * overlapping imports (one long, one short) would otherwise render in an order the dates
     * contradict.
     *
     * <p>⚠ The fallback is {@code createdAt}, while the surface falls back to {@code startedAt}. They
     * are stamped together by {@code CollectionRunService.open}, so in practice they coincide — but
     * they are not the same column, and the difference is deliberate: {@code createdAt} is NOT NULL
     * (BaseEntity) where {@code startedAt} is nullable, so ordering on it cannot degrade into a null
     * sort key. Do not "fix" this into {@code startedAt} without making that column non-null first.
     */
    @Query("""
            select j from SyncJob j
            where j.orgId = :orgId and j.jobType in ('FILE_UPLOAD', 'AGENT_HANDOFF')
              and j.uploadType = 'REVIEW'
            order by coalesce(j.finishedAt, j.createdAt) desc, j.id desc
            """)
    List<SyncJob> findReviewImports(@Param("orgId") UUID orgId, Pageable pageable);

    /** Org-scoped lookup — a cross-org id reads as absent. */
    Optional<SyncJob> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The most recent sync of one data type for one account, newest first — used by the
     * first-connection capability check to report order-read status from real history
     * (no live call). Org-scoped, so a cross-org account reads as absent.
     */
    Optional<SyncJob> findFirstByOrgIdAndSellerAccountIdAndDataTypeOrderByCreatedAtDesc(
            UUID orgId, UUID sellerAccountId, String dataType);

    /**
     * <b>The most recent run of one data type for one CHANNEL that actually asked the channel something</b>
     * — newest first, runs that stopped at their own configuration gate skipped.
     *
     * <p>The channel scope is not cosmetic. A surface that lists reviews by channel — every review the org
     * holds for that channel, whichever seller account collected it — must date its "last import" and its
     * "new since" by the same scope it listed by. Reading the import from one ACCOUNT instead would mark rows
     * collected under a sibling account as new or not-new against a clock that never ran over them.
     *
     * <p><b>Why the skip, and why it is the repository's job.</b> The two freshness surfaces used to take the
     * single latest run and read its status, which let a run that never opened a socket speak for the
     * channel. On 2026-10-07 six manual syncs against a backend started without connector configuration
     * proved it twice over, in opposite directions: coverage dropped four real 09-26 collection times to
     * 「확인된 적 없음」, while the 리뷰 record dated its last import to that same minute and told the seller
     * 「마지막 수집이 목록 끝까지 확인되지 않은 상태로 끝났습니다」 about a collection nobody attempted. One
     * question — which run last spoke for this channel — asked by two services, so it is answered once here
     * rather than twice, differently, in them.
     *
     * <p><b>The skip is narrow on purpose.</b> Only {@link SyncJob#FAILURE_CONNECTOR_UNAVAILABLE} is
     * transparent. A run that reached the connector and was refused — a dead credential, a missing live
     * approval, a timeout — is a real answer from the channel and still stands here as the latest word,
     * because a failure that IS evidence must never read as a success. Rows written before V116 carry no
     * code and are never skipped, so history keeps exactly the meaning it had.
     *
     * <p>Bounded in SQL rather than filtered afterwards: a deployment whose connectors are off emits these
     * runs in streaks, and a fixed window fetched first would let a streak push the real collection out of
     * sight — the very fact this read exists to hold on to.
     */
    @Query("""
            select j from SyncJob j
            where j.orgId = :orgId and j.channelId = :channelId and j.dataType = :dataType
              and (j.failureCode is null or j.failureCode <> :skippedFailureCode)
            order by j.createdAt desc, j.id desc
            """)
    List<SyncJob> findRunsReachingChannel(@Param("orgId") UUID orgId, @Param("channelId") UUID channelId,
                                          @Param("dataType") String dataType,
                                          @Param("skippedFailureCode") String skippedFailureCode,
                                          Pageable pageable);

    /** {@link #findRunsReachingChannel} narrowed to the latest one — the form both callers want. */
    default Optional<SyncJob> findLatestRunReachingChannel(UUID orgId, UUID channelId, String dataType) {
        return findRunsReachingChannel(orgId, channelId, dataType, SyncJob.FAILURE_CONNECTOR_UNAVAILABLE,
                        PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    /**
     * The in-flight ({@code RUNNING}) runs for one (seller account, data type) — the single-flight
     * gate ({@code SyncRunGate}) reads this under the account row lock to decide whether to coalesce a
     * new run into an existing one or fail an orphaned one. Oldest first so a stale sweep is
     * deterministic. Normally 0 or 1 rows; more only if a prior crash left several.
     */
    @Query("select j from SyncJob j where j.sellerAccountId = :sellerAccountId "
            + "and j.dataType = :dataType and j.status = 'RUNNING' order by j.startedAt asc")
    List<SyncJob> findRunningBySellerAccountIdAndDataType(
            @Param("sellerAccountId") UUID sellerAccountId, @Param("dataType") String dataType);

    /**
     * <b>The most recent successful collection for each account</b> — the evidence that a recorded
     * failure has since ended.
     *
     * <p>Returns {@code (sellerAccountId, max(finishedAt))} over runs that SUCCEEDED (or partially
     * did). One org-scoped query for every alert on the page rather than one per alert.
     *
     * <p><b>{@code max}, not {@code min}, and the difference is a bug this had.</b> The first version
     * asked for the EARLIEST success after the oldest alert on the page, then compared that one
     * timestamp against each alert's own time. On the live org that silently failed: Coupang's first
     * success after the oldest alert (2026-08-18) predated Coupang's own alert (08-23), so the account
     * read as never recovered — while it had in fact collected successfully on 09-12. The latest
     * success is the only per-account figure a single grouped query can produce that answers
     * 「has collection worked since THIS alert」 correctly for every alert on the page.
     *
     * <p><b>{@code PARTIAL} counts.</b> A run that brought some rows back reached the channel and was
     * answered; the condition a failure alert reports — that collection is not getting through — has
     * ended. Treating it as still-failing would keep a resolved alert on a seller's screen for a
     * channel that is demonstrably reachable.
     */
    @Query("""
            select j.sellerAccountId, max(j.finishedAt) from SyncJob j
            where j.sellerAccountId in :accountIds
              and j.status in ('SUCCESS', 'PARTIAL')
              and j.finishedAt is not null
            group by j.sellerAccountId
            """)
    List<Object[]> latestSuccessByAccount(@Param("accountIds") Collection<UUID> accountIds);
}
