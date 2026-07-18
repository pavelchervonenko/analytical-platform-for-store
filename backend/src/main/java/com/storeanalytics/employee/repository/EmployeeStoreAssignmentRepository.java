package com.storeanalytics.employee.repository;

import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.model.EmployeeStoreAssignmentId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeStoreAssignmentRepository
        extends JpaRepository<EmployeeStoreAssignment, EmployeeStoreAssignmentId> {

    List<EmployeeStoreAssignment> findAllByStoreId(UUID storeId);

    List<EmployeeStoreAssignment> findAllByEmployeeId(UUID employeeId);
}
