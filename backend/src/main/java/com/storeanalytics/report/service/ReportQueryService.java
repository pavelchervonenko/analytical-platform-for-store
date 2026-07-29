package com.storeanalytics.report.service;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.web.PageParameters;
import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.metrics.repository.ReportSummaryProjection;
import com.storeanalytics.report.exception.ReportNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportQueryService {

    private final ReportSnapshotRepository repository;
    private final ReportSnapshotCodec codec;

    public ReportQueryService(
            ReportSnapshotRepository repository,
            ReportSnapshotCodec codec
    ) {
        this.repository = repository;
        this.codec = codec;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportSummaryView> list(
            UUID storeId,
            Integer year,
            ReportType type,
            int page,
            int size
    ) {
        validateYear(year);
        PageParameters parameters = new PageParameters(page, size);
        LocalDateRange range = yearRange(year);
        return PageResponse.from(repository.findArchiveSummaries(
                storeId,
                range.start(),
                range.end(),
                type == null ? List.of(ReportType.values()) : List.of(type),
                ReportStatus.FINALIZED,
                parameters.pageable(Sort.unsorted())
        ).map(this::summary));
    }

    @Transactional(readOnly = true)
    public List<Integer> years(UUID storeId) {
        return repository.findFinalizedYears(storeId);
    }

    @Transactional(readOnly = true)
    public ReportDetailView get(UUID storeId, UUID reportId) {
        ReportSnapshot report = repository.findById(reportId)
                .filter(candidate -> candidate.getStatus() == ReportStatus.FINALIZED)
                .filter(candidate -> candidate.getStore().getId().equals(storeId))
                .orElseThrow(() -> new ReportNotFoundException(reportId));
        boolean current = !repository
                .existsByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusAndRevisionGreaterThan(
                        storeId,
                        report.getReportType(),
                        report.getPeriodStart(),
                        report.getPeriodEnd(),
                        ReportStatus.FINALIZED,
                        report.getRevision()
                );
        ReportSummaryView summary = summary(report, current);
        if (report.getReportType() == ReportType.MONTHLY) {
            return new ReportDetailView(summary, codec.decodeMonthly(report), null);
        }
        return new ReportDetailView(summary, null, codec.decodeAnnual(report));
    }

    private ReportSummaryView summary(ReportSummaryProjection report) {
        ReportActorView actor = report.getFinalizedById() == null
                ? null : new ReportActorView(
                        report.getFinalizedById(),
                        report.getFinalizedByDisplayName()
                );
        return new ReportSummaryView(
                report.getId(),
                report.getStoreId(),
                report.getType(),
                report.getPeriodStart(),
                report.getPeriodEnd(),
                coverage(report.getType(), report.getPeriodStart()),
                report.getStatus(),
                report.getRevision(),
                Boolean.TRUE.equals(report.getCurrentRevision()),
                report.getSupersedesReportId(),
                report.getRevisionReason(),
                report.getPayrollRunId(),
                report.getTemplateVersion(),
                report.getSchemaVersion(),
                report.getFinalizedAt(),
                actor
        );
    }

    private ReportSummaryView summary(ReportSnapshot report, boolean current) {
        return new ReportSummaryView(
                report.getId(),
                report.getStore().getId(),
                report.getReportType(),
                report.getPeriodStart(),
                report.getPeriodEnd(),
                coverage(report),
                report.getStatus(),
                report.getRevision(),
                current,
                id(report.getSupersedes()),
                report.getRevisionReason(),
                report.getPayrollRun() == null ? null : report.getPayrollRun().getId(),
                report.getTemplateVersion(),
                report.getSchemaVersion(),
                report.getGeneratedAt(),
                report.getGeneratedBy() == null
                        ? null : new ReportActorView(
                                report.getGeneratedBy().getId(),
                                report.getGeneratedBy().getDisplayName()
                        )
        );
    }

    private ReportCoverageStatus coverage(ReportSnapshot report) {
        return coverage(report.getReportType(), report.getPeriodStart());
    }

    private ReportCoverageStatus coverage(
            ReportType type,
            java.time.LocalDate periodStart
    ) {
        return type == ReportType.ANNUAL && periodStart.getMonthValue() != 1
                ? ReportCoverageStatus.PARTIAL_FIRST_YEAR
                : ReportCoverageStatus.COMPLETE;
    }

    private void validateYear(Integer year) {
        if (year != null && (year < 2000 || year > 2100)) {
            throw new InvalidRequestException(
                    "report year is outside supported range"
            );
        }
    }

    private LocalDateRange yearRange(Integer year) {
        return year == null
                ? new LocalDateRange(
                        java.time.LocalDate.of(1, 1, 1),
                        java.time.LocalDate.of(9999, 12, 31)
                )
                : new LocalDateRange(
                        java.time.LocalDate.of(year, 1, 1),
                        java.time.LocalDate.of(year, 12, 31)
                );
    }

    private UUID id(ReportSnapshot report) {
        return report == null ? null : report.getId();
    }

    private record LocalDateRange(
            java.time.LocalDate start,
            java.time.LocalDate end
    ) {
    }
}
