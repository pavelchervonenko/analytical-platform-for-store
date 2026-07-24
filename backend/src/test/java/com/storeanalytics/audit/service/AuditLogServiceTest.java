package com.storeanalytics.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeanalytics.audit.model.AuditLog;
import com.storeanalytics.audit.repository.AuditLogRepository;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogServiceTest {

    @Test
    void storesVersionedSafeBeforeAndAfterSummary() throws Exception {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID actorId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        AppUser actor = mock(AppUser.class);
        Store store = mock(Store.class);
        when(entityManager.getReference(AppUser.class, actorId)).thenReturn(actor);
        when(entityManager.getReference(Store.class, storeId)).thenReturn(store);
        AuditLogService service = new AuditLogService(
                repository, entityManager, objectMapper
        );

        service.record(
                actorId,
                storeId,
                AuditAction.PAYROLL_APPROVED,
                new AuditTarget(AuditEntityType.PAYROLL_RUN, UUID.randomUUID()),
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
        assertThat(saved.getAction()).isEqualTo("PAYROLL_APPROVED");
        assertThat(saved.getEntityType()).isEqualTo("PAYROLL_RUN");
        assertThat(metadata.path("schemaVersion").asInt()).isOne();
        assertThat(metadata.path("reason").asText()).isEqualTo("Monthly approval");
        assertThat(metadata.path("before").path("accessToken").asText())
                .isEqualTo("[REDACTED]");
        assertThat(metadata.path("after").path("amount").decimalValue())
                .isEqualByComparingTo("125000.00");
        assertThat(metadata.path("after").path("periodMonth").asText())
                .isEqualTo("2026-07-01");
    }

    @Test
    void rejectsArbitraryDomainObjectsFromSummary() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        UUID actorId = UUID.randomUUID();
        when(entityManager.getReference(AppUser.class, actorId))
                .thenReturn(mock(AppUser.class));
        AuditLogService service = new AuditLogService(
                repository, entityManager, new ObjectMapper()
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

        verifyNoInteractions(repository);
    }
}
