package com.storeanalytics.audit.service;

import java.util.UUID;

public record AuditMonitoringEvent(
        AuditAction action,
        UUID actorUserId,
        String targetId
) {
}
