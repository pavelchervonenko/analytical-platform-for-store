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
import java.util.List;
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
        when(store.findRecoveryByExternalIdAndMode(
                "6a6daeadaa17fa79fe127335",
                LiveSkladReturnRecoveryMode.MISSING_RETURN
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
    void queuesZeroNetMissingReturnAndStillRejectsNegativeAmount() {
        UUID requestedBy = UUID.randomUUID();
        when(store.findRecoveryByRequesterAndKey(
                requestedBy, "recovery-F000175"
        )).thenReturn(Optional.empty());
        when(store.findRecoveryByExternalIdAndMode(
                "69c67dbf35f1a26e5b3bc638",
                LiveSkladReturnRecoveryMode.MISSING_RETURN
        )).thenReturn(Optional.empty());
        when(store.createRecovery(any())).thenAnswer(invocation ->
                view(invocation.getArgument(
                        0,
                        LiveSkladReturnRecoveryRequest.class
                )));

        LiveSkladReturnRecoveryView result = service.request(
                requestedBy,
                "recovery-F000175",
                "69c67dbf35f1a26e5b3bc638",
                "F000175",
                new BigDecimal("0.00"),
                1,
                "Restore verified zero-net item return"
        );

        assertThat(result.expectedNetAmount()).isEqualByComparingTo("0.00");
        assertThat(result.expectedPositionCount()).isEqualTo(1);

        assertThatThrownBy(() -> service.request(
                UUID.randomUUID(),
                "recovery-F000176",
                "69c67dbf35f1a26e5b3bc639",
                "F000176",
                new BigDecimal("-0.01"),
                1,
                "Negative amount must remain invalid"
        )).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void queuesExistingOrphanRelinkWithExactOriginalExpectations() {
        UUID requestedBy = UUID.randomUUID();
        when(store.findRecoveryByRequesterAndKey(
                requestedBy, "relink-F000349-v1"
        )).thenReturn(Optional.empty());
        when(store.findRecoveryByExternalIdAndMode(
                "6a5ce976c30937c4371a0af1",
                LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK
        )).thenReturn(Optional.empty());
        when(store.createRecovery(any())).thenAnswer(invocation ->
                view(invocation.getArgument(
                        0,
                        LiveSkladReturnRecoveryRequest.class
                )));

        LiveSkladReturnRecoveryView result = service.request(
                requestedBy,
                "relink-F000349-v1",
                new RecoverLiveSkladReturnRequest(
                        "6a5ce976c30937c4371a0af1",
                        "F000349",
                        new BigDecimal("46990"),
                        1,
                        LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK,
                        "6912f4ab09e647f3125d14ba",
                        "69875ed7a44502f84130f263",
                        "6912f4ab09e647f3125d14ba",
                        List.of(new RecoverLiveSkladReturnLinkExpectation(
                                "6a5ce976c3093727ca1a0af0",
                                "69875b2ba44502026430bc2d",
                                "695bd5e1214c11471133b70a",
                                new BigDecimal("1"),
                                new BigDecimal("46990"),
                                new BigDecimal("43750")
                        )),
                        "Relink verified existing orphan return"
                )
        );

        assertThat(result.mode()).isEqualTo(
                LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK
        );
        assertThat(result.expectedCurrentEmployeeExternalId())
                .isEqualTo("6912f4ab09e647f3125d14ba");
        assertThat(result.expectedOriginalSaleExternalId())
                .isEqualTo("69875ed7a44502f84130f263");
        assertThat(result.expectedOriginalLinks()).singleElement()
                .satisfies(link -> {
                    assertThat(link.expectedQuantity())
                            .isEqualByComparingTo("1.000");
                    assertThat(link.expectedNetAmount())
                            .isEqualByComparingTo("46990.00");
                    assertThat(link.expectedCostAmount())
                            .isEqualByComparingTo("43750.00");
                });
    }

    @Test
    void rejectsOrphanRelinkWithoutCompletePositionExpectations() {
        assertThatThrownBy(() -> service.request(
                UUID.randomUUID(),
                "relink-F000349-v1",
                new RecoverLiveSkladReturnRequest(
                        "6a5ce976c30937c4371a0af1",
                        "F000349",
                        new BigDecimal("46990.00"),
                        1,
                        LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK,
                        null,
                        "69875ed7a44502f84130f263",
                        "6912f4ab09e647f3125d14ba",
                        List.of(),
                        "Incomplete relink request"
                )
        )).isInstanceOf(InvalidRequestException.class);

        verify(store, never()).createRecovery(any());
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
                LiveSkladReturnRecoveryMode.MISSING_RETURN,
                null,
                null,
                null,
                List.of(),
                "RECEIVED",
                0,
                false,
                null,
                NOW,
                null
        );
    }

    private LiveSkladReturnRecoveryView view(
            LiveSkladReturnRecoveryRequest request
    ) {
        return new LiveSkladReturnRecoveryView(
                request.id(),
                request.externalId(),
                request.documentNumber(),
                request.netAmount(),
                request.positionCount(),
                request.mode(),
                request.currentEmployeeExternalId(),
                request.originalSaleExternalId(),
                request.originalEmployeeExternalId(),
                request.originalLinks(),
                "RECEIVED",
                0,
                false,
                null,
                NOW,
                null
        );
    }
}
