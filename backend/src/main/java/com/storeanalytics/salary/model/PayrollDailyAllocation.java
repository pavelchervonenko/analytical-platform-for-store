package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireNumeric;
import static com.storeanalytics.performance.model.EmployeeWorkShift.validateWorkedHours;

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
import java.time.LocalDate;

@Entity
@Table(name = "payroll_daily_allocations")
public class PayrollDailyAllocation extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false, updatable = false)
    private PayrollRun payrollRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "worked_hours", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal workedHours;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    protected PayrollDailyAllocation() {
    }

    public PayrollDailyAllocation(
            PayrollRun payrollRun,
            Employee employee,
            LocalDate workDate,
            BigDecimal workedHours,
            BigDecimal amount
    ) {
        this.payrollRun = requireNonNull(payrollRun, "payrollRun");
        this.store = payrollRun.getStore();
        this.employee = requireNonNull(employee, "employee");
        this.workDate = requireNonNull(workDate, "workDate");
        this.workedHours = validateWorkedHours(workedHours);
        this.amount = requireNumeric(amount, "amount", 19, 2);
    }

    public Employee getEmployee() {
        return employee;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public BigDecimal getWorkedHours() {
        return workedHours;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
