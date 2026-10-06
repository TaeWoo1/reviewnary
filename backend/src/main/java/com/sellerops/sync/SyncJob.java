package com.sellerops.sync;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Record of one ingestion run (file upload now; API pulls later). */
@Getter
@Setter
@Entity
@Table(name = "sync_jobs")
public class SyncJob extends BaseEntity {

    /**
     * The run ended at its own configuration gate: no pull connector for the channel, a data type that
     * connector cannot serve, or a backfill window it cannot seed.
     *
     * <p>One of {@link #FAILURE_CODES_BEFORE_CHANNEL_ATTEMPT} — nothing was asked of the channel.
     */
    public static final String FAILURE_CONNECTOR_UNAVAILABLE = "CONNECTOR_UNAVAILABLE";

    /**
     * The run was refused by a live-approval interlock: the process carries no approval id and no standing
     * read grant for the channel it was about to call.
     *
     * <p>One of {@link #FAILURE_CODES_BEFORE_CHANNEL_ATTEMPT}. {@code SyncRunExecutor.failureCodeOf} sets
     * this code for exactly one condition — {@code CoupangLiveApprovalRequiredException}, thrown by
     * {@code CoupangLiveCallGuard} as the <b>first statement</b> of the signed-GET choke points, ahead of the
     * signature and the socket. Keep that the only source: the code's meaning here is "no request left this
     * process", and a second producer that had already called a channel would quietly make it a lie.
     */
    public static final String FAILURE_CONFIGURATION_REQUIRED = "CONFIGURATION_REQUIRED";

    /**
     * <b>The codes that mean the run ended before it asked the channel anything.</b>
     *
     * <p>This set is the <i>meaning</i> the freshness surfaces read by, and it is deliberately a statement
     * about evidence rather than a list of uninteresting errors: a run in here opened no socket, so it is
     * evidence about how this deployment is configured and says nothing at all about whether the channel is
     * answering. {@code SyncJobRepository.findRunsReachingChannel} therefore looks straight through such a
     * run — it may neither date a collection nor erase one.
     *
     * <p><b>Everything else stays visible, and that is the other half of the rule.</b> A failure that reached
     * the marketplace — a rejected credential (AUTH_REQUIRED), a gateway that refused this egress
     * (GW.IP_NOT_ALLOWED), a 4xx, a timeout, a rate limit — IS an answer from the channel and still stands as
     * its latest word, because a failure that is evidence must never read as a success.
     *
     * <p><b>Why a FAILED run in here provably collected nothing.</b> {@code SyncRunExecutor.resolveStatus}
     * returns PARTIAL, not FAILED, the moment any page landed. So a run carrying one of these codes AND the
     * FAILED status got through zero pages; the paired PARTIAL case keeps its ordinary meaning and is not
     * skipped anywhere.
     *
     * <p><b>Known boundary — credential resolution.</b> A run that cannot find or decrypt its stored
     * credential also stops before any request, but it surfaces as the general EXECUTION_FAILED, which real
     * attempt failures share. Widening the skip to that code would hide genuine failures, so those runs are
     * NOT in here: they still date and still erase. Giving them a code of their own is a separate change.
     */
    public static final Set<String> FAILURE_CODES_BEFORE_CHANNEL_ATTEMPT =
            Set.of(FAILURE_CONNECTOR_UNAVAILABLE, FAILURE_CONFIGURATION_REQUIRED);

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "channel_id")
    private UUID channelId;

    /** Seller account this run belongs to (null for legacy/channel-only upload jobs). */
    @Column(name = "seller_account_id")
    private UUID sellerAccountId;

    /** REVIEW / INQUIRY / ORDER_SUMMARY (null on legacy rows). */
    @Column(name = "data_type")
    private String dataType;

    /** How the run collected data: API / SELLER_CENTER_EXPORT / MANUAL_UPLOAD (null on legacy rows). */
    @Column(name = "method")
    private String method;

    /** How the run was triggered: UPLOAD / SCHEDULED / MANUAL / RETRY. Defaults to UPLOAD. */
    @Column(name = "\"trigger\"", nullable = false)
    private String trigger = "UPLOAD";

    /** Attempt number for retry tracking (1 = first attempt). */
    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /** Whether the run hit a rate limit. */
    @Column(name = "rate_limited", nullable = false)
    private boolean rateLimited = false;

    /** Connector kind, e.g. FILE_UPLOAD. */
    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "upload_type")
    private String uploadType;

    /** RUNNING / SUCCESS / PARTIAL / FAILED. */
    @Column(nullable = false)
    private String status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "success_rows", nullable = false)
    private int successRows;

    @Column(name = "skipped_rows", nullable = false)
    private int skippedRows;

    @Column(name = "failed_rows", nullable = false)
    private int failedRows;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /**
     * Closed classification of why the run that just finished was not clean — set by {@code SyncRunExecutor}.
     * AUTH_REQUIRED / TIMEOUT / RATE_LIMITED / PAGE_LIMIT_REACHED / {@link #FAILURE_CONNECTOR_UNAVAILABLE} /
     * CONFIGURATION_REQUIRED / EXECUTION_FAILED; null for a clean run.
     *
     * <p><b>Persisted since V116, and it was transient before.</b> The code reached whoever held the returned
     * instance and died there, so every reader that loads a run back from the database saw {@code FAILED} and
     * nothing about why — which is how a run that never opened a socket came to overwrite the freshness
     * surfaces. Rows written before V116 carry null, which reads as "says nothing special".
     */
    @Column(name = "failure_code", length = 40)
    private String failureCode;

    /**
     * Rows this run inserted as new — set by {@code SyncRunExecutor} on the instance it returns, not persisted.
     * {@code successRows} also counts in-place updates, so it cannot answer «how many were new». Null when unknown.
     */
    @Transient
    private Integer insertedRows;
}
