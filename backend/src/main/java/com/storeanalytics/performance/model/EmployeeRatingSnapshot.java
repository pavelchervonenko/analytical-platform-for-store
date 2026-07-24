package com.storeanalytics.performance.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;

@Entity
@Table(
        name = "employee_rating_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "employee_rating_snapshots_store_period_unique",
                columnNames = {"store_id", "period_start", "period_end"}
        )
)
public class EmployeeRatingSnapshot extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(name = "formula_code", nullable = false, updatable = false, length = 100)
    private String formulaCode;

    @Column(name = "result_payload", nullable = false, updatable = false, columnDefinition = "text")
    private String resultPayload;

    @Column(name = "result_sha256", nullable = false, updatable = false, length = 64)
    private String resultSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finalized_by", nullable = false, updatable = false)
    private AppUser finalizedBy;

    @Column(name = "finalized_by_name", nullable = false, updatable = false)
    private String finalizedByName;

    protected EmployeeRatingSnapshot() {
    }

    public EmployeeRatingSnapshot(
            Store store,
            LocalDate periodStart,
            LocalDate periodEnd,
            String formulaCode,
            String resultPayload,
            String resultSha256,
            AppUser finalizedBy
    ) {
        this.store = requireNonNull(store, "store");
        this.periodStart = requireNonNull(periodStart, "periodStart");
        this.periodEnd = requireNonNull(periodEnd, "periodEnd");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not be before periodStart");
        }
        this.formulaCode = requireText(formulaCode, "formulaCode");
        this.resultPayload = requireText(resultPayload, "resultPayload");
        this.resultSha256 = requireText(resultSha256, "resultSha256");
        this.finalizedBy = requireNonNull(finalizedBy, "finalizedBy");
        this.finalizedByName = requireText(finalizedBy.getDisplayName(), "finalizedByName");
    }

    public Store getStore() {
        return store;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getFormulaCode() {
        return formulaCode;
    }

    public String getResultPayload() {
        return resultPayload;
    }

    public String getResultSha256() {
        return resultSha256;
    }

    public AppUser getFinalizedBy() {
        return finalizedBy;
    }

    public String getFinalizedByName() {
        return finalizedByName;
    }
}
