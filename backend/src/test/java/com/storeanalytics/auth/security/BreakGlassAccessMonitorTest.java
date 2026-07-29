package com.storeanalytics.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BreakGlassAccessMonitorTest {

    @Test
    void persistsEverySuccessfulEmergencyAccountLogin() {
        UUID userId = UUID.randomUUID();
        AuditLogService auditLogService = mock(AuditLogService.class);
        BreakGlassAccessMonitor monitor = new BreakGlassAccessMonitor(
                new BreakGlassAccessProperties(Set.of(userId)),
                auditLogService
        );

        assertThat(monitor.recordSuccessfulLogin(userId)).isTrue();

        verify(auditLogService).record(
                userId,
                null,
                AuditAction.BREAK_GLASS_LOGIN_SUCCEEDED,
                new AuditTarget(AuditEntityType.USER, userId),
                null,
                null,
                Map.of("accessMode", "break_glass")
        );
    }

    @Test
    void doesNotAddAuditRowsForOrdinaryAccounts() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        BreakGlassAccessMonitor monitor = new BreakGlassAccessMonitor(
                new BreakGlassAccessProperties(Set.of()),
                auditLogService
        );

        assertThat(monitor.recordSuccessfulLogin(UUID.randomUUID())).isFalse();

        verifyNoInteractions(auditLogService);
    }
}
