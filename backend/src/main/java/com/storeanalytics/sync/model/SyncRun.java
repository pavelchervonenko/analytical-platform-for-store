package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "sync_runs")
public class SyncRun {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private IntegrationConnection connection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;
    @Column(name = "sync_job_id")
    private UUID syncJobId;


    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private SourceSystem sourceSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private SyncTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_scope", nullable = false)
    private SyncScope syncScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private AppUser requestedBy;

    @Column(name = "records_fetched", nullable = false)
    private int recordsFetched;

    @Column(name = "records_created", nullable = false)
    private int recordsCreated;

    @Column(name = "records_updated", nullable = false)
    private int recordsUpdated;

    @Column(name = "records_skipped", nullable = false)
    private int recordsSkipped;

    @Column(name = "records_failed", nullable = false)
    private int recordsFailed;

    @Column(name = "error_summary")
    private String errorSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SyncRun() {
    }

    public static SyncRun startStoreSync(IntegrationConnection connection, Instant now) {
        return startStoreSync(
                connection, SyncTriggerType.MANUAL, null, null, now
        );
    }

    public static SyncRun startStoreSync(
            IntegrationConnection connection,
            SyncTriggerType triggerType,
            UUID syncJobId,
            AppUser requestedBy,
            Instant now
    ) {
        requireNonNull(connection, "connection");
        return start(new SyncRunRequest(
                connection.getSourceSystem(), connection, null, triggerType,
                SyncScope.STORES, null, syncJobId, requestedBy
        ), now);
    }

    public static SyncRun startEmployeeSync(IntegrationConnection connection, Instant now) {
        return startEmployeeSync(
                connection, SyncTriggerType.MANUAL, null, null, now
        );
    }

    public static SyncRun startEmployeeSync(
            IntegrationConnection connection,
            SyncTriggerType triggerType,
            UUID syncJobId,
            AppUser requestedBy,
            Instant now
    ) {
        requireNonNull(connection, "connection");
        return start(new SyncRunRequest(
                connection.getSourceSystem(), connection, null, triggerType,
                SyncScope.EMPLOYEES, null, syncJobId, requestedBy
        ), now);
    }

    public static SyncRun startSalesSync(
            IntegrationConnection connection,
            SyncPeriod period,
            Instant now
    ) {
        return startSalesSync(
                connection, period, SyncTriggerType.MANUAL, null, null, now
        );
    }

    public static SyncRun startSalesSync(
            IntegrationConnection connection,
            SyncPeriod period,
            SyncTriggerType triggerType,
            UUID syncJobId,
            AppUser requestedBy,
            Instant now
    ) {
        requireNonNull(connection, "connection");
        requireNonNull(period, "period");
        return start(new SyncRunRequest(
                connection.getSourceSystem(), connection, null, triggerType,
                SyncScope.SALES, period, syncJobId, requestedBy
        ), now);
    }

    public static SyncRun startReturnSync(
            IntegrationConnection connection,
            SyncPeriod period,
            Instant now
    ) {
        return startReturnSync(
                connection, period, SyncTriggerType.MANUAL, null, null, now
        );
    }

    public static SyncRun startReturnSync(
            IntegrationConnection connection,
            SyncPeriod period,
            SyncTriggerType triggerType,
            UUID syncJobId,
            AppUser requestedBy,
            Instant now
    ) {
        requireNonNull(connection, "connection");
        requireNonNull(period, "period");
        return start(new SyncRunRequest(
                connection.getSourceSystem(), connection, null, triggerType,
                SyncScope.RETURNS, period, syncJobId, requestedBy
        ), now);
    }

    public static SyncRun start(SyncRunRequest request, Instant now) {
        requireNonNull(request, "request");
        SyncPeriod period = request.period();
        SyncRun run = new SyncRun();
        run.connection = request.connection();
        run.store = request.store();
        run.syncJobId = request.syncJobId();
        run.sourceSystem = request.sourceSystem();
        run.triggerType = request.triggerType();
        run.syncScope = request.syncScope();
        run.status = SyncStatus.RUNNING;
        run.periodStart = period == null ? null : period.start();
        run.periodEnd = period == null ? null : period.end();
        run.startedAt = requireNonNull(now, "now");
        run.requestedBy = request.requestedBy();
        run.metadata = "{}";
        run.createdAt = now;
        return run;
    }

    public void complete(int fetched, int created, int updated, int skipped, Instant now) {
        require(status == SyncStatus.RUNNING, "only a running sync can be completed");
        require(fetched >= 0 && created >= 0 && updated >= 0 && skipped >= 0,
                "sync counters must not be negative");
        status = SyncStatus.SUCCESS;
        recordsFetched = fetched;
        recordsCreated = created;
        recordsUpdated = updated;
        recordsSkipped = skipped;
        recordsFailed = 0;
        finishedAt = normalizedFinishedAt(now);
        errorSummary = null;
    }

    public void completePartial(
            int fetched,
            int created,
            int updated,
            int skipped,
            int failed,
            Instant now
    ) {
        require(status == SyncStatus.RUNNING, "only a running sync can be completed");
        require(fetched >= 0 && created >= 0 && updated >= 0
                        && skipped >= 0 && failed > 0,
                "partial sync counters must be valid");
        status = SyncStatus.PARTIAL_SUCCESS;
        recordsFetched = fetched;
        recordsCreated = created;
        recordsUpdated = updated;
        recordsSkipped = skipped;
        recordsFailed = failed;
        finishedAt = normalizedFinishedAt(now);
        errorSummary = "Some records were skipped because dependencies are missing";
    }
    public void fail(int fetched, String summary, Instant now) {
        require(status == SyncStatus.RUNNING, "only a running sync can fail");
        require(fetched >= 0, "fetched must not be negative");
        requireText(summary, "summary");
        status = SyncStatus.FAILED;
        recordsFetched = fetched;
        recordsFailed = 1;
        finishedAt = normalizedFinishedAt(now);
        errorSummary = summary;
    }

    private Instant normalizedFinishedAt(Instant now) {
        Instant finished = requireNonNull(now, "now");
        return finished.isBefore(startedAt) ? startedAt : finished;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSyncJobId() {
        return syncJobId;
    }

    public IntegrationConnection getConnection() {
        return connection;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public int getRecordsFetched() {
        return recordsFetched;
    }

    public int getRecordsCreated() {
        return recordsCreated;
    }

    public int getRecordsUpdated() {
        return recordsUpdated;
    }

    public int getRecordsSkipped() {
        return recordsSkipped;
    }

    public int getRecordsFailed() {
        return recordsFailed;
    }
}
