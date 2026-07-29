package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
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

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, UUID> {

    Optional<ReportSnapshot> findFirstByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusOrderByRevisionDesc(
            UUID storeId,
            ReportType reportType,
            LocalDate periodStart,
            LocalDate periodEnd,
            ReportStatus status
    );

    @Query(
            value = """
                    select
                        report.id as id,
                        report.store.id as storeId,
                        report.reportType as type,
                        report.periodStart as periodStart,
                        report.periodEnd as periodEnd,
                        report.status as status,
                        report.revision as revision,
                        case when report.revision = (
                            select max(candidate.revision)
                            from ReportSnapshot candidate
                            where candidate.store.id = report.store.id
                              and candidate.reportType = report.reportType
                              and candidate.periodStart = report.periodStart
                              and candidate.periodEnd = report.periodEnd
                              and candidate.status = :status
                        ) then true else false end as currentRevision,
                        report.supersedes.id as supersedesReportId,
                        report.revisionReason as revisionReason,
                        report.payrollRun.id as payrollRunId,
                        report.templateVersion as templateVersion,
                        report.schemaVersion as schemaVersion,
                        report.generatedAt as finalizedAt,
                        generatedBy.id as finalizedById,
                        generatedBy.displayName as finalizedByDisplayName
                    from ReportSnapshot report
                    left join report.generatedBy generatedBy
                    where report.store.id = :storeId
                      and report.status = :status
                      and report.periodEnd between :yearStart and :yearEnd
                      and report.reportType in :types
                    order by report.periodEnd desc,
                             report.revision desc,
                             report.id desc
                    """,
            countQuery = """
                    select count(report)
                    from ReportSnapshot report
                    where report.store.id = :storeId
                      and report.status = :status
                      and report.periodEnd between :yearStart and :yearEnd
                      and report.reportType in :types
                    """
    )
    Page<ReportSummaryProjection> findArchiveSummaries(
            @Param("storeId") UUID storeId,
            @Param("yearStart") LocalDate yearStart,
            @Param("yearEnd") LocalDate yearEnd,
            @Param("types") Collection<ReportType> types,
            @Param("status") ReportStatus status,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT DISTINCT EXTRACT(YEAR FROM period_end)::integer
                    FROM report_snapshots
                    WHERE store_id = :storeId
                      AND status = 'FINALIZED'
                    ORDER BY 1 DESC
                    """,
            nativeQuery = true
    )
    List<Integer> findFinalizedYears(@Param("storeId") UUID storeId);

    List<ReportSnapshot> findAllByStoreIdAndReportTypeAndStatusAndPeriodStartBetweenOrderByPeriodStartAscRevisionDesc(
            UUID storeId,
            ReportType reportType,
            ReportStatus status,
            LocalDate from,
            LocalDate through
    );

    Optional<ReportSnapshot> findByPayrollRunId(UUID payrollRunId);

    boolean existsByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusAndRevisionGreaterThan(
            UUID storeId,
            ReportType reportType,
            LocalDate periodStart,
            LocalDate periodEnd,
            ReportStatus status,
            int revision
    );
}
