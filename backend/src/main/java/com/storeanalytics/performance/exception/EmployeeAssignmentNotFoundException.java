package com.storeanalytics.performance.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class EmployeeAssignmentNotFoundException extends BusinessException {

    private final UUID storeId;
    private final UUID employeeId;

    public EmployeeAssignmentNotFoundException(UUID storeId, UUID employeeId) {
        super(
                BusinessErrorCode.EMPLOYEE_ASSIGNMENT_NOT_FOUND,
                "Employee is not assigned to store " + storeId + ": " + employeeId
        );
        this.storeId = storeId;
        this.employeeId = employeeId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }
}
