package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollAdjustment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollAdjustmentRepository extends JpaRepository<PayrollAdjustment, UUID> {

    List<PayrollAdjustment> findAllByPayrollRunIdOrderByCreatedAt(UUID payrollRunId);

    List<PayrollAdjustment> findAllByPayrollRunIdAndActiveTrue(UUID payrollRunId);
}
