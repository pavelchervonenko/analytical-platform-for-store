package com.storeanalytics.integration.livesklad.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.integration.livesklad.exception.LiveSkladHttpException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladOrderChangedException;
import com.storeanalytics.sync.service.OrderSyncService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LiveSkladOrderReturnWebhookWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private LiveSkladWebhookStore store;
    private OrderSyncService orderSyncService;
    private LiveSkladOrderReturnWebhookWorker worker;

    @BeforeEach
    void setUp() {
        store = mock(LiveSkladWebhookStore.class);
        orderSyncService = mock(OrderSyncService.class);
        worker = new LiveSkladOrderReturnWebhookWorker(
                store,
                orderSyncService,
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
    void processesDataIdAndIgnoresActionId() {
        UUID receiptId = UUID.randomUUID();
        claim(receiptId, "event-1", """
                {"eventId":"event-1","action":{"id":"not-an-order"},
                 "data":{"id":"order-1"}}
                """, false, 1);

        worker.processNext();

        verify(store).recordSourceDocument(
                eq(receiptId), anyString(), eq("order-1")
        );
        verify(orderSyncService).synchronizeWebhookOrder("order-1");
        verify(store).complete(eq(receiptId), anyString(), eq(NOW));
    }

    @Test
    void rejectsPayloadMismatchWithoutSynchronizingOrder() {
        UUID receiptId = UUID.randomUUID();
        claim(receiptId, "event-2", "{}", true, 1);

        worker.processNext();

        verify(store).failPermanently(
                eq(receiptId),
                anyString(),
                eq(NOW),
                eq("PAYLOAD_MISMATCH"),
                anyString()
        );
        verify(orderSyncService, never()).synchronizeWebhookOrder(anyString());
    }

    @Test
    void rejectsMissingDataIdPermanently() {
        UUID receiptId = UUID.randomUUID();
        claim(receiptId, "event-3", "{\"action\":{\"id\":\"action-1\"}}", false, 1);

        worker.processNext();

        verify(store).failPermanently(
                eq(receiptId),
                anyString(),
                eq(NOW),
                eq("SOURCE_DOCUMENT_ID_MISSING"),
                anyString()
        );
        verify(orderSyncService, never()).synchronizeWebhookOrder(anyString());
    }

    @Test
    void retriesEventualNotFound() {
        UUID receiptId = UUID.randomUUID();
        claim(receiptId, "event-4", "{\"data\":{\"id\":\"order-late\"}}", false, 1);
        when(orderSyncService.synchronizeWebhookOrder("order-late"))
                .thenThrow(new LiveSkladHttpException("order-detail", 404));

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
    void retriesOrderChangeRace() {
        UUID receiptId = UUID.randomUUID();
        claim(receiptId, "event-5", "{\"data\":{\"id\":\"order-racing\"}}", false, 2);
        when(orderSyncService.synchronizeWebhookOrder("order-racing"))
                .thenThrow(new LiveSkladOrderChangedException());

        worker.processNext();

        verify(store).retry(
                eq(receiptId),
                anyString(),
                eq(NOW.plusSeconds(60)),
                eq("LIVESKLAD_ORDER_CHANGED"),
                anyString()
        );
    }

    @Test
    void marksRetryableFailurePermanentAfterLastAttempt() {
        UUID receiptId = UUID.randomUUID();
        claim(receiptId, "event-6", "{\"data\":{\"id\":\"order-late\"}}", false, 8);
        when(orderSyncService.synchronizeWebhookOrder("order-late"))
                .thenThrow(new LiveSkladHttpException("order-detail", 404));

        worker.processNext();

        verify(store).failPermanently(
                eq(receiptId),
                anyString(),
                eq(NOW),
                eq("RETRY_EXHAUSTED_LIVESKLAD_HTTP_404"),
                anyString()
        );
        verify(store, never()).retry(any(), anyString(), any(), anyString(), anyString());
    }

    private void claim(
            UUID receiptId,
            String eventId,
            String payload,
            boolean payloadMismatch,
            int attemptCount
    ) {
        when(store.claimNextOrderReturn(
                anyString(), eq(NOW), eq(Duration.ofMinutes(2)), eq(8)
        )).thenReturn(Optional.of(new LiveSkladWebhookClaim(
                receiptId,
                eventId,
                payload,
                payloadMismatch,
                attemptCount
        )));
    }
}
