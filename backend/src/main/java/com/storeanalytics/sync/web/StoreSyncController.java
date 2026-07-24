package com.storeanalytics.sync.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.sync.service.ManualSyncAuditService;
import com.storeanalytics.sync.service.ManualSyncAuditSummary;
import com.storeanalytics.sync.service.StoreSyncResult;
import com.storeanalytics.sync.service.StoreSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class StoreSyncController {

    private final StoreSyncService storeSyncService;
    private final ManualSyncAuditService auditService;

    public StoreSyncController(
            StoreSyncService storeSyncService,
            ManualSyncAuditService auditService
    ) {
        this.storeSyncService = storeSyncService;
        this.auditService = auditService;
    }

    @PostMapping("/stores")
    @ResponseStatus(HttpStatus.OK)
    StoreSyncResult synchronizeStores(
            Authentication authentication
    ) {
        StoreSyncResult result = storeSyncService.synchronize();
        auditService.record(
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId(),
                "STORES",
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
