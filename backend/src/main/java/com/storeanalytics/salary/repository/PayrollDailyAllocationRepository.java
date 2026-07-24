package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollDailyAllocation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayrollDailyAllocationRepository
        extends JpaRepository<PayrollDailyAllocation, UUID> {

    @Query("""
            select allocation
            from PayrollDailyAllocation allocation
            join fetch allocation.employee employee
            where allocation.payrollRun.id = :payrollRunId
            order by allocation.workDate, employee.fullName
            """)
    List<PayrollDailyAllocation> findAllByPayrollRunIdOrderByWorkDateEmployeeFullName(
            @Param("payrollRunId") UUID payrollRunId
    );

    long deleteAllByPayrollRunId(UUID payrollRunId);
}
