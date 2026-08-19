package com.storeanalytics.integration.livesklad.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.common.exception.InvalidRequestException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveSkladReturnRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    private LiveSkladWebhookStore store;
    private AuditLogService auditLogService;
    private LiveSkladReturnRecoveryService service;

    @BeforeEach
    void setUp() {
        store = mock(LiveSkladWebhookStore.class);
        auditLogService = mock(AuditLogService.class);
        service = new LiveSkladReturnRecoveryService(
                store,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                auditLogService
        );
    }

    @Test
    void queuesCanonicalValidatedRecoveryAndAuditsIt() {
        UUID requestedBy = UUID.randomUUID();
        when(store.findRecoveryByRequesterAndKey(
                requestedBy, "recovery-F000381"
        )).thenReturn(Optional.empty());
        when(store.findRecoveryByExternalId(
                "6a6daeadaa17fa79fe127335"
        )).thenReturn(Optional.empty());
        when(store.createRecovery(any())).thenAnswer(invocation -> {
            LiveSkladReturnRecoveryRequest request = invocation.getArgument(0);
            return view(request.id());
        });

        LiveSkladReturnRecoveryView result = service.request(
                requestedBy,
                "recovery-F000381",
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030"),
                2,
                "Restore verified report discrepancy"
        );

        assertThat(result.externalId())
                .isEqualTo("6a6daeadaa17fa79fe127335");
        verify(store).lockRecoveryCreation();
        verify(auditLogService).record(
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void returnsSameRequestForIdempotentReplay() {
        UUID requestedBy = UUID.randomUUID();
        LiveSkladReturnRecoveryView existing = view(UUID.randomUUID());
        when(store.findRecoveryByRequesterAndKey(
                requestedBy, "recovery-F000381"
        )).thenReturn(Optional.of(existing));

        LiveSkladReturnRecoveryView replay = service.request(
                requestedBy,
                "recovery-F000381",
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                2,
                "Same request"
        );

        assertThat(replay).isSameAs(existing);
        verify(store, never()).createRecovery(any());
        verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void rejectsMalformedExternalIdBeforeQueueing() {
        assertThatThrownBy(() -> service.request(
                UUID.randomUUID(),
                "recovery-F000381",
                "not-an-id",
                "F000381",
                new BigDecimal("15030.00"),
                2,
                "Invalid request"
        )).isInstanceOf(InvalidRequestException.class);

        verify(store, never()).createRecovery(any());
    }

    private LiveSkladReturnRecoveryView view(UUID id) {
        return new LiveSkladReturnRecoveryView(
                id,
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                2,
                "RECEIVED",
                0,
                false,
                null,
                NOW,
                null
        );
    }
}
