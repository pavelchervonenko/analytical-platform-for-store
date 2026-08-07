package com.storeanalytics.integration.llm.yandex;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.interpretation.generation.LlmProviderClient;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public final class YandexLlmProviderClient implements LlmProviderClient {

    static final URI CHAT_COMPLETIONS_URI = URI.create(
            "https://ai.api.cloud.yandex.net/v1/chat/completions"
    );

    private static final String PROVIDER_CODE = "YANDEX";
    private static final String COST_CURRENCY = "RUB";
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);
    private static final int TOKEN_ESTIMATE_OVERHEAD = 64;
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final YandexLlmProperties properties;
    private final YandexLlmPolicyProperties policy;
    private final ObjectMapper objectMapper;
    private final YandexLlmMetrics metrics;
    private final Clock clock;
    private final URI endpoint;
    private final HttpClient httpClient;

    @Autowired
    public YandexLlmProviderClient(
            YandexLlmProperties properties,
            YandexLlmPolicyProperties policy,
            ObjectMapper objectMapper,
            YandexLlmMetrics metrics,
            Clock clock
    ) {
        this(
                properties,
                policy,
                objectMapper,
                metrics,
                clock,
                CHAT_COMPLETIONS_URI,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    YandexLlmProviderClient(
            YandexLlmProperties properties,
            YandexLlmPolicyProperties policy,
            ObjectMapper objectMapper,
            YandexLlmMetrics metrics,
            Clock clock,
            URI endpoint,
            HttpClient httpClient
    ) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public LlmProviderPreflight preflight(LlmProviderRequest request) {
        validateLocalConfiguration(request);
        JsonNode responseSchema = parseJson(
                request.responseSchemaJson(),
                "Yandex response schema is not valid JSON"
        );
        if (!responseSchema.isObject()) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex response schema must be a JSON object"
            );
        }
        int estimatedInputTokens = estimateInputTokens(request);
        BigDecimal maximumCost = calculateCost(
                estimatedInputTokens,
                0,
                request.maxOutputTokens()
        );
        metrics.preflight();
        return new LlmProviderPreflight(
                estimatedInputTokens,
                policy.contextWindowTokens(),
                maximumCost,
                COST_CURRENCY
        );
    }

    @Override
    public LlmProviderResponseReceipt generate(LlmProviderRequest request) {
        Instant startedAt = clock.instant();
        try {
            validateLocalConfiguration(request);
            Duration timeout = requestTimeout(request.callDeadline());
            byte[] requestBody = createRequestBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.ACCEPT_ENCODING, "identity")
                    .header(HttpHeaders.AUTHORIZATION,
                            "Api-Key " + properties.getApiKey())
                    .header("OpenAI-Project", properties.getFolderId())
                    .header("x-data-logging-enabled", "false")
                    .header("x-client-request-id", java.util.UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            return handleResponse(request, response, startedAt);
        } catch (YandexLlmProviderException exception) {
            metrics.failure(exception.getKind(), elapsed(startedAt));
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw recordedFailure(
                    LlmProviderFailureKind.DEADLINE_EXCEEDED,
                    LlmProviderOutcomeCertainty.UNKNOWN,
                    "Yandex LLM request timed out",
                    null,
                    null,
                    exception,
                    startedAt
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw recordedFailure(
                    LlmProviderFailureKind.TRANSPORT,
                    LlmProviderOutcomeCertainty.UNKNOWN,
                    "Yandex LLM request was interrupted",
                    null,
                    null,
                    exception,
                    startedAt
            );
        } catch (IOException exception) {
            throw recordedFailure(
                    LlmProviderFailureKind.TRANSPORT,
                    LlmProviderOutcomeCertainty.UNKNOWN,
                    "Yandex LLM transport failed",
                    null,
                    null,
                    exception,
                    startedAt
            );
        } catch (RuntimeException exception) {
            throw recordedFailure(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    LlmProviderOutcomeCertainty.NOT_SENT,
                    "Yandex LLM request could not be constructed",
                    null,
                    null,
                    exception,
                    startedAt
            );
        }
    }

    private LlmProviderResponseReceipt handleResponse(
            LlmProviderRequest request,
            HttpResponse<InputStream> response,
            Instant startedAt
    ) throws IOException {
        int status = response.statusCode();
        byte[] body;
        try (InputStream input = response.body()) {
            body = readBounded(input);
        } catch (ResponseTooLargeException exception) {
            throw received(
                    LlmProviderFailureKind.RESPONSE_TOO_LARGE,
                    "Yandex LLM response exceeds configured byte limit",
                    status,
                    null,
                    exception
            );
        }
        if (status < 200 || status >= 300) {
            throw httpFailure(status, response, body);
        }
        validateResponseHeaders(response);
        JsonNode root = parseResponse(body);
        JsonNode choice = firstChoice(root);
        validateChoice(choice);
        String content = requiredText(choice.path("message"), "content");
        validateGeneratedContent(content);
        String resolvedModel = requiredText(root, "model");
        if (!resolvedModel.equals(request.requestedModel())) {
            throw received(
                    LlmProviderFailureKind.PROVIDER_INCOMPATIBLE,
                    "Yandex LLM response model does not match the request",
                    status,
                    null,
                    null
            );
        }
        Integer inputTokens = requiredNonNegative(root.path("usage"), "prompt_tokens");
        Integer outputTokens = requiredNonNegative(
                root.path("usage"),
                "completion_tokens"
        );
        Integer totalTokens = optionalNonNegative(root.path("usage"), "total_tokens");
        Integer cachedTokens = optionalNonNegative(
                root.path("usage").path("prompt_tokens_details"),
                "cached_tokens"
        );
        Integer reasoningTokens = optionalNonNegative(
                root.path("usage").path("completion_tokens_details"),
                "reasoning_tokens"
        );
        int cached = cachedTokens == null ? 0 : cachedTokens;
        if (cached > inputTokens) {
            throw malformed("Yandex LLM cached token count exceeds input token count");
        }
        long calculatedTotal = (long) inputTokens + outputTokens;
        if (calculatedTotal > Integer.MAX_VALUE) {
            throw malformed("Yandex LLM total token count exceeds supported range");
        }
        if (totalTokens == null) {
            totalTokens = (int) calculatedTotal;
        } else if (totalTokens != calculatedTotal) {
            throw malformed("Yandex LLM total token count is inconsistent");
        }
        BigDecimal cost = calculateCost(inputTokens, cached, outputTokens);
        Duration elapsed = elapsed(startedAt);
        metrics.success(inputTokens, outputTokens, cached, cost, elapsed);
        return new LlmProviderResponseReceipt(
                content,
                resolvedModel,
                optionalText(root, "id", null),
                inputTokens,
                outputTokens,
                cachedTokens,
                reasoningTokens,
                totalTokens,
                cost,
                COST_CURRENCY,
                elapsed.toMillis(),
                status
        );
    }

    private byte[] createRequestBody(LlmProviderRequest request) {
        JsonNode schema = parseJson(
                request.responseSchemaJson(),
                "Yandex response schema is not valid JSON"
        );
        Map<String, Object> schemaDefinition = new LinkedHashMap<>();
        schemaDefinition.put("name", "weekly_interpretation_v1");
        schemaDefinition.put(
                "description",
                "Versioned weekly retail analytics interpretation"
        );
        schemaDefinition.put("schema", schema);
        schemaDefinition.put("strict", false);

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", schemaDefinition);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.requestedModel());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.inputJson())
        ));
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxOutputTokens());
        payload.put("stream", false);
        payload.put("store", false);
        payload.put("response_format", responseFormat);
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (RuntimeException exception) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex LLM request serialization failed",
                    exception
            );
        }
    }

    private void validateLocalConfiguration(LlmProviderRequest request) {
        if (!properties.isConfigured()) {
            throw notSent(
                    LlmProviderFailureKind.AUTHENTICATION,
                    "Yandex LLM credentials and model must be configured"
            );
        }
        rejectHeaderInjection(properties.getApiKey(), "API key");
        rejectHeaderInjection(properties.getFolderId(), "folder ID");
        String expectedPrefix = "gpt://" + properties.getFolderId() + "/";
        if (!request.requestedModel().startsWith(expectedPrefix)
                || !request.requestedModel().equals(properties.getModelUri())) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex model URI does not match the configured folder/model"
            );
        }
    }

    private void rejectHeaderInjection(String value, String field) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex " + field + " contains forbidden characters"
            );
        }
    }

    private Duration requestTimeout(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw notSent(
                    LlmProviderFailureKind.DEADLINE_EXCEEDED,
                    "Yandex LLM call deadline has expired"
            );
        }
        return remaining.compareTo(properties.getReadTimeout()) < 0
                ? remaining
                : properties.getReadTimeout();
    }

    private int estimateInputTokens(LlmProviderRequest request) {
        long codePoints = (long) codePoints(request.systemPrompt())
                + codePoints(request.inputJson())
                + codePoints(request.responseSchemaJson());
        long estimate = Math.ceilDiv(codePoints, 2) + TOKEN_ESTIMATE_OVERHEAD;
        if (estimate > Integer.MAX_VALUE) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex LLM token estimate exceeds supported range"
            );
        }
        return (int) estimate;
    }

    private int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private BigDecimal calculateCost(int input, int cached, int output) {
        int uncached = input - cached;
        return policy.inputRubPerThousandTokens()
                .multiply(BigDecimal.valueOf(uncached))
                .add(policy.cachedInputRubPerThousandTokens()
                        .multiply(BigDecimal.valueOf(cached)))
                .add(policy.outputRubPerThousandTokens()
                        .multiply(BigDecimal.valueOf(output)))
                .divide(THOUSAND, 6, RoundingMode.CEILING);
    }

    private byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(policy.maxResponseBytes() + 1);
        if (bytes.length > policy.maxResponseBytes()) {
            throw new ResponseTooLargeException();
        }
        return bytes;
    }

    private void validateResponseHeaders(HttpResponse<?> response) {
        String contentType = response.headers()
                .firstValue(HttpHeaders.CONTENT_TYPE)
                .orElse("")
                .toLowerCase(java.util.Locale.ROOT);
        if (!contentType.startsWith("application/json")) {
            throw malformed("Yandex LLM successful response must contain JSON");
        }
        String encoding = response.headers()
                .firstValue(HttpHeaders.CONTENT_ENCODING)
                .orElse("identity");
        if (!encoding.equalsIgnoreCase("identity")) {
            throw malformed("Yandex LLM response encoding is not supported");
        }
    }

    private JsonNode parseResponse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw malformed("Yandex LLM response root must be an object");
            }
            return root;
        } catch (YandexLlmProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw malformed("Yandex LLM response is not valid JSON", exception);
        }
    }

    private JsonNode parseJson(String value, String safeMessage) {
        try {
            return objectMapper.readTree(value);
        } catch (RuntimeException exception) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    safeMessage,
                    exception
            );
        }
    }

    private JsonNode firstChoice(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw malformed("Yandex LLM response must contain exactly one choice");
        }
        return choices.get(0);
    }

    private void validateGeneratedContent(String content) {
        try {
            JsonNode generated = objectMapper.readTree(content);
            if (generated == null || !generated.isObject()) {
                throw malformed("Yandex LLM generated content must be a JSON object");
            }
        } catch (YandexLlmProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw malformed("Yandex LLM generated content is not valid JSON", exception);
        }
    }

    private void validateChoice(JsonNode choice) {
        JsonNode index = choice.path("index");
        if (!index.canConvertToInt() || index.asInt() != 0) {
            throw malformed("Yandex LLM response choice index must be zero");
        }
        String finishReason = requiredText(choice, "finish_reason");
        if ("length".equals(finishReason)) {
            throw received(
                    LlmProviderFailureKind.TRUNCATED_RESPONSE,
                    "Yandex LLM response was truncated",
                    200,
                    null,
                    null
            );
        }
        if ("content_filter".equals(finishReason)) {
            throw received(
                    LlmProviderFailureKind.MODERATION_OR_REFUSAL,
                    "Yandex LLM response was filtered",
                    200,
                    null,
                    null
            );
        }
        if (!"stop".equals(finishReason)) {
            throw received(
                    LlmProviderFailureKind.PROVIDER_INCOMPATIBLE,
                    "Yandex LLM returned an unsupported finish reason",
                    200,
                    null,
                    null
            );
        }
        JsonNode refusal = choice.path("message").path("refusal");
        if (!refusal.isMissingNode() && !refusal.isNull()) {
            throw received(
                    LlmProviderFailureKind.MODERATION_OR_REFUSAL,
                    "Yandex LLM refused the request",
                    200,
                    null,
                    null
            );
        }
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw malformed("Yandex LLM response is missing " + field);
        }
        return value.asText();
    }

    private String optionalText(JsonNode parent, String field, String fallback) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText();
    }

    private Integer requiredNonNegative(JsonNode parent, String field) {
        Integer value = optionalNonNegative(parent, field);
        if (value == null) {
            throw malformed("Yandex LLM response is missing " + field);
        }
        return value;
    }

    private Integer optionalNonNegative(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw malformed("Yandex LLM response has invalid " + field);
        }
        return value.asInt();
    }

    private YandexLlmProviderException httpFailure(
            int status,
            HttpResponse<?> response,
            byte[] body
    ) {
        LlmProviderFailureKind kind;
        if (status == 401 || status == 403) {
            kind = LlmProviderFailureKind.AUTHENTICATION;
        } else if (status == 408) {
            kind = LlmProviderFailureKind.DEADLINE_EXCEEDED;
        } else if (status == 429) {
            kind = LlmProviderFailureKind.RATE_LIMITED;
        } else if (status == 501) {
            kind = LlmProviderFailureKind.PROVIDER_INCOMPATIBLE;
        } else if (status >= 500) {
            kind = LlmProviderFailureKind.TRANSIENT_PROVIDER;
        } else {
            kind = LlmProviderFailureKind.INVALID_REQUEST;
        }
        Duration retryAfter = kind == LlmProviderFailureKind.RATE_LIMITED
                || kind == LlmProviderFailureKind.TRANSIENT_PROVIDER
                ? retryAfter(response)
                : null;
        return received(
                kind,
                providerFailureMessage(status, body),
                status,
                retryAfter,
                null
        );
    }

    private String providerFailureMessage(int status, byte[] body) {
        String prefix = "Yandex LLM returned HTTP " + status;
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String code = safeProviderErrorField(error.path("code"));
            String type = safeProviderErrorField(error.path("type"));
            String category = safeProviderErrorCategory(error.path("message"));
            if (code != null && type != null) {
                return prefix + " (code=" + code + ", type=" + type + ")";
            }
            if (code != null) {
                return prefix + " (code=" + code + ")";
            }
            if (category != null && type != null) {
                return prefix + " (category=" + category + ", type=" + type + ")";
            }
            if (category != null) {
                return prefix + " (category=" + category + ")";
            }
            if (type != null) {
                return prefix + " (type=" + type + ")";
            }
        } catch (RuntimeException ignored) {
            // Provider error bodies are untrusted and must never break classification.
        }
        return prefix;
    }

    private String safeProviderErrorField(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.matches("[A-Za-z0-9_.-]{1,80}") ? value : null;
    }

    private String safeProviderErrorCategory(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String message = node.asText().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("context")) {
            return "context_length";
        }
        if (message.contains("max_tokens")) {
            return "max_tokens";
        }
        if (message.contains("$ref")
                || message.contains("$defs")
                || message.contains("reference")) {
            return "schema_reference";
        }
        if (message.contains("enum")) {
            return "schema_enum";
        }
        if (message.contains("required")) {
            return "schema_required";
        }
        if (message.contains("unsupported")
                || message.contains("not supported")) {
            return "schema_unsupported";
        }
        if (message.contains("too large") || message.contains("size")) {
            return "schema_size";
        }
        if (message.contains("response_format")
                || message.contains("json_schema")
                || message.contains("schema")) {
            return "response_schema";
        }
        if (message.contains("model")) {
            return "model";
        }
        return null;
    }

    private Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers()
                .firstValue(HttpHeaders.RETRY_AFTER)
                .orElse("");
        if (value.isBlank()) {
            return DEFAULT_RETRY_AFTER;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return boundRetryAfter(Duration.ofSeconds(Math.max(0, seconds)));
        } catch (NumberFormatException ignored) {
            // Retry-After may contain an RFC 1123 timestamp.
        }
        try {
            Instant retryAt = ZonedDateTime.parse(
                    value.trim(),
                    DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();
            Duration delay = Duration.between(clock.instant(), retryAt);
            return boundRetryAfter(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException ignored) {
            return DEFAULT_RETRY_AFTER;
        }
    }

    private Duration boundRetryAfter(Duration value) {
        return value.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : value;
    }

    private YandexLlmProviderException malformed(String message) {
        return malformed(message, null);
    }

    private YandexLlmProviderException malformed(String message, Throwable cause) {
        return received(
                LlmProviderFailureKind.MALFORMED_RESPONSE,
                message,
                200,
                null,
                cause
        );
    }

    private YandexLlmProviderException received(
            LlmProviderFailureKind kind,
            String message,
            Integer status,
            Duration retryAfter,
            Throwable cause
    ) {
        return new YandexLlmProviderException(
                kind,
                LlmProviderOutcomeCertainty.RESPONSE_RECEIVED,
                message,
                status,
                retryAfter,
                cause
        );
    }

    private YandexLlmProviderException notSent(
            LlmProviderFailureKind kind,
            String message
    ) {
        return notSent(kind, message, null);
    }

    private YandexLlmProviderException notSent(
            LlmProviderFailureKind kind,
            String message,
            Throwable cause
    ) {
        return new YandexLlmProviderException(
                kind,
                LlmProviderOutcomeCertainty.NOT_SENT,
                message,
                null,
                null,
                cause
        );
    }

    private YandexLlmProviderException recordedFailure(
            LlmProviderFailureKind kind,
            LlmProviderOutcomeCertainty certainty,
            String message,
            Integer status,
            Duration retryAfter,
            Throwable cause,
            Instant startedAt
    ) {
        metrics.failure(kind, elapsed(startedAt));
        return new YandexLlmProviderException(
                kind,
                certainty,
                message,
                status,
                retryAfter,
                cause
        );
    }

    private Duration elapsed(Instant startedAt) {
        Duration value = Duration.between(startedAt, clock.instant());
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() {
            super("Yandex LLM response exceeds configured byte limit");
        }
    }
}
