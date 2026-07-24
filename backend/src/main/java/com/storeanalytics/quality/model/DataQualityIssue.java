package com.storeanalytics.quality.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.auth.model.AppUser;
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
@Table(name = "data_quality_issues")
public class DataQualityIssue {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "issue_code", nullable = false)
    private String issueCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataQualitySeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataQualityStatus status;

    @Column(nullable = false)
    private String message;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private AppUser resolvedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected DataQualityIssue() {
    }

    public static DataQualityIssue open(
            Store store,
            String entityType,
            String entityId,
            String issueCode,
            DataQualitySeverity severity,
            String message,
            Instant detectedAt
    ) {
        DataQualityIssue issue = new DataQualityIssue();
        issue.store = store;
        issue.entityType = requireText(entityType, "entityType");
        issue.entityId = requireText(entityId, "entityId");
        issue.issueCode = requireText(issueCode, "issueCode");
        issue.severity = requireNonNull(severity, "severity");
        issue.status = DataQualityStatus.OPEN;
        issue.message = requireText(message, "message");
        issue.detectedAt = requireNonNull(detectedAt, "detectedAt");
        issue.metadata = "{}";
        return issue;
    }

    public void resolve(AppUser resolvedBy, Instant resolvedAt) {
        close(DataQualityStatus.RESOLVED, resolvedBy, resolvedAt);
    }

    public void ignore(AppUser resolvedBy, Instant resolvedAt) {
        close(DataQualityStatus.IGNORED, resolvedBy, resolvedAt);
    }

    private void close(DataQualityStatus targetStatus, AppUser actor, Instant when) {
        require(status == DataQualityStatus.OPEN, "only an open issue can be closed");
        Instant closeTime = requireNonNull(when, "resolvedAt");
        require(!closeTime.isBefore(detectedAt), "resolvedAt must not be before detectedAt");
        status = targetStatus;
        resolvedBy = actor;
        resolvedAt = closeTime;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return store == null ? null : store.getId();
    }

    public String getEntityType() {
        return entityType;
    }

    public String getIssueCode() {
        return issueCode;
    }

    public DataQualitySeverity getSeverity() {
        return severity;
    }

    public DataQualityStatus getStatus() {
        return status;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }
}
