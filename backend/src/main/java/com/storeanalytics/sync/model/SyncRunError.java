package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sync_run_errors")
public class SyncRunError {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sync_run_id", nullable = false)
    private SyncRun syncRun;

    @Column(nullable = false)
    private String stage;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", nullable = false)
    private String errorMessage;

    @Column(name = "is_retryable", nullable = false)
    private boolean retryable;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SyncRunError() {
    }

    public static SyncRunError storeSyncFailure(SyncRun syncRun, String summary, Instant now) {
        return failure(syncRun, new SyncErrorDetails(
                "STORES_SYNC", null, null, "STORE_SYNC_FAILED", summary, false
        ), now);
    }

    public static SyncRunError employeeSyncFailure(
            SyncRun syncRun,
            String summary,
            Instant now
    ) {
        return failure(syncRun, new SyncErrorDetails(
                "EMPLOYEES_SYNC", null, null, "EMPLOYEE_SYNC_FAILED", summary, false
        ), now);
    }

    public static SyncRunError salesSyncFailure(
            SyncRun syncRun,
            String summary,
            boolean retryable,
            Instant now
    ) {
        return failure(syncRun, new SyncErrorDetails(
                "SALES_SYNC", "SALE_DOCUMENT", null,
                "SALES_SYNC_FAILED", summary, retryable
        ), now);
    }

    public static SyncRunError returnSyncFailure(
            SyncRun syncRun,
            String summary,
            boolean retryable,
            Instant now
    ) {
        return failure(syncRun, new SyncErrorDetails(
                "RETURN_SYNC", "RETURN_DOCUMENT", null,
                "RETURN_SYNC_FAILED", summary, retryable
        ), now);
    }

    public static SyncRunError workerLeaseExpired(
            SyncRun syncRun,
            String summary,
            Instant now
    ) {
        return failure(syncRun, new SyncErrorDetails(
                "SYNC_JOB_RECOVERY", null, null,
                "SYNC_WORKER_LEASE_EXPIRED", summary, true
        ), now);
    }

    public static SyncRunError failure(
            SyncRun syncRun,
            SyncErrorDetails details,
            Instant now
    ) {
        requireNonNull(details, "details");
        SyncRunError error = new SyncRunError();
        error.syncRun = requireNonNull(syncRun, "syncRun");
        error.stage = details.stage();
        error.entityType = details.entityType();
        error.externalId = details.externalId();
        error.errorCode = details.errorCode();
        error.errorMessage = details.errorMessage();
        error.retryable = details.retryable();
        error.metadata = "{}";
        error.createdAt = requireNonNull(now, "now");
        return error;
    }
}
