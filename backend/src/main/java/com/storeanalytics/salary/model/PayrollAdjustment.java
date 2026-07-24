package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requirePositive;
import static com.storeanalytics.common.validation.ModelValidation.requireText;
import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.employee.model.Employee;
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

@Entity
@Table(name = "payroll_adjustments")
public class PayrollAdjustment extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false, updatable = false)
    private PayrollRun payrollRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, updatable = false)
    private PayrollAdjustmentType adjustmentType;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voided_by")
    private AppUser voidedBy;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    protected PayrollAdjustment() {
    }

    public PayrollAdjustment(
            PayrollRun payrollRun,
            Employee employee,
            PayrollAdjustmentType adjustmentType,
            BigDecimal amount,
            String reason,
            AppUser createdBy
    ) {
        this.payrollRun = requireNonNull(payrollRun, "payrollRun");
        require(payrollRun.getStatus() == PayrollRunStatus.CALCULATED,
                "adjustments require a calculated payroll run");
        this.store = payrollRun.getStore();
        this.employee = requireNonNull(employee, "employee");
        this.adjustmentType = requireNonNull(adjustmentType, "adjustmentType");
        this.amount = requirePositive(amount, "amount", 19, 2);
        this.reason = requireText(reason, "reason");
        this.createdBy = requireNonNull(createdBy, "createdBy");
        active = true;
    }

    public void voidAdjustment(AppUser actor, Instant at, String reason) {
        require(active, "adjustment is already voided");
        require(payrollRun.getStatus() == PayrollRunStatus.CALCULATED,
                "approved payroll adjustments cannot be voided");
        active = false;
        voidedBy = requireNonNull(actor, "voidedBy");
        voidedAt = requireNonNull(at, "voidedAt");
        voidReason = requireText(reason, "voidReason");
    }

    public PayrollRun getPayrollRun() {
        return payrollRun;
    }

    public Employee getEmployee() {
        return employee;
    }

    public PayrollAdjustmentType getAdjustmentType() {
        return adjustmentType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public boolean isActive() {
        return active;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public AppUser getVoidedBy() {
        return voidedBy;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }
}
