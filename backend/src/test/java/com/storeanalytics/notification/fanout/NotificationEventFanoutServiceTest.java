package com.storeanalytics.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import com.storeanalytics.notification.config.TelegramNotificationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NotificationEventFanoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T06:00:00Z");

    private NotificationEventFanoutStore store;
    private LlmCanonicalJsonCodec jsonCodec;
    private WeeklyTelegramMessageRenderer renderer;
    private TelegramQuietHoursScheduler quietHoursScheduler;
    private NotificationFanoutMetrics metrics;
    private NotificationEventFanoutService service;

    @BeforeEach
    void setUp() {
        store = mock(NotificationEventFanoutStore.class);
        jsonCodec = mock(LlmCanonicalJsonCodec.class);
        renderer = mock(WeeklyTelegramMessageRenderer.class);
        quietHoursScheduler = mock(TelegramQuietHoursScheduler.class);
        metrics = mock(NotificationFanoutMetrics.class);
        TelegramNotificationProperties properties =
                new TelegramNotificationProperties(
                        true,
                        true,
                        "primary",
                        Duration.ofSeconds(5),
                        5,
                        "weekly-telegram-v1",
                        false,
                        false,
                        "",
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(30),
                        5,
                        "",
                        "",
                        65_536,
                        false,
                        "",
                        "https://api.telegram.org",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(15),
                        Duration.ofMinutes(5),
                        65_536
                );
        service = new NotificationEventFanoutService(
                store,
                jsonCodec,
                renderer,
                quietHoursScheduler,
                properties,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void recordsTerminalNoRecipientOutcome() {
        WeeklyNotificationEvent event = event(NOW.plus(Duration.ofHours(24)));
        JsonNode content = mock(JsonNode.class);
        when(store.claimNextWeekly(NOW)).thenReturn(java.util.Optional.of(event));
        when(jsonCodec.decodeVerified("{}", "a".repeat(64))).thenReturn(content);
        when(store.employeeNames(event.snapshotId())).thenReturn(Map.of());
        when(renderer.render(event, content, Map.of())).thenReturn(message());
        when(store.recipients(event.storeId(), event.eventType(), "primary"))
                .thenReturn(List.of());

        NotificationFanoutResult result = service.processNext().orElseThrow();

        assertThat(result.outcome()).isEqualTo(
                NotificationFanoutOutcome.NO_RECIPIENTS
        );
        verify(store).insertReceipt(result, NOW);
        verify(metrics).completed(NotificationFanoutOutcome.NO_RECIPIENTS);
        verify(store, never()).insertDelivery(
                any(), any(), any(), any(), any(), eq(5), any()
        );
    }

    @Test
    void createsOneDurableDeliveryForEligibleRecipient() {
        WeeklyNotificationEvent event = event(NOW.plus(Duration.ofHours(24)));
        JsonNode content = mock(JsonNode.class);
        TelegramNotificationRecipient recipient = recipient();
        TelegramDeliverySchedule schedule = new TelegramDeliverySchedule(NOW, false);
        when(store.claimNextWeekly(NOW)).thenReturn(java.util.Optional.of(event));
        when(jsonCodec.decodeVerified("{}", "a".repeat(64))).thenReturn(content);
        when(store.employeeNames(event.snapshotId())).thenReturn(Map.of());
        when(renderer.render(event, content, Map.of())).thenReturn(message());
        when(store.recipients(event.storeId(), event.eventType(), "primary"))
                .thenReturn(List.of(recipient));
        when(quietHoursScheduler.schedule(NOW, event.expiresAt(), recipient))
                .thenReturn(schedule);
        when(store.insertDelivery(
                event,
                recipient,
                message(),
                schedule,
                "weekly-telegram-v1",
                5,
                NOW
        )).thenReturn(1);

        NotificationFanoutResult result = service.processNext().orElseThrow();

        assertThat(result.outcome()).isEqualTo(
                NotificationFanoutOutcome.DELIVERIES_CREATED
        );
        assertThat(result.recipientCount()).isOne();
        assertThat(result.deliveryCount()).isOne();
        verify(store).insertReceipt(result, NOW);
    }

    @Test
    void expiredEventIsClosedWithoutRendering() {
        WeeklyNotificationEvent event = event(NOW);
        when(store.claimNextWeekly(NOW)).thenReturn(java.util.Optional.of(event));

        NotificationFanoutResult result = service.processNext().orElseThrow();

        assertThat(result.outcome()).isEqualTo(
                NotificationFanoutOutcome.EVENT_EXPIRED
        );
        verify(jsonCodec, never()).decodeVerified(any(), any());
        verify(renderer, never()).render(any(), any(), any());
        verify(store).insertReceipt(result, NOW);
    }

    private WeeklyNotificationEvent event(Instant expiresAt) {
        Instant notBefore = expiresAt.equals(NOW) ? NOW.minusSeconds(1) : NOW;
        return new WeeklyNotificationEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Магазин",
                "WEEKLY_REPORT_READY",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                1,
                LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-08-02"),
                "{}",
                "a".repeat(64),
                notBefore,
                expiresAt
        );
    }

    private TelegramNotificationRecipient recipient() {
        return new TelegramNotificationRecipient(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ZoneId.of("Europe/Kaliningrad"),
                false,
                LocalTime.of(21, 0),
                LocalTime.of(8, 0)
        );
    }

    private RenderedTelegramMessage message() {
        return new RenderedTelegramMessage("text", "b".repeat(64));
    }
}
