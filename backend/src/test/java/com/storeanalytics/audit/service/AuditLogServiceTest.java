package com.storeanalytics.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.audit.model.AuditLog;
import com.storeanalytics.audit.model.AuditRetention;
import com.storeanalytics.audit.model.AuditRetentionClass;
import com.storeanalytics.audit.repository.AuditLogRepository;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class AuditLogServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");


    @Test
    void storesVersionedSafeBeforeAndAfterSummary() throws Exception {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ApplicationEventPublisher eventPublisher = mock(
                ApplicationEventPublisher.class);
        UUID actorId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AppUser actor = mock(AppUser.class);
        Store store = mock(Store.class);
        when(entityManager.getReference(AppUser.class, actorId)).thenReturn(actor);
        when(entityManager.getReference(Store.class, storeId)).thenReturn(store);
        AuditLogService service = service(
                repository,
                entityManager,
                objectMapper,
                eventPublisher,
                AuditAction.PAYROLL_APPROVED,
                new AuditRetention(AuditRetentionClass.FINANCIAL, null)
        );

        service.record(
                actorId,
                storeId,
                AuditAction.PAYROLL_APPROVED,
                new AuditTarget(AuditEntityType.PAYROLL_RUN, targetId),
                "Monthly approval",
                Map.of(
                        "status", "CALCULATED",
                        "accessToken", "must-not-be-stored"
                ),
                Map.of(
                        "status", "APPROVED",
                        "amount", new BigDecimal("125000.00"),
                        "periodMonth", LocalDate.of(2026, 7, 1)
                )
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        JsonNode metadata = objectMapper.readTree(saved.getMetadata());
        assertThat(saved.getActorUser()).isSameAs(actor);
        assertThat(saved.getStore()).isSameAs(store);
        assertThat(saved.getRetentionClass()).isEqualTo(AuditRetentionClass.FINANCIAL);
        assertThat(saved.getRetainUntil()).isNull();
        assertThat(saved.getAction()).isEqualTo("PAYROLL_APPROVED");
        assertThat(saved.getEntityType()).isEqualTo("PAYROLL_RUN");
        assertThat(metadata.path("schemaVersion").asInt()).isOne();
        assertThat(metadata.path("reason").asString()).isEqualTo("Monthly approval");
        assertThat(metadata.path("before").path("accessToken").asString())
                .isEqualTo("[REDACTED]");
        assertThat(metadata.path("after").path("amount").decimalValue())
                .isEqualByComparingTo("125000.00");
        assertThat(metadata.path("after").path("periodMonth").asString())
                .isEqualTo("2026-07-01");
        ArgumentCaptor<AuditMonitoringEvent> eventCaptor = ArgumentCaptor.forClass(
                AuditMonitoringEvent.class
        );
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new AuditMonitoringEvent(
                AuditAction.PAYROLL_APPROVED, actorId, targetId.toString()
        ));
    }

    @Test
    void rejectsArbitraryDomainObjectsFromSummary() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ApplicationEventPublisher eventPublisher = mock(
                ApplicationEventPublisher.class);
        UUID actorId = UUID.randomUUID();
        when(entityManager.getReference(AppUser.class, actorId))
                .thenReturn(mock(AppUser.class));
        AuditLogService service = service(
                repository,
                entityManager,
                new ObjectMapper(),
                eventPublisher,
                AuditAction.USER_CHANGED,
                new AuditRetention(AuditRetentionClass.SECURITY, NOW.plusSeconds(1))
        );

        assertThatThrownBy(() -> service.record(
                actorId,
                null,
                AuditAction.USER_CHANGED,
                new AuditTarget(AuditEntityType.USER, UUID.randomUUID()),
                null,
                null,
                Map.of("unsafe", new Object())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported audit summary value type");

        verifyNoInteractions(repository, eventPublisher);
    }

    private AuditLogService service(
            AuditLogRepository repository,
            EntityManager entityManager,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            AuditAction action,
            AuditRetention retention
    ) {
        AuditRetentionPolicy policy = mock(AuditRetentionPolicy.class);
        when(policy.retention(action, NOW)).thenReturn(retention);
        return new AuditLogService(
                repository,
                entityManager,
                objectMapper,
                policy,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
