package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "payroll_runs")
public class PayrollRun extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "period_month", nullable = false, updatable = false)
    private LocalDate periodMonth;

    @Column(nullable = false, updatable = false)
    private int revision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_run_id", updatable = false)
    private PayrollRun supersedes;

    @Column(name = "revision_reason", updatable = false)
    private String revisionReason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private PayrollScheme scheme;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayrollRunStatus status;

    @Column(name = "revenue_plan_target", nullable = false, precision = 19, scale = 2)
    private BigDecimal revenuePlanTarget;

    @Column(name = "actual_revenue", nullable = false, precision = 19, scale = 2)
    private BigDecimal actualRevenue;

    @Column(name = "revenue_plan_achieved", nullable = false)
    private boolean revenuePlanAchieved;

    @Column(name = "accessory_share_target", nullable = false, precision = 5, scale = 2)
    private BigDecimal accessoryShareTarget;

    @Column(name = "actual_accessory_turnover", nullable = false, precision = 19, scale = 2)
    private BigDecimal actualAccessoryTurnover;

    @Column(name = "actual_accessory_share_percent", precision = 9, scale = 2)
    private BigDecimal actualAccessorySharePercent;

    @Column(name = "accessory_plan_achieved", nullable = false)
    private boolean accessoryPlanAchieved;

    @Column(name = "service_share_target", nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceShareTarget;

    @Column(name = "actual_service_turnover", nullable = false, precision = 19, scale = 2)
    private BigDecimal actualServiceTurnover;

    @Column(name = "actual_service_share_percent", precision = 9, scale = 2)
    private BigDecimal actualServiceSharePercent;

    @Column(name = "service_plan_achieved", nullable = false)
    private boolean servicePlanAchieved;

    @Column(name = "calculation_complete", nullable = false)
    private boolean calculationComplete;

    @Column(name = "unmapped_item_count", nullable = false)
    private int unmappedItemCount;

    @Column(name = "missing_cost_item_count", nullable = false)
    private int missingCostItemCount;

    @Column(name = "days_without_shift", nullable = false)
    private int daysWithoutShift;

    @Column(name = "calculation_generation", nullable = false)
    private long calculationGeneration;

    @Column(name = "source_fingerprint_version")
    private Integer sourceFingerprintVersion;

    @Column(name = "source_sales_hash", length = 64)
    private String sourceSalesHash;

    @Column(name = "source_shifts_hash", length = 64)
    private String sourceShiftsHash;

    @Column(name = "source_plan_hash", length = 64)
    private String sourcePlanHash;

    @Column(name = "source_classification_hash", length = 64)
    private String sourceClassificationHash;

    @Column(name = "source_scheme_hash", length = 64)
    private String sourceSchemeHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by")
    private AppUser paidBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected PayrollRun() {
    }

    public PayrollRun(PayrollRunDefinition definition) {
        PayrollRunDefinition validated = requireNonNull(definition, "definition");
        store = validated.store();
        periodMonth = validated.periodMonth();
        revision = validated.revision();
        supersedes = validated.supersedes();
        revisionReason = validated.revisionReason();
        scheme = validated.scheme();
        createdBy = validated.createdBy();
        status = PayrollRunStatus.CALCULATED;
        calculationGeneration = 1;
        applyCalculation(validated);
    }

    public void recalculate(PayrollRunDefinition definition) {
        require(status == PayrollRunStatus.CALCULATED,
                "only a calculated payroll run can be recalculated");
        PayrollRunDefinition validated = requireNonNull(definition, "definition");
        require(validated.store().getId().equals(store.getId()), "store cannot change");
        require(validated.periodMonth().equals(periodMonth), "period month cannot change");
        scheme = validated.scheme();
        calculationGeneration = Math.addExact(calculationGeneration, 1);
        applyCalculation(validated);
    }

    private void applyCalculation(PayrollRunDefinition definition) {
        PayrollPlanResult plan = definition.planResult();
        revenuePlanTarget = plan.revenueTarget();
        actualRevenue = plan.actualRevenue();
        revenuePlanAchieved = plan.revenueAchieved();
        accessoryShareTarget = plan.accessoryShareTarget();
        actualAccessoryTurnover = plan.actualAccessoryTurnover();
        actualAccessorySharePercent = plan.actualAccessorySharePercent();
        accessoryPlanAchieved = plan.accessoryAchieved();
        serviceShareTarget = plan.serviceShareTarget();
        actualServiceTurnover = plan.actualServiceTurnover();
        actualServiceSharePercent = plan.actualServiceSharePercent();
        servicePlanAchieved = plan.serviceAchieved();
        PayrollRunQuality quality = definition.quality();
        calculationComplete = quality.complete();
        unmappedItemCount = quality.unmappedItemCount();
        missingCostItemCount = quality.missingCostItemCount();
        daysWithoutShift = quality.daysWithoutShift();
        PayrollSourceFingerprint fingerprint = definition.sourceFingerprint();
        sourceFingerprintVersion = fingerprint.version();
        sourceSalesHash = fingerprint.salesHash();
        sourceShiftsHash = fingerprint.shiftsHash();
        sourcePlanHash = fingerprint.planHash();
        sourceClassificationHash = fingerprint.classificationHash();
        sourceSchemeHash = fingerprint.schemeHash();
    }

    public void approve(AppUser actor, Instant at) {
        require(status == PayrollRunStatus.CALCULATED, "payroll run is not calculated");
        require(calculationComplete, "incomplete payroll run cannot be approved");
        approvedBy = requireNonNull(actor, "approvedBy");
        approvedAt = requireNonNull(at, "approvedAt");
        status = PayrollRunStatus.APPROVED;
    }

    public void markPaid(AppUser actor, Instant at) {
        require(status == PayrollRunStatus.APPROVED, "only approved payroll can be paid");
        paidBy = requireNonNull(actor, "paidBy");
        paidAt = requireNonNull(at, "paidAt");
        status = PayrollRunStatus.PAID;
    }

    public PayrollPlanResult getPlanResult() {
        return new PayrollPlanResult(
                revenuePlanTarget,
                actualRevenue,
                revenuePlanAchieved,
                accessoryShareTarget,
                actualAccessoryTurnover,
                actualAccessorySharePercent,
                accessoryPlanAchieved,
                serviceShareTarget,
                actualServiceTurnover,
                actualServiceSharePercent,
                servicePlanAchieved
        );
    }

    public Store getStore() {
        return store;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public int getRevision() {
        return revision;
    }

    public PayrollRun getSupersedes() {
        return supersedes;
    }

    public String getRevisionReason() {
        return revisionReason;
    }

    public PayrollScheme getScheme() {
        return scheme;
    }

    public PayrollRunStatus getStatus() {
        return status;
    }

    public boolean isCalculationComplete() {
        return calculationComplete;
    }

    public int getUnmappedItemCount() {
        return unmappedItemCount;
    }

    public int getMissingCostItemCount() {
        return missingCostItemCount;
    }

    public int getDaysWithoutShift() {
        return daysWithoutShift;
    }

    public long getCalculationGeneration() {
        return calculationGeneration;
    }

    public PayrollSourceFingerprint getSourceFingerprint() {
        if (sourceFingerprintVersion == null) {
            return null;
        }
        return new PayrollSourceFingerprint(
                sourceFingerprintVersion,
                sourceSalesHash,
                sourceShiftsHash,
                sourcePlanHash,
                sourceClassificationHash,
                sourceSchemeHash
        );
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public AppUser getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public AppUser getPaidBy() {
        return paidBy;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
