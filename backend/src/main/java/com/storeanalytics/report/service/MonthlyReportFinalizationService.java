package com.storeanalytics.report.service;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.metrics.model.ReportContent;
import com.storeanalytics.metrics.model.ReportDefinition;
import com.storeanalytics.metrics.model.ReportIntegrity;
import com.storeanalytics.metrics.model.ReportPeriodType;
import com.storeanalytics.metrics.model.ReportRevision;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.service.PayrollRunDetailView;
import com.storeanalytics.store.model.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonthlyReportFinalizationService {

    static final int SCHEMA_VERSION = 1;
    static final String TEMPLATE_VERSION = "store-monthly-report-v1";
    static final String DATA_CONTRACT_VERSION = "store-report-data-v1";

    private final ReportSnapshotRepository repository;
    private final ReportSnapshotCodec codec;
    private final MonthlyReportCalculators calculators;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public MonthlyReportFinalizationService(
            ReportSnapshotRepository repository,
            ReportSnapshotCodec codec,
            MonthlyReportCalculators calculators,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.repository = repository;
        this.codec = codec;
        this.calculators = calculators;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReportSnapshot finalizePaidRun(
            PayrollRun payrollRun,
            PayrollRunDetailView payroll,
            AppUser actor
    ) {
        PayrollRun run = requireNonNull(payrollRun, "payrollRun");
        PayrollRunDetailView detail = requireNonNull(payroll, "payroll");
        require(run.getStatus() == PayrollRunStatus.PAID,
                "monthly reports can only be finalized for paid payroll");
        require(detail.run().id().equals(run.getId())
                        && detail.run().status() == PayrollRunStatus.PAID,
                "payroll detail must describe the paid run");
        AppUser finalizedBy = requireNonNull(actor, "actor");
        return repository.findByPayrollRunId(run.getId())
                .orElseGet(() -> create(run, detail, finalizedBy));
    }

    private ReportSnapshot create(
            PayrollRun run,
            PayrollRunDetailView payroll,
            AppUser actor
    ) {
        Store store = run.getStore();
        YearMonth month = YearMonth.from(run.getPeriodMonth());
        Instant generatedAt = clock.instant();
        MonthlyReportParts parts = calculators.calculate(store.getId(), month);
        ReportHeader header = new ReportHeader(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getReportingStartedOn(),
                month.atDay(1),
                month.atEndOfMonth(),
                ReportCoverageStatus.COMPLETE,
                TEMPLATE_VERSION,
                DATA_CONTRACT_VERSION,
                generatedAt,
                new ReportActorView(actor.getId(), actor.getDisplayName())
        );
        MonthlyReportPayload report = new MonthlyReportPayload(
                SCHEMA_VERSION,
                header,
                parts.storeKpi(),
                parts.categoryKpi(),
                currentAverages(parts.averageKpi()),
                parts.attachRates(),
                parts.planProgress(),
                parts.employeeRating(),
                requireNonNull(payroll, "payroll"),
                parts.quality()
        );
        EncodedReport encoded = codec.encode(report);
        String sourceHash = codec.sourceHash(source(run, parts));
        ReportSnapshot previous = repository
                .findFirstByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusOrderByRevisionDesc(
                        store.getId(),
                        ReportType.MONTHLY,
                        month.atDay(1),
                        month.atEndOfMonth(),
                        ReportStatus.FINALIZED
                ).orElse(null);
        int revision = previous == null ? 1 : previous.getRevision() + 1;
        ReportSnapshot snapshot = repository.saveAndFlush(new ReportSnapshot(
                store,
                new ReportDefinition(
                        ReportType.MONTHLY,
                        ReportPeriodType.MONTH,
                        month.atDay(1),
                        month.atEndOfMonth(),
                        ReportStatus.FINALIZED,
                        TEMPLATE_VERSION,
                        DATA_CONTRACT_VERSION
                ),
                new ReportContent(
                        new ReportIntegrity(sourceHash, encoded.sha256()),
                        encoded.payload(),
                        generatedAt,
                        actor,
                        generatedAt,
                        actor
                ),
                new ReportRevision(
                        revision,
                        previous,
                        run,
                        previous == null ? null : run.getRevisionReason(),
                        SCHEMA_VERSION
                )
        ));
        audit(snapshot, run, actor);
        return snapshot;
    }

    private ReportAverageKpi currentAverages(AverageKpiResult result) {
        return new ReportAverageKpi(
                result.formulaVersion(),
                result.averageReceipt().current(),
                result.additionalRevenuePerPhone().current(),
                result.categoryAveragePrices().stream()
                        .map(category -> new ReportCategoryAverage(
                                category.categoryCode(),
                                category.categoryName(),
                                category.categoryActive(),
                                category.averageUnitPrice().current()
                        ))
                        .toList()
        );
    }

    private MonthlyReportSource source(PayrollRun run, MonthlyReportParts parts) {
        return new MonthlyReportSource(
                run.getId(),
                run.getRevision(),
                run.getSourceFingerprint(),
                parts.employeeRating().formula().version(),
                parts.storeKpi().formulaVersion(),
                parts.categoryKpi().formulaVersion(),
                parts.averageKpi().formulaVersion(),
                parts.attachRates().formulaVersion()
        );
    }

    private void audit(ReportSnapshot snapshot, PayrollRun run, AppUser actor) {
        auditLogService.record(
                actor.getId(),
                run.getStore().getId(),
                AuditAction.MONTHLY_REPORT_FINALIZED,
                new AuditTarget(AuditEntityType.REPORT_SNAPSHOT, snapshot.getId()),
                snapshot.getRevisionReason(),
                null,
                Map.of(
                        "reportType", snapshot.getReportType(),
                        "periodStart", snapshot.getPeriodStart(),
                        "periodEnd", snapshot.getPeriodEnd(),
                        "revision", snapshot.getRevision(),
                        "payrollRunId", run.getId(),
                        "payrollRevision", run.getRevision()
                )
        );
    }
}
