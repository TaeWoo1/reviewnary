package com.sellerops.sync;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
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
     * <p><b>Nothing was asked of the channel.</b> Such a run is evidence about how this deployment is
     * configured and never about whether the channel is answering — which is why the freshness reads
     * look straight through it rather than letting it date, or erase, a collection.
     */
    public static final String FAILURE_CONNECTOR_UNAVAILABLE = "CONNECTOR_UNAVAILABLE";

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
