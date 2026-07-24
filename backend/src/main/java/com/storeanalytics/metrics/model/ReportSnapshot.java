package com.storeanalytics.metrics.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "report_snapshots")
public class ReportSnapshot extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, updatable = false)
    private ReportType reportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false)
    private ReportPeriodType periodType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "template_version", nullable = false, updatable = false)
    private String templateVersion;

    @Column(name = "data_contract_version", nullable = false, updatable = false)
    private String dataContractVersion;

    @Column(name = "source_hash", length = 64, updatable = false)
    private String sourceHash;
    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(nullable = false, updatable = false)
    private int revision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_snapshot_id", updatable = false)
    private ReportSnapshot supersedes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", updatable = false)
    private PayrollRun payrollRun;

    @Column(name = "revision_reason", updatable = false)
    private String revisionReason;

    @Column(name = "payload_hash", length = 64, updatable = false)
    private String payloadHash;


    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by")
    private AppUser generatedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected ReportSnapshot() {
    }

    public ReportSnapshot(
            Store store,
            ReportDefinition definition,
            ReportContent content,
            ReportRevision revision
    ) {
        requireNonNull(definition, "definition");
        requireNonNull(content, "content");
        content.validateStatus(definition.status());
        ReportRevision validatedRevision = requireNonNull(revision, "revision");
        validateFinalizedContract(definition, content, validatedRevision);
        this.store = requireNonNull(store, "store");
        this.reportType = definition.reportType();
        this.periodType = definition.periodType();
        this.periodStart = definition.periodStart();
        this.periodEnd = definition.periodEnd();
        this.status = definition.status();
        this.templateVersion = definition.formulaVersion();
        this.dataContractVersion = definition.classificationVersion();
        this.sourceHash = content.integrity().sourceHash();
        this.payloadHash = content.integrity().payloadHash();
        this.revision = validatedRevision.number();
        this.supersedes = validatedRevision.supersedes();
        this.payrollRun = validatedRevision.payrollRun();
        this.revisionReason = validatedRevision.reason();
        this.payload = content.payload();
        this.generatedAt = content.generatedAt();
        this.generatedBy = content.generatedBy();
        this.approvedAt = content.approvedAt();
        this.approvedBy = content.approvedBy();
        this.metadata = "{}";
        this.schemaVersion = validatedRevision.schemaVersion();
    }
    private void validateFinalizedContract(
            ReportDefinition definition,
            ReportContent content,
            ReportRevision revisionDefinition
    ) {
        if (definition.status() != ReportStatus.FINALIZED) {
            return;
        }
        require(content.integrity().sourceHash() != null,
                "finalized reports require a source hash");
        boolean monthly = definition.reportType() == ReportType.MONTHLY;
        require(monthly == (revisionDefinition.payrollRun() != null),
                "monthly reports require payroll and annual reports must not reference payroll");
        require((monthly && definition.periodType() == ReportPeriodType.MONTH)
                        || (!monthly && definition.periodType() == ReportPeriodType.YEAR),
                "report type and period type must match");
    }

    public Store getStore() {
        return store;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public ReportPeriodType getPeriodType() {
        return periodType;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public String getDataContractVersion() {
        return dataContractVersion;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public String getPayload() {
        return payload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public int getRevision() {
        return revision;
    }

    public ReportSnapshot getSupersedes() {
        return supersedes;
    }

    public PayrollRun getPayrollRun() {
        return payrollRun;
    }

    public String getRevisionReason() {
        return revisionReason;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public AppUser getGeneratedBy() {
        return generatedBy;
    }
}
