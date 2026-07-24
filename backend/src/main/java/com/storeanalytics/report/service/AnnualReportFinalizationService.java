package com.storeanalytics.report.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.model.AnnualReportMonth;
import com.storeanalytics.metrics.model.ReportContent;
import com.storeanalytics.metrics.model.ReportDefinition;
import com.storeanalytics.metrics.model.ReportIntegrity;
import com.storeanalytics.metrics.model.ReportPeriodType;
import com.storeanalytics.metrics.model.ReportRevision;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.repository.AnnualReportMonthRepository;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnualReportFinalizationService {

    static final String TEMPLATE_VERSION = "store-annual-report-v1";

    private final StoreRepository storeRepository;
    private final ReportSnapshotRepository reportRepository;
    private final AnnualReportMonthRepository monthRepository;
    private final ReportSnapshotCodec codec;
    private final AnnualReportAggregationService aggregationService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AnnualReportFinalizationService(
            StoreRepository storeRepository,
            ReportSnapshotRepository reportRepository,
            AnnualReportMonthRepository monthRepository,
            ReportSnapshotCodec codec,
            AnnualReportAggregationService aggregationService,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.storeRepository = storeRepository;
        this.reportRepository = reportRepository;
        this.monthRepository = monthRepository;
        this.codec = codec;
        this.aggregationService = aggregationService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public Optional<ReportSnapshot> finalizeYear(UUID storeId, Year year) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        if (!closed(store, year) || store.getReportingStartedOn().getYear() > year.getValue()) {
            return Optional.empty();
        }
        LocalDate yearStart = year.atDay(1);
        LocalDate yearEnd = year.atMonth(12).atEndOfMonth();
        Map<YearMonth, ReportSnapshot> latest = latestMonthlyReports(
                store.getId(), yearStart, yearEnd
        );
        YearMonth firstExpected = store.getReportingStartedOn().getYear() == year.getValue()
                ? YearMonth.from(store.getReportingStartedOn())
                : YearMonth.of(year.getValue(), 1);
        List<ReportSnapshot> sourceReports = expectedReports(firstExpected, latest);
        if (sourceReports.isEmpty()) {
            return Optional.empty();
        }
        String sourceHash = sourceHash(sourceReports);
        ReportSnapshot previous = reportRepository
                .findFirstByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusOrderByRevisionDesc(
                        store.getId(),
                        ReportType.ANNUAL,
                        firstExpected.atDay(1),
                        yearEnd,
                        ReportStatus.FINALIZED
                ).orElse(null);
        if (previous != null && sourceHash.equals(previous.getSourceHash())) {
            return Optional.of(previous);
        }
        return Optional.of(create(
                store, firstExpected, yearEnd, sourceReports, sourceHash, previous
        ));
    }

    private boolean closed(Store store, Year year) {
        int currentYear = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone()))).getYear();
        return year.getValue() < currentYear;
    }

    private Map<YearMonth, ReportSnapshot> latestMonthlyReports(
            UUID storeId,
            LocalDate from,
            LocalDate through
    ) {
        Map<YearMonth, ReportSnapshot> latest = new LinkedHashMap<>();
        reportRepository
                .findAllByStoreIdAndReportTypeAndStatusAndPeriodStartBetweenOrderByPeriodStartAscRevisionDesc(
                        storeId, ReportType.MONTHLY, ReportStatus.FINALIZED, from, through
                )
                .forEach(report -> latest.putIfAbsent(
                        YearMonth.from(report.getPeriodStart()), report
                ));
        return latest;
    }

    private List<ReportSnapshot> expectedReports(
            YearMonth firstExpected,
            Map<YearMonth, ReportSnapshot> available
    ) {
        List<ReportSnapshot> reports = new ArrayList<>();
        YearMonth month = firstExpected;
        YearMonth december = YearMonth.of(firstExpected.getYear(), 12);
        while (!month.isAfter(december)) {
            ReportSnapshot report = available.get(month);
            if (report == null) {
                return List.of();
            }
            reports.add(report);
            month = month.plusMonths(1);
        }
        return List.copyOf(reports);
    }

    private String sourceHash(List<ReportSnapshot> reports) {
        return codec.sourceHash(new AnnualReportSource(
                reports.stream()
                        .map(report -> new AnnualReportSourceMonth(
                                report.getId(),
                                report.getRevision(),
                                report.getPayloadHash()
                        ))
                        .toList()
        ));
    }

    private ReportSnapshot create(
            Store store,
            YearMonth firstExpected,
            LocalDate periodEnd,
            List<ReportSnapshot> sourceReports,
            String sourceHash,
            ReportSnapshot previous
    ) {
        Instant generatedAt = clock.instant();
        List<AnnualReportMonthPayload> months = sourceReports.stream()
                .map(report -> new AnnualReportMonthPayload(
                        report.getId(),
                        report.getRevision(),
                        report.getPayloadHash(),
                        codec.decodeMonthly(report)
                ))
                .toList();
        AnnualReportAggregate aggregate = aggregationService.aggregate(
                months.stream().map(AnnualReportMonthPayload::report).toList()
        );
        ReportHeader header = new ReportHeader(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getReportingStartedOn(),
                firstExpected.atDay(1),
                periodEnd,
                firstExpected.getMonthValue() == 1
                        ? ReportCoverageStatus.COMPLETE
                        : ReportCoverageStatus.PARTIAL_FIRST_YEAR,
                TEMPLATE_VERSION,
                MonthlyReportFinalizationService.DATA_CONTRACT_VERSION,
                generatedAt,
                null
        );
        EncodedReport encoded = codec.encode(new AnnualReportPayload(
                MonthlyReportFinalizationService.SCHEMA_VERSION,
                header,
                aggregate.totals(),
                aggregate.categories(),
                aggregate.attachRates(),
                aggregate.employees(),
                months
        ));
        int revision = previous == null ? 1 : previous.getRevision() + 1;
        String reason = previous == null ? null : "MONTHLY_REPORT_REVISIONS_CHANGED";
        ReportSnapshot annual = reportRepository.saveAndFlush(new ReportSnapshot(
                store,
                new ReportDefinition(
                        ReportType.ANNUAL,
                        ReportPeriodType.YEAR,
                        firstExpected.atDay(1),
                        periodEnd,
                        ReportStatus.FINALIZED,
                        TEMPLATE_VERSION,
                        MonthlyReportFinalizationService.DATA_CONTRACT_VERSION
                ),
                new ReportContent(
                        new ReportIntegrity(sourceHash, encoded.sha256()),
                        encoded.payload(),
                        generatedAt,
                        null,
                        generatedAt,
                        null
                ),
                new ReportRevision(
                        revision,
                        previous,
                        null,
                        reason,
                        MonthlyReportFinalizationService.SCHEMA_VERSION
                )
        ));
        monthRepository.saveAll(sourceReports.stream()
                .map(report -> new AnnualReportMonth(
                        annual, report, report.getPeriodStart().getMonthValue()
                ))
                .toList());
        monthRepository.flush();
        audit(annual, sourceReports.size(), reason);
        return annual;
    }

    private void audit(ReportSnapshot report, int monthCount, String reason) {
        auditLogService.recordSystem(
                report.getStore().getId(),
                AuditAction.ANNUAL_REPORT_FINALIZED,
                new AuditTarget(AuditEntityType.REPORT_SNAPSHOT, report.getId()),
                reason,
                null,
                Map.of(
                        "reportType", report.getReportType(),
                        "periodStart", report.getPeriodStart(),
                        "periodEnd", report.getPeriodEnd(),
                        "revision", report.getRevision(),
                        "monthCount", monthCount
                )
        );
    }
}
