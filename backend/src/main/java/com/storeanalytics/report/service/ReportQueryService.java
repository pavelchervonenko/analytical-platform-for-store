package com.storeanalytics.report.service;

import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.report.exception.ReportNotFoundException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    public List<ReportSummaryView> list(
            UUID storeId,
            Integer year,
            ReportType type
    ) {
        List<ReportSnapshot> reports = repository
                .findAllByStoreIdOrderByPeriodEndDescRevisionDesc(storeId)
                .stream()
                .filter(report -> report.getStatus() == ReportStatus.FINALIZED)
                .filter(report -> year == null || report.getPeriodEnd().getYear() == year)
                .filter(report -> type == null || report.getReportType() == type)
                .toList();
        Map<ReportPeriodKey, Integer> latestRevisions = new HashMap<>();
        reports.forEach(report -> latestRevisions.merge(
                new ReportPeriodKey(
                        report.getReportType(),
                        report.getPeriodStart(),
                        report.getPeriodEnd()
                ),
                report.getRevision(),
                Math::max
        ));
        return reports.stream()
                .map(report -> summary(
                        report,
                        report.getRevision() == latestRevisions.get(new ReportPeriodKey(
                                report.getReportType(),
                                report.getPeriodStart(),
                                report.getPeriodEnd()
                        ))
                ))
                .sorted(Comparator.comparing(
                        ReportSummaryView::periodEnd,
                        Comparator.reverseOrder()
                ).thenComparing(
                        ReportSummaryView::revision,
                        Comparator.reverseOrder()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReportDetailView get(UUID storeId, UUID reportId) {
        ReportSnapshot report = repository.findById(reportId)
                .filter(candidate -> candidate.getStatus() == ReportStatus.FINALIZED)
                .filter(candidate -> candidate.getStore().getId().equals(storeId))
                .orElseThrow(() -> new ReportNotFoundException(reportId));
        boolean current = repository
                .findFirstByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusOrderByRevisionDesc(
                        storeId,
                        report.getReportType(),
                        report.getPeriodStart(),
                        report.getPeriodEnd(),
                        ReportStatus.FINALIZED
                )
                .map(latest -> latest.getId().equals(report.getId()))
                .orElse(false);
        ReportSummaryView summary = summary(report, current);
        if (report.getReportType() == ReportType.MONTHLY) {
            return new ReportDetailView(summary, codec.decodeMonthly(report), null);
        }
        return new ReportDetailView(summary, null, codec.decodeAnnual(report));
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
        return report.getReportType() == ReportType.ANNUAL
                && report.getPeriodStart().getMonthValue() != 1
                ? ReportCoverageStatus.PARTIAL_FIRST_YEAR
                : ReportCoverageStatus.COMPLETE;
    }

    private UUID id(ReportSnapshot report) {
        return report == null ? null : report.getId();
    }
}
