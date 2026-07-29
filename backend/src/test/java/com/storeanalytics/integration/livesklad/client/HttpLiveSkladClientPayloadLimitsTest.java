package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.config.LiveSkladPayloadLimitsProperties;
import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import com.storeanalytics.integration.livesklad.observability.LiveSkladPayloadRejectionMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

class HttpLiveSkladClientPayloadLimitsTest {

    private static final String NORMAL_STORES = """
            {"data":[{"id":"store-1","name":"Store"}]}
            """;

    private final ObjectMapper applicationObjectMapper =
            new ObjectMapper().rebuild().findAndAddModules().build();
    private final AtomicReference<ResponseFixture> storesResponse =
            new AtomicReference<>(ResponseFixture.json(NORMAL_STORES));
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", exchange -> send(
                exchange,
                ResponseFixture.json("""
                        {"token":"fixture-token","ttl":900,"remainRequest":99}
                        """)
        ));
        server.createContext("/shops", exchange -> send(
                exchange,
                storesResponse.get()
        ));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void rejectsResponseWhoseDeclaredLengthExceedsLimit() {
        String marker = "must-not-appear-in-exception";
        storesResponse.set(ResponseFixture.json(
                "{\"data\":[],\"padding\":\"" + marker + "x".repeat(512) + "\"}"
        ));

        LiveSkladPayloadRejectedException exception = assertRejected(
                limits(256, 64, 65_536),
                Reason.RESPONSE_TOO_LARGE
        );
        assertThat(exception.getMessage()).doesNotContain(marker);
    }

    @Test
    void recordsDeclaredLengthRejectionExactlyOnce() {
        storesResponse.set(ResponseFixture.json(
                "{\"data\":[],\"padding\":\"" + "x".repeat(512) + "\"}"
        ));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LiveSkladPayloadRejectionMetrics metrics =
                new LiveSkladPayloadRejectionMetrics(registry);

        Throwable failure = catchThrowable(() -> client(
                limits(256, 64, 65_536),
                metrics
        ).fetchStores());

        assertThat(failure).isInstanceOf(
                LiveSkladPayloadRejectedException.class
        );
        assertThat(registry.get(
                LiveSkladPayloadRejectionMetrics.REJECTIONS_METRIC
        ).tag("reason", "response_too_large").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void rejectsChunkedResponseAfterReadingConfiguredNumberOfBytes() {
        storesResponse.set(ResponseFixture.chunkedJson(
                "{\"data\":[],\"padding\":\"" + "x".repeat(512) + "\"}"
        ));

        assertRejected(limits(256, 64, 65_536), Reason.RESPONSE_TOO_LARGE);
    }

    @Test
    void rejectsJsonThatExceedsNestingConstraint() {
        storesResponse.set(ResponseFixture.json(
                "{\"data\":[],\"extra\":" + "[".repeat(8)
                        + "0" + "]".repeat(8) + "}"
        ));

        assertRejected(limits(4096, 5, 65_536), Reason.JSON_COMPLEXITY);
    }

    @Test
    void rejectsJsonThatExceedsStringConstraint() {
        storesResponse.set(ResponseFixture.json(
                "{\"data\":[{\"id\":\"store-1\",\"name\":\""
                        + "x".repeat(64) + "\"}]}"
        ));

        assertRejected(limits(4096, 64, 32), Reason.JSON_COMPLEXITY);
    }

    @Test
    void rejectsUnsupportedSuccessfulResponseHeaders() {
        storesResponse.set(new ResponseFixture(
                NORMAL_STORES,
                "text/plain",
                null,
                false
        ));
        assertRejected(
                limits(4096, 64, 65_536),
                Reason.UNSUPPORTED_CONTENT_TYPE
        );

        storesResponse.set(new ResponseFixture(
                NORMAL_STORES,
                "application/json",
                "gzip",
                false
        ));
        assertRejected(
                limits(4096, 64, 65_536),
                Reason.UNSUPPORTED_CONTENT_ENCODING
        );
    }

    @Test
    void acceptsResponseExactlyAtByteLimitWithoutChangingApplicationMapper() {
        String exactBody = "{\"data\":[{\"id\":\"store-1\",\"name\":\"Store\"}],"
                + "\"padding\":\"" + "x".repeat(64) + "\"}";
        storesResponse.set(ResponseFixture.json(exactBody));
        int responseBytes = exactBody.getBytes(StandardCharsets.UTF_8).length;
        LiveSkladPayloadLimitsProperties payloadLimits = limits(
                responseBytes,
                5,
                65_536
        );

        assertThat(client(payloadLimits).fetchStores()).hasSize(1);
        String deeplyNested = "[".repeat(8) + "0" + "]".repeat(8);
        assertThatCode(() -> applicationObjectMapper.readTree(deeplyNested))
                .doesNotThrowAnyException();
    }

    private LiveSkladPayloadRejectedException assertRejected(
            LiveSkladPayloadLimitsProperties limits,
            Reason reason
    ) {
        Throwable failure = catchThrowable(() -> client(limits).fetchStores());
        assertThat(failure).isInstanceOf(LiveSkladPayloadRejectedException.class);
        LiveSkladPayloadRejectedException rejected =
                (LiveSkladPayloadRejectedException) failure;
        assertThat(rejected.getReason()).isEqualTo(reason);
        return rejected;
    }

    private LiveSkladClient client(LiveSkladPayloadLimitsProperties limits) {
        return client(limits, LiveSkladPayloadRejectionMetrics.noop());
    }

    private LiveSkladClient client(
            LiveSkladPayloadLimitsProperties limits,
            LiveSkladPayloadRejectionMetrics rejectionMetrics
    ) {
        return new HttpLiveSkladClient(
                RestClient.builder(),
                new LiveSkladProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "login",
                        "password",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2)
                ),
                applicationObjectMapper,
                limits,
                Clock.systemUTC(),
                rejectionMetrics
        );
    }

    private LiveSkladPayloadLimitsProperties limits(
            long maxResponseBytes,
            int maxNestingDepth,
            int maxStringLength
    ) {
        return new LiveSkladPayloadLimitsProperties(
                DataSize.ofBytes(maxResponseBytes),
                maxResponseBytes,
                100_000,
                maxNestingDepth,
                maxStringLength,
                256,
                128,
                DataSize.ofMegabytes(4),
                1000,
                1000
        );
    }

    private void send(HttpExchange exchange, ResponseFixture fixture)
            throws IOException {
        byte[] response = fixture.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", fixture.contentType());
        if (fixture.contentEncoding() != null) {
            exchange.getResponseHeaders().set(
                    "Content-Encoding",
                    fixture.contentEncoding()
            );
        }
        exchange.sendResponseHeaders(200, fixture.chunked() ? 0 : response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record ResponseFixture(
            String body,
            String contentType,
            String contentEncoding,
            boolean chunked
    ) {

        private static ResponseFixture json(String body) {
            return new ResponseFixture(body, "application/json", null, false);
        }

        private static ResponseFixture chunkedJson(String body) {
            return new ResponseFixture(body, "application/json", null, true);
        }
    }
}
