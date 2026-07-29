package com.storeanalytics.report.service;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.report.exception.ReportBackfillJobNotFoundException;
import com.storeanalytics.report.model.ReportBackfillJob;
import com.storeanalytics.report.repository.ReportBackfillJobRepository;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import com.storeanalytics.salary.service.PayrollManagementService;
import java.time.Clock;
import java.time.Year;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportBackfillJobExecutionService {

    private final ReportBackfillJobRepository jobRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollManagementService payrollService;
    private final ReportSnapshotRepository reportRepository;
    private final MonthlyReportFinalizationService monthlyFinalizationService;
    private final AnnualReportFinalizationService annualFinalizationService;
    private final Clock clock;

    public ReportBackfillJobExecutionService(
            ReportBackfillJobRepository jobRepository,
            PayrollRunRepository payrollRunRepository,
            PayrollManagementService payrollService,
            ReportSnapshotRepository reportRepository,
            MonthlyReportFinalizationService monthlyFinalizationService,
            AnnualReportFinalizationService annualFinalizationService,
            Clock clock
    ) {
        this.jobRepository = jobRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.payrollService = payrollService;
        this.reportRepository = reportRepository;
        this.monthlyFinalizationService = monthlyFinalizationService;
        this.annualFinalizationService = annualFinalizationService;
        this.clock = clock;
    }

    @Transactional
    public void execute(ReportBackfillJobClaim claim, String owner) {
        ReportBackfillJob job = jobRepository.findByIdForUpdate(claim.jobId())
                .orElseThrow(() -> new ReportBackfillJobNotFoundException(
                        claim.jobId()
                ));
        if (job.cancelClaimedIfRequested(owner, clock.instant())) {
            return;
        }
        switch (job.getPhase()) {
            case MONTHLY -> completeMonthly(job, owner);
            case ANNUAL -> completeAnnual(job, owner);
            default -> throw new IllegalStateException(
                    "Unsupported report backfill phase"
            );
        }
    }

    private void completeMonthly(ReportBackfillJob job, String owner) {
        ReportBackfillMonthlyResult result = processMonth(job);
        job.completeMonthlyStep(
                owner,
                result.paidMonth(),
                result.created(),
                clock.instant()
        );
    }

    private ReportBackfillMonthlyResult processMonth(ReportBackfillJob job) {
        YearMonth month = YearMonth.of(job.getYear(), job.getCursorMonth());
        Optional<PayrollRun> paidRun = payrollRunRepository
                .findFirstByStoreIdAndPeriodMonthAndStatusOrderByRevisionDesc(
                        job.getStore().getId(),
                        month.atDay(1),
                        PayrollRunStatus.PAID
                );
        if (paidRun.isEmpty()) {
            return ReportBackfillMonthlyResult.withoutPaidPayroll();
        }
        PayrollRun run = paidRun.get();
        if (reportRepository.findByPayrollRunId(run.getId()).isPresent()) {
            return ReportBackfillMonthlyResult.existing();
        }
        AppUser actor = job.getRequestedBy();
        if (actor == null) {
            throw new IllegalStateException(
                    "Report backfill requesting user no longer exists"
            );
        }
        monthlyFinalizationService.finalizePaidRun(
                run,
                payrollService.get(job.getStore().getId(), run.getId()),
                actor
        );
        return ReportBackfillMonthlyResult.createdNew();
    }

    private void completeAnnual(ReportBackfillJob job, String owner) {
        ReportSnapshot annual = annualFinalizationService.finalizeYear(
                job.getStore().getId(),
                Year.of(job.getYear())
        ).orElse(null);
        job.completeAnnualStep(owner, annual, clock.instant());
    }
}
