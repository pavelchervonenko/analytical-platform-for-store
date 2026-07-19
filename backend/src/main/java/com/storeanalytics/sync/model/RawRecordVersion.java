package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireJson;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

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
@Table(name = "raw_record_versions")
public class RawRecordVersion {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private IntegrationConnection connection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private SourceSystem sourceSystem;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_sync_run_id", nullable = false)
    private SyncRun firstSyncRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_sync_run_id", nullable = false)
    private SyncRun lastSyncRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalization_status", nullable = false)
    private NormalizationStatus normalizationStatus;

    @Column(name = "normalized_at")
    private Instant normalizedAt;

    protected RawRecordVersion() {
    }

    public static RawRecordVersion pendingStore(
            String externalId,
            String payload,
            String payloadHash,
            SyncRun syncRun,
            Instant now
    ) {
        RawRecordDescriptor descriptor = new RawRecordDescriptor(
                syncRun.getConnection(), null, SourceSystem.LIVESKLAD,
                "STORE", externalId, null
        );
        return pending(descriptor, payload, payloadHash, syncRun, now);
    }

    public static RawRecordVersion pendingEmployee(
            Store store,
            String externalId,
            String payload,
            String payloadHash,
            SyncRun syncRun,
            Instant now
    ) {
        Store validatedStore = requireNonNull(store, "store");
        RawRecordDescriptor descriptor = new RawRecordDescriptor(
                syncRun.getConnection(), validatedStore, SourceSystem.LIVESKLAD,
                "EMPLOYEE", externalId, null
        );
        return pending(descriptor, payload, payloadHash, syncRun, now);
    }

    public static RawRecordVersion pendingSale(
            Store store,
            String externalId,
            String payload,
            String payloadHash,
            Instant sourceUpdatedAt,
            SyncRun syncRun,
            Instant now
    ) {
        Store validatedStore = requireNonNull(store, "store");
        RawRecordDescriptor descriptor = new RawRecordDescriptor(
                syncRun.getConnection(), validatedStore, SourceSystem.LIVESKLAD,
                "SALE_DOCUMENT", externalId, sourceUpdatedAt
        );
        return pending(descriptor, payload, payloadHash, syncRun, now);
    }

    public static RawRecordVersion pendingCashItems(
            String payload,
            String payloadHash,
            SyncRun syncRun,
            Instant now
    ) {
        RawRecordDescriptor descriptor = new RawRecordDescriptor(
                syncRun.getConnection(), null, SourceSystem.LIVESKLAD,
                "CASH_ITEM_DICTIONARY", "cash-items", null
        );
        return pending(descriptor, payload, payloadHash, syncRun, now);
    }

    public static RawRecordVersion pendingCashRegister(
            Store store,
            String externalId,
            String payload,
            String payloadHash,
            SyncRun syncRun,
            Instant now
    ) {
        Store validatedStore = requireNonNull(store, "store");
        RawRecordDescriptor descriptor = new RawRecordDescriptor(
                syncRun.getConnection(), validatedStore, SourceSystem.LIVESKLAD,
                "CASH_REGISTER", externalId, null
        );
        return pending(descriptor, payload, payloadHash, syncRun, now);
    }

    public static RawRecordVersion pendingReturn(
            Store store,
            String externalId,
            String payload,
            String payloadHash,
            Instant sourceUpdatedAt,
            SyncRun syncRun,
            Instant now
    ) {
        Store validatedStore = requireNonNull(store, "store");
        RawRecordDescriptor descriptor = new RawRecordDescriptor(
                syncRun.getConnection(), validatedStore, SourceSystem.LIVESKLAD,
                "RETURN_DOCUMENT", externalId, sourceUpdatedAt
        );
        return pending(descriptor, payload, payloadHash, syncRun, now);
    }
    public static RawRecordVersion pending(
            RawRecordDescriptor descriptor,
            String payload,
            String payloadHash,
            SyncRun syncRun,
            Instant now
    ) {
        requireNonNull(descriptor, "descriptor");
        requireNonNull(syncRun, "syncRun");
        require(syncRun.getSourceSystem() == descriptor.sourceSystem(),
                "syncRun sourceSystem must match raw record sourceSystem");
        require(payloadHash != null && payloadHash.length() == 64,
                "payloadHash must contain 64 characters");
        RawRecordVersion version = new RawRecordVersion();
        version.connection = descriptor.connection();
        version.store = descriptor.store();
        version.sourceSystem = descriptor.sourceSystem();
        version.entityType = descriptor.entityType();
        version.externalId = descriptor.externalId();
        version.payload = requireJson(payload, "payload");
        version.payloadHash = payloadHash;
        version.sourceUpdatedAt = descriptor.sourceUpdatedAt();
        version.firstSeenAt = requireNonNull(now, "now");
        version.lastSeenAt = now;
        version.firstSyncRun = syncRun;
        version.lastSyncRun = syncRun;
        version.normalizationStatus = NormalizationStatus.PENDING;
        return version;
    }

    public UUID getId() {
        return id;
    }

    public void markSeen(SyncRun syncRun, Instant now) {
        SyncRun validatedRun = requireNonNull(syncRun, "syncRun");
        require(validatedRun.getSourceSystem() == sourceSystem,
                "syncRun sourceSystem must match raw record sourceSystem");
        Instant seenAt = requireNonNull(now, "now");
        lastSyncRun = validatedRun;
        lastSeenAt = seenAt.isBefore(firstSeenAt) ? firstSeenAt : seenAt;
    }

    public void markNormalized(Instant now) {
        require(normalizationStatus != NormalizationStatus.NORMALIZED,
                "normalized raw record cannot be normalized again");
        normalizedAt = requireNonNull(now, "now");
        normalizationStatus = NormalizationStatus.NORMALIZED;
    }
    public void markSkipped() {
        require(normalizationStatus != NormalizationStatus.NORMALIZED,
                "normalized raw record cannot be skipped");
        normalizedAt = null;
        normalizationStatus = NormalizationStatus.SKIPPED;
    }

    public boolean isSkipped() {
        return normalizationStatus == NormalizationStatus.SKIPPED;
    }

    public boolean isNormalized() {
        return normalizationStatus == NormalizationStatus.NORMALIZED;
    }
}
