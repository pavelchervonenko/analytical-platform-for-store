package com.storeanalytics.salary.service;

import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.common.idempotency.IdempotencyService;
import com.storeanalytics.report.service.MonthlyReportFinalizationService;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
class PayrollManagementSupport {

    private final Clock clock;
    private final MonthlyReportFinalizationService reportFinalizationService;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    PayrollManagementSupport(
            Clock clock,
            AuditLogService auditLogService,
            MonthlyReportFinalizationService reportFinalizationService,
            IdempotencyService idempotencyService
    ) {
        this.clock = clock;
        this.auditLogService = auditLogService;
        this.reportFinalizationService = reportFinalizationService;
        this.idempotencyService = idempotencyService;
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

    IdempotencyService idempotency() {
        return idempotencyService;
    }

}
