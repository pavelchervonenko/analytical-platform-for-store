package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollDailyPool;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollDailyPoolRepository extends JpaRepository<PayrollDailyPool, UUID> {

    List<PayrollDailyPool> findAllByPayrollRunIdOrderByWorkDate(UUID payrollRunId);

    long deleteAllByPayrollRunId(UUID payrollRunId);
}
