package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.config.LiveSkladPayloadLimitsProperties;
import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import com.storeanalytics.integration.livesklad.observability.LiveSkladPayloadRejectionMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class HttpLiveSkladClientFailureTest {

    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .findAndAddModules()
            .build();
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", this::authenticate);
        server.createContext("/shops", this::rateLimit);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesRetryAfterDeltaSeconds() {
        retryAfter.set("120");

        assertThatThrownBy(() -> client().fetchStores())
                .isInstanceOfSatisfying(
                        LiveSkladRateLimitException.class,
                        exception -> {
                            org.assertj.core.api.Assertions.assertThat(
                                    exception.getRetryAfter()
                            ).isEqualTo(Duration.ofMinutes(2));
                            org.assertj.core.api.Assertions.assertThat(
                                    exception.getStatusCode()
                            ).isEqualTo(429);
                            org.assertj.core.api.Assertions.assertThat(
                                    exception.getOperation()
                            ).isEqualTo("LiveSklad stores request failed");
                        }
                );
    }

    @Test
    void parsesRetryAfterHttpDate() {
        retryAfter.set("Mon, 20 Jul 2026 10:05:00 GMT");

        assertThatThrownBy(() -> client().fetchStores())
                .isInstanceOfSatisfying(
                        LiveSkladRateLimitException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.getRetryAfter()
                        ).isEqualTo(Duration.ofMinutes(5))
                );
    }

    @Test
    void boundsExcessiveRetryAfter() {
        retryAfter.set("172800");

        assertThatThrownBy(() -> client().fetchStores())
                .isInstanceOfSatisfying(
                        LiveSkladRateLimitException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.getRetryAfter()
                        ).isEqualTo(Duration.ofDays(1))
                );
    }

    private HttpLiveSkladClient client() {
        LiveSkladProperties properties = new LiveSkladProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-login",
                "test-password",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        return new HttpLiveSkladClient(
                RestClient.builder(),
                properties,
                objectMapper,
                LiveSkladPayloadLimitsProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                LiveSkladPayloadRejectionMetrics.noop()
        );
    }

    private void authenticate(HttpExchange exchange) throws IOException {
        byte[] body = "{\"token\":\"fixture-token\",\"ttl\":3600}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(
                HttpHeaders.CONTENT_TYPE,
                "application/json"
        );
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void rateLimit(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add(
                HttpHeaders.RETRY_AFTER,
                retryAfter.get()
        );
        exchange.sendResponseHeaders(429, -1);
        exchange.close();
    }
}
