package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.config.LiveSkladPayloadLimitsProperties;
import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

class HttpLiveSkladClientCardinalityLimitsTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", exchange -> sendJson(exchange, """
                {"token":"fixture-token","ttl":900}
                """));
        server.createContext("/shops", exchange -> sendJson(exchange, """
                {"data":[
                  {"id":"store-1","name":"One"},
                  {"id":"store-2","name":"Two"}
                ]}
                """));
        server.createContext("/documents", exchange -> sendJson(exchange, """
                {"data":{
                  "id":"sale-1","date":"2026-07-01T10:00:00Z","type":"sale",
                  "shop":{"id":"store-1"},
                  "cash":{"money":0,"bank":0,"invoice":0},
                  "positions":[
                    {"positionId":"p-1","nomenclatureId":"n-1","name":"One",
                     "isWork":false,"count":1,"price":1,"soldPrice":1},
                    {"positionId":"p-2","nomenclatureId":"n-2","name":"Two",
                     "isWork":false,"count":1,"price":1,"soldPrice":1}
                  ]
                }}
                """));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void rejectsCollectionBeyondConfiguredRecordCount() {
        assertRejected(() -> client(1, 1000).fetchStores(),
                Reason.COLLECTION_RECORD_COUNT);
    }

    @Test
    void rejectsDocumentBeyondConfiguredPositionCount() {
        assertRejected(() -> client(1000, 1).fetchSaleDetail("sale-1"),
                Reason.DOCUMENT_POSITION_COUNT);
    }

    private void assertRejected(Runnable request, Reason reason) {
        Throwable failure = catchThrowable(request::run);
        assertThat(failure).isInstanceOf(LiveSkladPayloadRejectedException.class);
        assertThat(((LiveSkladPayloadRejectedException) failure).getReason())
                .isEqualTo(reason);
    }

    private LiveSkladClient client(int maxRecords, int maxPositions) {
        LiveSkladPayloadLimitsProperties limits =
                new LiveSkladPayloadLimitsProperties(
                        DataSize.ofMegabytes(2),
                        2L * 1024 * 1024,
                        100_000,
                        64,
                        65_536,
                        256,
                        128,
                        DataSize.ofMegabytes(4),
                        maxRecords,
                        maxPositions
                );
        return new HttpLiveSkladClient(
                RestClient.builder(),
                new LiveSkladProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "login",
                        "password",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2)
                ),
                new ObjectMapper().rebuild().findAndAddModules().build(),
                limits
        );
    }

    private void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
