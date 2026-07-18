package com.storeanalytics.employee.model;

import static com.storeanalytics.common.validation.ModelValidation.requirePersistedId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class EmployeeStoreAssignmentId implements Serializable {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    protected EmployeeStoreAssignmentId() {
    }

    public EmployeeStoreAssignmentId(UUID employeeId, UUID storeId) {
        this.employeeId = requirePersistedId(employeeId, "employeeId");
        this.storeId = requirePersistedId(storeId, "storeId");
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmployeeStoreAssignmentId that)) {
            return false;
        }
        return Objects.equals(employeeId, that.employeeId) && Objects.equals(storeId, that.storeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(employeeId, storeId);
    }
}
