package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollEventRepository extends JpaRepository<PayrollEvent, UUID> {

    List<PayrollEvent> findAllByPayrollRunIdOrderByCreatedAt(UUID payrollRunId);
}
