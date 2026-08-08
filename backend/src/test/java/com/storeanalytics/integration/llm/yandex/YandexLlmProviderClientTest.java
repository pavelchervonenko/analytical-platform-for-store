package com.storeanalytics.integration.llm.yandex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class YandexLlmProviderClientTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final String MODEL = "gpt://folder/yandexgpt-5.1";
    private static final String SUCCESS_RESPONSE = """
            {
              "id":"chatcmpl-request-1",
              "object":"chat.completion",
              "model":"gpt://folder/yandexgpt-5.1",
              "choices":[{
                "index":0,
                "message":{"role":"assistant","content":"{\\"store\\":{}}"},
                "finish_reason":"stop"
              }],
              "usage":{
                "prompt_tokens":100,
                "completion_tokens":20,
                "total_tokens":120,
                "prompt_tokens_details":{"cached_tokens":10},
                "completion_tokens_details":{"reasoning_tokens":3}
              }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .findAndAddModules()
            .build();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicReference<ResponseFixture> fixture = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        fixture.set(ResponseFixture.json(200, SUCCESS_RESPONSE));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsOfficialStructuredCompletionContractAndMapsReceipt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        YandexLlmProviderClient client = client(
                policy(1_048_576),
                Duration.ofSeconds(2),
                Clock.fixed(NOW, ZoneOffset.UTC),
                registry
        );

        LlmProviderResponseReceipt receipt = client.generate(request(
                NOW.plusSeconds(5)
        ));

        CapturedRequest sent = captured.get();
        assertThat(sent.authorization()).isEqualTo("Api-Key secret-key");
        assertThat(sent.project()).isEqualTo("folder");
        assertThat(sent.dataLoggingEnabled()).isEqualTo("false");
        assertThat(sent.clientRequestId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
        assertThat(sent.contentType()).startsWith("application/json");
        JsonNode payload = readTree(sent.body());
        assertThat(payload.path("model").asText()).isEqualTo(MODEL);
        assertThat(payload.path("messages")).hasSize(2);
        assertThat(payload.path("stream").asBoolean()).isFalse();
        assertThat(payload.path("store").asBoolean()).isFalse();
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(4000);
        assertThat(payload.path("response_format").path("type").asText())
                .isEqualTo("json_schema");
        assertThat(payload.path("response_format")
                .path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(payload.path("response_format")
                .path("json_schema").path("schema").path("type").asText())
                .isEqualTo("object");

        assertThat(receipt.responseBody()).isEqualTo("{\"store\":{}}");
        assertThat(receipt.providerRequestId()).isEqualTo("chatcmpl-request-1");
        assertThat(receipt.inputTokens()).isEqualTo(100);
        assertThat(receipt.outputTokens()).isEqualTo(20);
        assertThat(receipt.cachedInputTokens()).isEqualTo(10);
        assertThat(receipt.reasoningTokens()).isEqualTo(3);
        assertThat(receipt.totalTokens()).isEqualTo(120);
        assertThat(receipt.costAmount()).isEqualByComparingTo("0.096000");
        assertThat(registry.get(YandexLlmMetrics.CALLS)
                .tag("provider", "YANDEX").tag("outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(YandexLlmMetrics.TOKENS)
                .tag("provider", "YANDEX").tag("type", "input")
                .counter().count()).isEqualTo(100.0);
    }

    @Test
    void performsConservativeLocalPreflightWithoutNetworkCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        YandexLlmProviderClient client = client(
                policy(1_048_576),
                Duration.ofSeconds(2),
                Clock.fixed(NOW, ZoneOffset.UTC),
                registry
        );

        LlmProviderPreflight preflight = client.preflight(request(
                NOW.plusSeconds(5)
        ));

        assertThat(preflight.estimatedInputTokens()).isPositive();
        assertThat(preflight.contextWindowTokens()).isEqualTo(32_768);
        assertThat(preflight.estimatedMaximumCost()).isPositive();
        assertThat(preflight.costCurrency()).isEqualTo("RUB");
        assertThat(requests).hasValue(0);
        assertThat(registry.get(YandexLlmMetrics.PREFLIGHTS)
                .tag("provider", "YANDEX").counter().count()).isEqualTo(1.0);
    }

    @Test
    void classifiesRateLimitAndDoesNotExposeProviderBody() {
        fixture.set(new ResponseFixture(
                429,
                "application/json",
                "{\"message\":\"sensitive-provider-detail\"}",
                "120",
                Duration.ZERO
        ));

        assertThatThrownBy(() -> client().generate(request(NOW.plusSeconds(5))))
                .isInstanceOfSatisfying(
                        YandexLlmProviderException.class,
                        exception -> {
                            assertThat(exception.getKind())
                                    .isEqualTo(LlmProviderFailureKind.RATE_LIMITED);
                            assertThat(exception.getOutcomeCertainty()).isEqualTo(
                                    LlmProviderOutcomeCertainty.RESPONSE_RECEIVED
                            );
                            assertThat(exception.getHttpStatus()).isEqualTo(429);
                            assertThat(exception.getRetryAfter())
                                    .isEqualTo(Duration.ofMinutes(2));
                            assertThat(exception.isRetryable()).isTrue();
                            assertThat(exception.getMessage())
                                    .doesNotContain("sensitive-provider-detail");
                        }
                );
    }

    @Test
    void exposesOnlySafeProviderErrorMetadata() {
        fixture.set(new ResponseFixture(
                400,
                "application/json",
                "{\"error\":{\"code\":null,"
                        + "\"type\":\"invalid_request_error\","
                        + "\"message\":\"maximum context length sensitive-provider-detail\"}}",
                null,
                Duration.ZERO
        ));

        assertThatThrownBy(() -> client().generate(request(NOW.plusSeconds(5))))
                .isInstanceOfSatisfying(
                        YandexLlmProviderException.class,
                        exception -> {
                            assertThat(exception.getMessage())
                                    .contains("category=context_length")
                                    .contains("type=invalid_request_error")
                                    .doesNotContain("sensitive-provider-detail");
                        }
                );
    }

    @Test
    void rejectsOversizedChunkedResponseBeforeJsonParsing() {
        fixture.set(new ResponseFixture(
                200,
                "application/json",
                "x".repeat(20_000),
                null,
                Duration.ZERO
        ));

        assertThatThrownBy(() -> client(
                policy(16_384),
                Duration.ofSeconds(2),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry()
        ).generate(request(NOW.plusSeconds(5))))
                .isInstanceOfSatisfying(
                        YandexLlmProviderException.class,
                        exception -> {
                            assertThat(exception.getKind()).isEqualTo(
                                    LlmProviderFailureKind.RESPONSE_TOO_LARGE
                            );
                            assertThat(exception.getOutcomeCertainty()).isEqualTo(
                                    LlmProviderOutcomeCertainty.RESPONSE_RECEIVED
                            );
                        }
                );
    }

    @Test
    void rejectsTruncatedProviderResponseWithoutRetryClassification() {
        fixture.set(ResponseFixture.json(
                200,
                SUCCESS_RESPONSE.replace("\"finish_reason\":\"stop\"",
                        "\"finish_reason\":\"length\"")
        ));

        assertThatThrownBy(() -> client().generate(request(NOW.plusSeconds(5))))
                .isInstanceOfSatisfying(
                        YandexLlmProviderException.class,
                        exception -> {
                            assertThat(exception.getKind()).isEqualTo(
                                    LlmProviderFailureKind.TRUNCATED_RESPONSE
                            );
                            assertThat(exception.getOutcomeCertainty()).isEqualTo(
                                    LlmProviderOutcomeCertainty.RESPONSE_RECEIVED
                            );
                            assertThat(exception.isRetryable()).isFalse();
                        }
                );
    }

    @Test
    void rejectsNonJsonGeneratedContent() {
        fixture.set(ResponseFixture.json(
                200,
                SUCCESS_RESPONSE.replace("{\\\"store\\\":{}}", "plain text")
        ));

        assertThatThrownBy(() -> client().generate(request(NOW.plusSeconds(5))))
                .isInstanceOfSatisfying(
                        YandexLlmProviderException.class,
                        exception -> assertThat(exception.getKind()).isEqualTo(
                                LlmProviderFailureKind.MALFORMED_RESPONSE
                        )
                );
    }

    @Test
    void doesNotSendRequestAfterCallDeadline() {
        assertThatThrownBy(() -> client().generate(request(NOW.minusSeconds(1))))
                .isInstanceOfSatisfying(
                        YandexLlmProviderException.class,
                        exception -> {
                            assertThat(exception.getKind()).isEqualTo(
                                    LlmProviderFailureKind.DEADLINE_EXCEEDED
                            );
                            assertThat(exception.getOutcomeCertainty()).isEqualTo(
                                    LlmProviderOutcomeCertainty.NOT_SENT
                            );
                        }
                );
        assertThat(requests).hasValue(0);
    }

    @Test
    void classifiesReadTimeoutAsUnknownOutcome() {
        fixture.set(new ResponseFixture(
                200,
                "application/json",
                SUCCESS_RESPONSE,
                null,
                Duration.ofMillis(500)
        ));
        Clock liveClock = Clock.systemUTC();

        assertThatThrownBy(() -> client(
                policy(1_048_576),
                Duration.ofMillis(100),
                liveClock,
                new SimpleMeterRegistry()
        ).generate(request(liveClock.instant().plusSeconds(5))))
                .isInstanceOfSatisfying(
                        YandexLlmProviderException.class,
                        exception -> {
                            assertThat(exception.getKind()).isEqualTo(
                                    LlmProviderFailureKind.DEADLINE_EXCEEDED
                            );
                            assertThat(exception.getOutcomeCertainty()).isEqualTo(
                                    LlmProviderOutcomeCertainty.UNKNOWN
                            );
                        }
                );
    }

    private YandexLlmProviderClient client() {
        return client(
                policy(1_048_576),
                Duration.ofSeconds(2),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry()
        );
    }

    private YandexLlmProviderClient client(
            YandexLlmPolicyProperties policy,
            Duration readTimeout,
            Clock clock,
            SimpleMeterRegistry registry
    ) {
        YandexLlmProperties properties = new YandexLlmProperties(
                "folder",
                "secret-key",
                MODEL,
                Duration.ofSeconds(1),
                readTimeout
        );
        return new YandexLlmProviderClient(
                properties,
                policy,
                objectMapper,
                new YandexLlmMetrics(registry),
                clock,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/v1/chat/completions"),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .build()
        );
    }

    private YandexLlmPolicyProperties policy(int maxResponseBytes) {
        return new YandexLlmPolicyProperties(
                32_768,
                maxResponseBytes,
                new java.math.BigDecimal("0.8"),
                new java.math.BigDecimal("0.8"),
                new java.math.BigDecimal("0.8")
        );
    }

    private LlmProviderRequest request(Instant deadline) {
        return new LlmProviderRequest(
                UUID.fromString("5f82f723-3e8e-42fb-b24a-a0374c6e5575"),
                "YANDEX",
                MODEL,
                "Return one JSON object.",
                "{\"storeRef\":\"S01\"}",
                "{\"type\":\"object\",\"additionalProperties\":false}",
                new java.math.BigDecimal("0.2"),
                4000,
                deadline
        );
    }

    private JsonNode readTree(String value) {
        return objectMapper.readTree(value);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );
        captured.set(new CapturedRequest(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("OpenAI-Project"),
                exchange.getRequestHeaders().getFirst("x-data-logging-enabled"),
                exchange.getRequestHeaders().getFirst("x-client-request-id"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                requestBody
        ));
        ResponseFixture response = fixture.get();
        if (!response.delay().isZero()) {
            try {
                Thread.sleep(response.delay());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        if (response.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", response.retryAfter());
        }
        exchange.sendResponseHeaders(response.status(), 0);
        try {
            exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
            // Expected when the client enforces a timeout or response-size limit.
        } finally {
            exchange.close();
        }
    }

    private record CapturedRequest(
            String authorization,
            String project,
            String dataLoggingEnabled,
            String clientRequestId,
            String contentType,
            String body
    ) {
    }

    private record ResponseFixture(
            int status,
            String contentType,
            String body,
            String retryAfter,
            Duration delay
    ) {
        private static ResponseFixture json(int status, String body) {
            return new ResponseFixture(
                    status,
                    "application/json",
                    body,
                    null,
                    Duration.ZERO
            );
        }
    }
}
