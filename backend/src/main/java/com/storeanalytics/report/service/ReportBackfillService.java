package com.storeanalytics.report.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import com.storeanalytics.salary.service.PayrollManagementService;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportBackfillService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollManagementService payrollService;
    private final ReportSnapshotRepository reportRepository;
    private final MonthlyReportFinalizationService monthlyFinalizationService;
    private final AnnualReportFinalizationService annualFinalizationService;
    private final AppUserRepository userRepository;
    private final AuditLogService auditLogService;

    public ReportBackfillService(
            PayrollRunRepository payrollRunRepository,
            PayrollManagementService payrollService,
            ReportSnapshotRepository reportRepository,
            MonthlyReportFinalizationService monthlyFinalizationService,
            AnnualReportFinalizationService annualFinalizationService,
            AppUserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.payrollRunRepository = payrollRunRepository;
        this.payrollService = payrollService;
        this.reportRepository = reportRepository;
        this.monthlyFinalizationService = monthlyFinalizationService;
        this.annualFinalizationService = annualFinalizationService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ReportBackfillResult backfill(UUID storeId, int year, UUID actorId) {
        if (year < 2000 || year > 2100) {
            throw new InvalidRequestException("report backfill year is outside supported range");
        }
        AppUser actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalArgumentException("actor does not exist"));
        List<PayrollRun> paidRuns = latestPaidRuns(storeId, year);
        int created = 0;
        int existing = 0;
        for (PayrollRun run : paidRuns) {
            if (reportRepository.findByPayrollRunId(run.getId()).isPresent()) {
                existing++;
            } else {
                monthlyFinalizationService.finalizePaidRun(
                        run,
                        payrollService.get(storeId, run.getId()),
                        actor
                );
                created++;
            }
        }
        ReportSnapshot annual = annualFinalizationService
                .finalizeYear(storeId, Year.of(year))
                .orElse(null);
        ReportBackfillResult result = new ReportBackfillResult(
                storeId,
                year,
                paidRuns.size(),
                created,
                existing,
                annual == null ? null : annual.getId()
        );
        audit(actor, result);
        return result;
    }

    private List<PayrollRun> latestPaidRuns(UUID storeId, int year) {
        Map<java.time.LocalDate, PayrollRun> latest = new TreeMap<>();
        payrollRunRepository.findAllByStoreIdOrderByPeriodMonthDescRevisionDesc(storeId)
                .stream()
                .filter(run -> run.getPeriodMonth().getYear() == year)
                .filter(run -> run.getStatus() == PayrollRunStatus.PAID)
                .forEach(run -> latest.putIfAbsent(run.getPeriodMonth(), run));
        return List.copyOf(latest.values());
    }

    private void audit(AppUser actor, ReportBackfillResult result) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("year", result.year());
        after.put("paidMonthCount", result.paidMonthCount());
        after.put("monthlyCreatedCount", result.monthlyCreatedCount());
        after.put("monthlyExistingCount", result.monthlyExistingCount());
        after.put("annualReportId", result.annualReportId());
        auditLogService.record(
                actor.getId(),
                result.storeId(),
                AuditAction.REPORT_BACKFILL_REQUESTED,
                new AuditTarget(AuditEntityType.REPORT_BACKFILL, result.storeId()),
                "Administrative report backfill",
                null,
                after
        );
    }
}
