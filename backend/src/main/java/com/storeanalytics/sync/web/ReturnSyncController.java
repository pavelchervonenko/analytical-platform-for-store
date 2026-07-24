package com.storeanalytics.sync.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.sync.service.ManualSyncAuditService;
import com.storeanalytics.sync.service.ManualSyncAuditSummary;
import com.storeanalytics.sync.service.ReturnSyncPeriod;
import com.storeanalytics.sync.service.ReturnSyncResult;
import com.storeanalytics.sync.service.ReturnSyncService;
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
public class ReturnSyncController {

    private final ReturnSyncService returnSyncService;
    private final ManualSyncAuditService auditService;

    public ReturnSyncController(
            ReturnSyncService returnSyncService,
            ManualSyncAuditService auditService
    ) {
        this.returnSyncService = returnSyncService;
        this.auditService = auditService;
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.OK)
    ReturnSyncResult synchronizeReturns(
            @Valid @RequestBody ReturnSyncRequest request,
            Authentication authentication
    ) {
        ReturnSyncResult result = returnSyncService.synchronize(new ReturnSyncPeriod(
                request.periodStart(),
                request.periodEnd()
        ));
        auditService.record(
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId(),
                "RETURNS",
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
