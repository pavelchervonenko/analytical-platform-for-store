package com.storeanalytics.integration.livesklad.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladHttpException;
import com.storeanalytics.sync.service.ReturnRelinkPositionExpectation;
import com.storeanalytics.sync.service.ReturnSyncService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveSkladSaleReturnWebhookWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private LiveSkladWebhookStore store;
    private ReturnSyncService returnSyncService;
    private LiveSkladSaleReturnWebhookWorker worker;

    @BeforeEach
    void setUp() {
        store = mock(LiveSkladWebhookStore.class);
        returnSyncService = mock(ReturnSyncService.class);
        worker = new LiveSkladSaleReturnWebhookWorker(
                store,
                returnSyncService,
                new LiveSkladWebhookWorkerProperties(
                        true,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(2),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(15),
                        8
                ),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void processesDataIdAndCompletesReceipt() {
        UUID receiptId = UUID.randomUUID();
        when(store.claimNextSaleReturn(
                anyString(), eq(NOW), eq(Duration.ofMinutes(2)), eq(8)
        )).thenReturn(Optional.of(new LiveSkladWebhookClaim(
                receiptId,
                "event-1",
                """
                {"eventId":"event-1","action":{"id":"not-a-document"},                "data":{"id":"return-1"}}
                """,
                false,
                1
        )));

        worker.processNext();

        verify(store).recordSourceDocument(
                eq(receiptId), anyString(), eq("return-1")
        );
        verify(returnSyncService).synchronizeWebhookReturn("return-1");
        verify(store).complete(eq(receiptId), anyString(), eq(NOW));
    }

    @Test
    void rejectsPayloadMismatchWithoutCallingLiveSklad() {
        UUID receiptId = UUID.randomUUID();
        when(store.claimNextSaleReturn(
                anyString(), eq(NOW), eq(Duration.ofMinutes(2)), eq(8)
        )).thenReturn(Optional.of(new LiveSkladWebhookClaim(
                receiptId, "event-2", "{}", true, 1
        )));

        worker.processNext();

        verify(store).failPermanently(
                eq(receiptId),
                anyString(),
                eq(NOW),
                eq("PAYLOAD_MISMATCH"),
                anyString()
        );
        verify(returnSyncService, never()).synchronizeWebhookReturn(anyString());
    }

    @Test
    void retriesEventualNotFoundInsteadOfLosingEvent() {
        UUID receiptId = UUID.randomUUID();
        when(store.claimNextSaleReturn(
                anyString(), eq(NOW), eq(Duration.ofMinutes(2)), eq(8)
        )).thenReturn(Optional.of(new LiveSkladWebhookClaim(
                receiptId,
                "event-3",
                """
                {"eventId":"event-3","data":{"id":"return-late"}}
                """,
                false,
                1
        )));
        when(returnSyncService.synchronizeWebhookReturn("return-late"))
                .thenThrow(new LiveSkladHttpException("return-detail", 404));

        worker.processNext();

        verify(store).retry(
                eq(receiptId),
                anyString(),
                eq(NOW.plusSeconds(30)),
                eq("LIVESKLAD_HTTP_404"),
                anyString()
        );
        verify(store, never()).complete(any(), anyString(), any());
    }

    @Test
    void processesValidatedManualRecoveryWithoutParsingWebhookPayload() {
        UUID receiptId = UUID.randomUUID();
        when(store.claimNextSaleReturn(
                anyString(), eq(NOW), eq(Duration.ofMinutes(2)), eq(8)
        )).thenReturn(Optional.of(new LiveSkladWebhookClaim(
                receiptId,
                "manual-recovery-1",
                "{}",
                false,
                1,
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                2,
                LiveSkladReturnRecoveryMode.MISSING_RETURN,
                null,
                null,
                null,
                List.of()
        )));

        worker.processNext();

        verify(returnSyncService).recoverReturn(
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                2
        );
        verify(returnSyncService, never()).synchronizeWebhookReturn(anyString());
        verify(store).complete(eq(receiptId), anyString(), eq(NOW));
    }

    @Test
    void permanentlyRejectsRecoveryExpectationMismatch() {
        UUID receiptId = UUID.randomUUID();
        when(store.claimNextSaleReturn(
                anyString(), eq(NOW), eq(Duration.ofMinutes(2)), eq(8)
        )).thenReturn(Optional.of(new LiveSkladWebhookClaim(
                receiptId,
                "manual-recovery-mismatch",
                "{}",
                false,
                1,
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                2,
                LiveSkladReturnRecoveryMode.MISSING_RETURN,
                null,
                null,
                null,
                List.of()
        )));
        when(returnSyncService.recoverReturn(
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                2
        )).thenThrow(new InvalidRequestException("mismatch"));

        worker.processNext();

        verify(store).failPermanently(
                eq(receiptId),
                anyString(),
                eq(NOW),
                eq("RETURN_RECOVERY_EXPECTATION_MISMATCH"),
                anyString()
        );
        verify(store, never()).complete(any(), anyString(), any());
    }

    @Test
    void processesExistingOrphanRelinkWithAllExpectedLinks() {
        UUID receiptId = UUID.randomUUID();
        RecoverLiveSkladReturnLinkExpectation link =
                new RecoverLiveSkladReturnLinkExpectation(
                        "6a5ce976c3093727ca1a0af0",
                        "69875b2ba44502026430bc2d",
                        "695bd5e1214c11471133b70a",
                        new BigDecimal("1.000"),
                        new BigDecimal("46990.00"),
                        new BigDecimal("43750.00")
                );
        when(store.claimNextSaleReturn(
                anyString(), eq(NOW), eq(Duration.ofMinutes(2)), eq(8)
        )).thenReturn(Optional.of(new LiveSkladWebhookClaim(
                receiptId,
                "manual-relink-1",
                "{}",
                false,
                1,
                "6a5ce976c30937c4371a0af1",
                "F000349",
                new BigDecimal("46990.00"),
                1,
                LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK,
                "6912f4ab09e647f3125d14ba",
                "69875ed7a44502f84130f263",
                "6912f4ab09e647f3125d14ba",
                List.of(link)
        )));

        worker.processNext();

        verify(returnSyncService).relinkExistingOrphanReturn(
                "6a5ce976c30937c4371a0af1",
                "F000349",
                new BigDecimal("46990.00"),
                1,
                "6912f4ab09e647f3125d14ba",
                "69875ed7a44502f84130f263",
                "6912f4ab09e647f3125d14ba",
                List.of(new ReturnRelinkPositionExpectation(
                        link.returnPositionExternalId(),
                        link.originalSalePositionExternalId(),
                        link.productExternalId(),
                        link.expectedQuantity(),
                        link.expectedNetAmount(),
                        link.expectedCostAmount()
                ))
        );
        verify(returnSyncService, never()).recoverReturn(
                anyString(), anyString(), any(), any(Integer.class)
        );
        verify(store).complete(eq(receiptId), anyString(), eq(NOW));
    }

}
