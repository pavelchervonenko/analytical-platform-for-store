package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.sync.exception.HistoricalSalesReadBudgetException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class HistoricalSalesHttpBudgetTest {
    @Test
    void realInterceptorCountsAuthAndPreventsTheNextHttpRequest() throws Exception {
        AtomicInteger auth = new AtomicInteger();
        AtomicInteger shops = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", exchange -> {
            auth.incrementAndGet();
            byte[] bytes = "{\"token\":\"fixture-token\",\"ttl\":900,\"remainRequest\":999}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/shops", exchange -> {
            shops.incrementAndGet();
            byte[] bytes = "{\"data\":[],\"remainRequest\":998}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            HttpLiveSkladClient client = new HttpLiveSkladClient(RestClient.builder(), new LiveSkladProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-login", "fixture-password",
                    Duration.ofSeconds(2), Duration.ofSeconds(2)), new ObjectMapper());
            AtomicInteger persistedAttempts = new AtomicInteger();
            var context = new HistoricalSalesReadScope.Context(UUID.randomUUID(), "fixture-worker", 0,
                    Instant.parse("2026-09-30T22:00:00Z"), Instant.parse("2026-10-01T01:00:00Z"));
            try (var ignored = HistoricalSalesReadScope.open(context, 1, persistedAttempts::incrementAndGet)) {
                assertThatThrownBy(client::fetchStores).isInstanceOf(HistoricalSalesReadBudgetException.class);
            }
            assertThat(auth).hasValue(1);
            assertThat(shops).hasValue(0);
            assertThat(persistedAttempts).hasValue(1);
            assertThat(HistoricalSalesReadScope.current()).isNull();
            assertThat(client.fetchStores()).isEmpty();
            assertThat(auth).hasValue(1);
            assertThat(shops).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
