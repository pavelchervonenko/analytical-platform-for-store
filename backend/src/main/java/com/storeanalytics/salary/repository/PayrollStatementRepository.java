package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollStatement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollStatementRepository extends JpaRepository<PayrollStatement, UUID> {

    List<PayrollStatement> findAllByPayrollRunIdOrderByEmployeeFullName(UUID payrollRunId);

    long deleteAllByPayrollRunId(UUID payrollRunId);
}
