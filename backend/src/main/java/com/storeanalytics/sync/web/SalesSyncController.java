package com.storeanalytics.sync.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.sync.service.ManualSyncAuditService;
import com.storeanalytics.sync.service.ManualSyncAuditSummary;
import com.storeanalytics.sync.service.SalesSyncPeriod;
import com.storeanalytics.sync.service.SalesSyncResult;
import com.storeanalytics.sync.service.SalesSyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SalesSyncController {

    private final SalesSyncService salesSyncService;
    private final ManualSyncAuditService auditService;

    public SalesSyncController(
            SalesSyncService salesSyncService,
            ManualSyncAuditService auditService
    ) {
        this.salesSyncService = salesSyncService;
        this.auditService = auditService;
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.OK)
    SalesSyncResult synchronizeSales(
            @Valid @RequestBody SalesSyncRequest request,
            Authentication authentication
    ) {
        SalesSyncResult result = salesSyncService.synchronize(new SalesSyncPeriod(
                request.periodStart(),
                request.periodEnd()
        ));
        auditService.record(
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId(),
                "SALES",
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
