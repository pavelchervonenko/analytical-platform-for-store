package com.storeanalytics.auth.security;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BreakGlassAccessMonitor {

    private final BreakGlassAccessProperties properties;
    private final AuditLogService auditLogService;

    public BreakGlassAccessMonitor(
            BreakGlassAccessProperties properties,
            AuditLogService auditLogService
    ) {
        this.properties = properties;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public boolean recordSuccessfulLogin(UUID userId) {
        if (!properties.contains(userId)) {
            return false;
        }
        auditLogService.record(
                userId,
                null,
                AuditAction.BREAK_GLASS_LOGIN_SUCCEEDED,
                new AuditTarget(AuditEntityType.USER, userId),
                null,
                null,
                Map.of("accessMode", "break_glass")
        );
        return true;
    }
}
