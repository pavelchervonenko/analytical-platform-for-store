package com.storeanalytics.sync.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.sync.service.ManualSyncAuditService;
import com.storeanalytics.sync.service.ManualSyncAuditSummary;
import com.storeanalytics.sync.service.EmployeeSyncResult;
import com.storeanalytics.sync.service.EmployeeSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class EmployeeSyncController {

    private final EmployeeSyncService employeeSyncService;
    private final ManualSyncAuditService auditService;

    public EmployeeSyncController(
            EmployeeSyncService employeeSyncService,
            ManualSyncAuditService auditService
    ) {
        this.employeeSyncService = employeeSyncService;
        this.auditService = auditService;
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.OK)
    EmployeeSyncResult synchronizeEmployees(
            Authentication authentication
    ) {
        EmployeeSyncResult result = employeeSyncService.synchronize();
        auditService.record(
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId(),
                "EMPLOYEES",
                new ManualSyncAuditSummary(
                        result.syncRunId(),
                        result.status(),
                        result.recordsFetched(),
                        result.recordsCreated(),
                        result.recordsUpdated(),
                        result.recordsSkipped(),
                        result.recordsFailed()
                )
        );
        return result;
    }
}
