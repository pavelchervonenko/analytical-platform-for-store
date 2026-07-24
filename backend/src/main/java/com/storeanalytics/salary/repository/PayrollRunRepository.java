package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    Optional<PayrollRun> findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
            UUID storeId,
            LocalDate periodMonth
    );

    @Query("""
            select run
            from PayrollRun run
            where run.status in :statuses
              and run.revision = (
                  select max(candidate.revision)
                  from PayrollRun candidate
                  where candidate.store.id = run.store.id
                    and candidate.periodMonth = run.periodMonth
              )
            """)
    List<PayrollRun> findLatestByStatusIn(
            @Param("statuses") Collection<PayrollRunStatus> statuses
    );

    List<PayrollRun> findAllByStoreIdOrderByPeriodMonthDescRevisionDesc(UUID storeId);
}
