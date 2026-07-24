package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "payroll_statements")
public class PayrollStatement extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false, updatable = false)
    private PayrollRun payrollRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    @Column(name = "shift_count", nullable = false, updatable = false)
    private int shiftCount;

    @Column(name = "worked_hours", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal workedHours;

    @Column(name = "earned_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal earnedAmount;

    @Column(name = "advance_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal advanceAmount;

    @Column(name = "penalty_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal penaltyAmount;

    @Column(name = "inventory_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal inventoryAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal taxAmount;

    @Column(name = "payable_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal payableAmount;

    protected PayrollStatement() {
    }

    public PayrollStatement(
            PayrollRun payrollRun,
            Employee employee,
            int shiftCount,
            BigDecimal workedHours,
            PayrollStatementAmounts amounts
    ) {
        this.payrollRun = requireNonNull(payrollRun, "payrollRun");
        this.store = payrollRun.getStore();
        this.employee = requireNonNull(employee, "employee");
        require(shiftCount >= 0, "shiftCount must not be negative");
        this.shiftCount = shiftCount;
        this.workedHours = requireNonNegative(workedHours, "workedHours", 19, 2);
        PayrollStatementAmounts validated = requireNonNull(amounts, "amounts");
        earnedAmount = validated.earnedAmount();
        advanceAmount = validated.advanceAmount();
        penaltyAmount = validated.penaltyAmount();
        inventoryAmount = validated.inventoryAmount();
        taxAmount = validated.taxAmount();
        payableAmount = validated.payableAmount();
    }

    public Employee getEmployee() {
        return employee;
    }

    public int getShiftCount() {
        return shiftCount;
    }

    public BigDecimal getWorkedHours() {
        return workedHours;
    }

    public BigDecimal getEarnedAmount() {
        return earnedAmount;
    }

    public BigDecimal getAdvanceAmount() {
        return advanceAmount;
    }

    public BigDecimal getPenaltyAmount() {
        return penaltyAmount;
    }

    public BigDecimal getInventoryAmount() {
        return inventoryAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }
}
