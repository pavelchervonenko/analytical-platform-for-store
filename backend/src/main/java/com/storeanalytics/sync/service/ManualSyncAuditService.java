package com.storeanalytics.sync.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ManualSyncAuditService {

    private final AuditLogService auditLogService;

    public ManualSyncAuditService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public void record(
            UUID actorId,
            String scope,
            ManualSyncAuditSummary summary
    ) {
        auditLogService.record(
                actorId,
                null,
                AuditAction.MANUAL_SYNC_STARTED,
                new AuditTarget(AuditEntityType.SYNC_RUN, summary.syncRunId()),
                null,
                null,
                Map.of(
                        "scope", scope,
                        "status", summary.status(),
                        "recordsFetched", summary.recordsFetched(),
                        "recordsCreated", summary.recordsCreated(),
                        "recordsUpdated", summary.recordsUpdated(),
                        "recordsSkipped", summary.recordsSkipped(),
                        "recordsFailed", summary.recordsFailed()
                )
        );
    }
}
