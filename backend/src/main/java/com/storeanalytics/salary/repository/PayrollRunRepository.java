package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    Optional<PayrollRun> findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
            UUID storeId,
            LocalDate periodMonth
    );

    Optional<PayrollRun> findFirstByStoreIdAndPeriodMonthAndStatusOrderByRevisionDesc(
            UUID storeId,
            LocalDate periodMonth,
            PayrollRunStatus status
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

    @Query(
            value = """
                    select
                        run.id as id,
                        run.store.id as storeId,
                        run.periodMonth as periodMonth,
                        run.revision as revision,
                        run.supersedes.id as supersedesRunId,
                        run.revisionReason as revisionReason,
                        run.status as status,
                        run.createdAt as createdAt
                    from PayrollRun run
                    where run.store.id = :storeId
                      and run.periodMonth between :monthStart and :monthEnd
                    order by run.periodMonth desc,
                             run.revision desc,
                             run.id desc
                    """,
            countQuery = """
                    select count(run)
                    from PayrollRun run
                    where run.store.id = :storeId
                      and run.periodMonth between :monthStart and :monthEnd
                    """
    )
    Page<PayrollRunListProjection> findListItems(
            @Param("storeId") UUID storeId,
            @Param("monthStart") LocalDate monthStart,
            @Param("monthEnd") LocalDate monthEnd,
            Pageable pageable
    );
}
