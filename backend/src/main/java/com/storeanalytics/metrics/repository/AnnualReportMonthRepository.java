package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.model.AnnualReportMonth;
import com.storeanalytics.metrics.model.AnnualReportMonthId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnualReportMonthRepository
        extends JpaRepository<AnnualReportMonth, AnnualReportMonthId> {

    List<AnnualReportMonth> findAllByAnnualReportIdOrderByIdMonthNumber(UUID annualReportId);
}
