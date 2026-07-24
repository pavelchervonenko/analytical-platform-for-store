package com.storeanalytics.salary.service;

import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.report.service.MonthlyReportFinalizationService;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
class PayrollManagementSupport {

    private final Clock clock;
    private final MonthlyReportFinalizationService reportFinalizationService;
    private final AuditLogService auditLogService;

    PayrollManagementSupport(
            Clock clock,
            AuditLogService auditLogService,
            MonthlyReportFinalizationService reportFinalizationService
    ) {
        this.clock = clock;
        this.auditLogService = auditLogService;
        this.reportFinalizationService = reportFinalizationService;
    }

    Clock clock() {
        return clock;
    }

    AuditLogService auditLog() {
        return auditLogService;
    }

    MonthlyReportFinalizationService reportFinalization() {
        return reportFinalizationService;
    }

}
