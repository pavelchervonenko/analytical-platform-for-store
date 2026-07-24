package com.storeanalytics.performance.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requirePositive;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
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
@Table(name = "employee_work_shifts")
public class EmployeeWorkShift extends AbstractMutableEntity {

    public static final BigDecimal FULL_SHIFT_HOURS = new BigDecimal("11.00");

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "worked_hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal workedHours;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    protected EmployeeWorkShift() {
    }

    public EmployeeWorkShift(
            Store store,
            Employee employee,
            LocalDate workDate,
            AppUser updatedBy
    ) {
        this(store, employee, workDate, FULL_SHIFT_HOURS, updatedBy);
    }

    public EmployeeWorkShift(
            Store store,
            Employee employee,
            LocalDate workDate,
            BigDecimal workedHours,
            AppUser updatedBy
    ) {
        this.store = requireNonNull(store, "store");
        this.employee = requireNonNull(employee, "employee");
        this.workDate = requireNonNull(workDate, "workDate");
        this.workedHours = validateWorkedHours(workedHours);
        active = true;
        this.updatedBy = requireNonNull(updatedBy, "updatedBy");
    }

    public void activate(AppUser actor) {
        active = true;
        workedHours = FULL_SHIFT_HOURS;
        updatedBy = requireNonNull(actor, "updatedBy");
    }

    public void deactivate(AppUser actor) {
        active = false;
        updatedBy = requireNonNull(actor, "updatedBy");
    }

    public void setWorkedHours(BigDecimal hours, AppUser actor) {
        workedHours = validateWorkedHours(hours);
        active = true;
        updatedBy = requireNonNull(actor, "updatedBy");
    }

    public static BigDecimal validateWorkedHours(BigDecimal hours) {
        BigDecimal validated = requirePositive(hours, "workedHours", 5, 2);
        if (validated.compareTo(FULL_SHIFT_HOURS) > 0) {
            throw new IllegalArgumentException("workedHours must not exceed 11");
        }
        return validated;
    }

    public Store getStore() {
        return store;
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

    public boolean isActive() {
        return active;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }
}
