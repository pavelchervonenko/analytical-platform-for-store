package com.storeanalytics.integration.telegram.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.notification.config.TelegramNotificationProperties;
import com.storeanalytics.notification.delivery.TelegramSendException;
import com.storeanalytics.notification.delivery.TelegramSendFailureKind;
import com.storeanalytics.notification.delivery.TelegramSendReceipt;
import com.storeanalytics.notification.delivery.TelegramSendRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TelegramBotApiSenderTest {

    private static final Instant NOW = Instant.parse("2026-08-03T09:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .findAndAddModules()
            .build();
    private final AtomicReference<ResponseFixture> response = new AtomicReference<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        response.set(new ResponseFixture(
                200,
                "{\"ok\":true,\"result\":{\"message_id\":987654321}}",
                Duration.ZERO
        ));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sendMessage", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsBoundedPlainTextContractAndReturnsMessageId() {
        TelegramBotApiSender sender = sender(Clock.fixed(NOW, ZoneOffset.UTC));

        TelegramSendReceipt receipt = sender.send(sendRequest(NOW.plusSeconds(10)));

        assertThat(receipt.providerMessageId()).isEqualTo("987654321");
        JsonNode payload = request.get();
        assertThat(payload.path("chat_id").asLong()).isEqualTo(123456789L);
        assertThat(payload.path("text").asText()).isEqualTo("Недельная сводка");
        assertThat(payload.path("protect_content").asBoolean()).isTrue();
        assertThat(payload.path("allow_paid_broadcast").asBoolean()).isFalse();
        assertThat(payload.path("link_preview_options")
                .path("is_disabled").asBoolean()).isTrue();
        assertThat(payload.has("parse_mode")).isFalse();
    }

    @Test
    void mapsRateLimitAndOfficialRetryAfterWithoutProviderDescription() {
        response.set(new ResponseFixture(
                429,
                "{\"ok\":false,\"error_code\":429,"
                        + "\"description\":\"sensitive\","
                        + "\"parameters\":{\"retry_after\":30}}",
                Duration.ZERO
        ));

        assertThatThrownBy(() -> sender(Clock.fixed(NOW, ZoneOffset.UTC))
                .send(sendRequest(NOW.plusSeconds(10))))
                .isInstanceOfSatisfying(
                        TelegramSendException.class,
                        failure -> {
                            assertThat(failure.getKind()).isEqualTo(
                                    TelegramSendFailureKind.RATE_LIMITED
                            );
                            assertThat(failure.getRetryAfterAt())
                                    .isEqualTo(NOW.plusSeconds(30));
                            assertThat(failure.getMessage()).doesNotContain("sensitive");
                        }
                );
    }

    @Test
    void mapsForbiddenDestinationToBlockedWithoutParsingDescription() {
        response.set(new ResponseFixture(
                403,
                "{\"ok\":false,\"error_code\":403,"
                        + "\"description\":\"bot was blocked\"}",
                Duration.ZERO
        ));

        assertThatThrownBy(() -> sender(Clock.fixed(NOW, ZoneOffset.UTC))
                .send(sendRequest(NOW.plusSeconds(10))))
                .isInstanceOfSatisfying(
                        TelegramSendException.class,
                        failure -> assertThat(failure.getKind()).isEqualTo(
                                TelegramSendFailureKind.BOT_BLOCKED
                        )
                );
    }

    @Test
    void mapsMalformedServerErrorByUnambiguousHttpStatus() {
        response.set(new ResponseFixture(
                503,
                "not-json",
                Duration.ZERO
        ));

        assertThatThrownBy(() -> sender(Clock.fixed(NOW, ZoneOffset.UTC))
                .send(sendRequest(NOW.plusSeconds(10))))
                .isInstanceOfSatisfying(
                        TelegramSendException.class,
                        failure -> assertThat(failure.getKind()).isEqualTo(
                                TelegramSendFailureKind.TRANSIENT_PROVIDER
                        )
                );
    }

    @Test
    void treatsReadTimeoutAsUnknownOutcome() {
        response.set(new ResponseFixture(
                200,
                "{\"ok\":true,\"result\":{\"message_id\":1}}",
                Duration.ofMillis(500)
        ));
        Clock liveClock = Clock.systemUTC();

        assertThatThrownBy(() -> sender(liveClock, Duration.ofMillis(100))
                .send(sendRequest(liveClock.instant().plusSeconds(5))))
                .isInstanceOfSatisfying(
                        TelegramSendException.class,
                        failure -> assertThat(failure.getKind()).isEqualTo(
                                TelegramSendFailureKind.UNKNOWN_OUTCOME
                        )
                );
    }

    private TelegramBotApiSender sender(Clock clock) {
        return sender(clock, Duration.ofSeconds(2));
    }

    private TelegramBotApiSender sender(Clock clock, Duration readTimeout) {
        return new TelegramBotApiSender(
                properties(readTimeout),
                objectMapper,
                clock,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/sendMessage")
        );
    }

    private TelegramNotificationProperties properties(Duration readTimeout) {
        return new TelegramNotificationProperties(
                true, false, "primary", Duration.ofSeconds(5), 5,
                "weekly-telegram-v1", false, false, "",
                Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofSeconds(30), 5, "", "", 65_536,
                true, "123456789:abcdefghijklmnopqrstuvwxyzABCDEFGHI",
                "https://api.telegram.org", Duration.ofSeconds(1),
                readTimeout, Duration.ofSeconds(5),
                readTimeout.plusSeconds(1), Duration.ofSeconds(15),
                Duration.ofMinutes(5), 65_536
        );
    }

    private TelegramSendRequest sendRequest(Instant deadline) {
        return new TelegramSendRequest(
                UUID.randomUUID(),
                123456789L,
                "Недельная сводка",
                deadline
        );
    }

    private void respond(HttpExchange exchange) throws IOException {
        request.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
        ResponseFixture fixture = response.get();
        if (!fixture.delay().isZero()) {
            try {
                Thread.sleep(fixture.delay());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = fixture.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(fixture.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record ResponseFixture(int status, String body, Duration delay) {
    }
}
