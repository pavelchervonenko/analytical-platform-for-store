package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, UUID> {

    Optional<ReportSnapshot> findFirstByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusOrderByRevisionDesc(
            UUID storeId,
            ReportType reportType,
            LocalDate periodStart,
            LocalDate periodEnd,
            ReportStatus status
    );

    List<ReportSnapshot> findAllByStoreIdOrderByPeriodEndDescRevisionDesc(UUID storeId);

    List<ReportSnapshot> findAllByStoreIdAndReportTypeAndStatusAndPeriodStartBetweenOrderByPeriodStartAscRevisionDesc(
            UUID storeId,
            ReportType reportType,
            ReportStatus status,
            LocalDate from,
            LocalDate through
    );

    Optional<ReportSnapshot> findByPayrollRunId(UUID payrollRunId);
}
