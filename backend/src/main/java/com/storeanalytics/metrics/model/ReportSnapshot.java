package com.storeanalytics.metrics.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractCreatedEntity;
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

    @Column(name = "report_type", nullable = false)
    private String reportType;

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

    @Column(name = "formula_version", nullable = false)
    private String formulaVersion;

    @Column(name = "classification_version", nullable = false)
    private String classificationVersion;

    @Column(name = "input_hash", length = 64)
    private String inputHash;

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
            ReportContent content
    ) {
        requireNonNull(definition, "definition");
        requireNonNull(content, "content");
        content.validateStatus(definition.status());
        this.store = requireNonNull(store, "store");
        this.reportType = definition.reportType();
        this.periodType = definition.periodType();
        this.periodStart = definition.periodStart();
        this.periodEnd = definition.periodEnd();
        this.status = definition.status();
        this.formulaVersion = definition.formulaVersion();
        this.classificationVersion = definition.classificationVersion();
        this.inputHash = content.inputHash();
        this.payload = content.payload();
        this.generatedAt = content.generatedAt();
        this.generatedBy = content.generatedBy();
        this.approvedAt = content.approvedAt();
        this.approvedBy = content.approvedBy();
        this.metadata = "{}";
    }
}
