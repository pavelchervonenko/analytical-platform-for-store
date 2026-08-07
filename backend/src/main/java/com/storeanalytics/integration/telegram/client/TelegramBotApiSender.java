package com.storeanalytics.integration.telegram.client;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.notification.config.TelegramNotificationProperties;
import com.storeanalytics.notification.delivery.TelegramSendException;
import com.storeanalytics.notification.delivery.TelegramSendFailureKind;
import com.storeanalytics.notification.delivery.TelegramSendReceipt;
import com.storeanalytics.notification.delivery.TelegramSendRequest;
import com.storeanalytics.notification.delivery.TelegramSender;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public final class TelegramBotApiSender implements TelegramSender {

    private final TelegramNotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;
    private final URI endpoint;

    @Autowired
    public TelegramBotApiSender(
            TelegramNotificationProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this(
                properties,
                objectMapper,
                clock,
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                endpoint(properties.apiBaseUrl(), properties.botToken())
        );
    }

    TelegramBotApiSender(
            TelegramNotificationProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            HttpClient httpClient,
            URI endpoint
    ) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
    }

    @Override
    public TelegramSendReceipt send(TelegramSendRequest request) {
        Instant startedAt = clock.instant();
        validateReady(request);
        byte[] payload = payload(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout(request.deadline()))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.ACCEPT_ENCODING, "identity")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            return handleResponse(response, startedAt);
        } catch (HttpTimeoutException exception) {
            throw failure(
                    TelegramSendFailureKind.UNKNOWN_OUTCOME,
                    "Telegram response deadline was exceeded",
                    null,
                    null,
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(
                    TelegramSendFailureKind.UNKNOWN_OUTCOME,
                    "Telegram request was interrupted after provider call started",
                    null,
                    null,
                    exception
            );
        } catch (IOException exception) {
            throw failure(
                    TelegramSendFailureKind.UNKNOWN_OUTCOME,
                    "Telegram transport outcome is unknown",
                    null,
                    null,
                    exception
            );
        }
    }

    private TelegramSendReceipt handleResponse(
            HttpResponse<InputStream> response,
            Instant startedAt
    ) throws IOException {
        int httpStatus = response.statusCode();
        byte[] body;
        try (InputStream input = response.body()) {
            body = readBounded(input);
        } catch (ResponseTooLargeException exception) {
            throw failure(
                    TelegramSendFailureKind.UNKNOWN_OUTCOME,
                    "Telegram response exceeds the configured byte limit",
                    httpStatus,
                    null,
                    exception
            );
        }
        JsonNode root = parse(body, httpStatus);
        boolean ok = root.path("ok").asBoolean(false);
        if (httpStatus >= 200 && httpStatus < 300 && ok) {
            JsonNode messageId = root.path("result").path("message_id");
            if (!messageId.isIntegralNumber()) {
                throw failure(
                        TelegramSendFailureKind.UNKNOWN_OUTCOME,
                        "Telegram success response has no message identifier",
                        httpStatus,
                        null,
                        null
                );
            }
            return new TelegramSendReceipt(
                    messageId.asText(),
                    httpStatus,
                    elapsedMillis(startedAt)
            );
        }
        int providerCode = root.path("error_code").isIntegralNumber()
                ? root.path("error_code").asInt() : httpStatus;
        Instant retryAfterAt = retryAfterAt(root);
        throw classifiedFailure(providerCode, httpStatus, retryAfterAt);
    }

    private TelegramSendException classifiedFailure(
            int providerCode,
            int httpStatus,
            Instant retryAfterAt
    ) {
        if (providerCode == 429 || httpStatus == 429) {
            return failure(
                    TelegramSendFailureKind.RATE_LIMITED,
                    "Telegram rate limit was reached",
                    httpStatus,
                    retryAfterAt,
                    null
            );
        }
        if (providerCode == 401 || httpStatus == 401) {
            return failure(
                    TelegramSendFailureKind.AUTHENTICATION,
                    "Telegram bot authentication failed",
                    httpStatus,
                    null,
                    null
            );
        }
        if (providerCode == 403 || httpStatus == 403) {
            return failure(
                    TelegramSendFailureKind.BOT_BLOCKED,
                    "Telegram destination rejected bot access",
                    httpStatus,
                    null,
                    null
            );
        }
        if (providerCode >= 500 || httpStatus >= 500) {
            return failure(
                    TelegramSendFailureKind.TRANSIENT_PROVIDER,
                    "Telegram provider is temporarily unavailable",
                    httpStatus,
                    retryAfterAt,
                    null
            );
        }
        return failure(
                TelegramSendFailureKind.PERMANENT_PROVIDER_REJECTED,
                "Telegram permanently rejected the delivery",
                httpStatus,
                null,
                null
        );
    }

    private JsonNode parse(byte[] body, int httpStatus) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root != null && root.isObject()) {
                return root;
            }
        } catch (RuntimeException exception) {
            return malformedResponse(httpStatus, exception);
        }
        return malformedResponse(httpStatus, null);
    }

    private JsonNode malformedResponse(int httpStatus, Throwable cause) {
        if (httpStatus < 200 || httpStatus >= 300) {
            throw classifiedFailure(httpStatus, httpStatus, null);
        }
        throw failure(
                TelegramSendFailureKind.UNKNOWN_OUTCOME,
                "Telegram returned an empty or malformed success response",
                httpStatus,
                null,
                cause
        );
    }

    private byte[] payload(TelegramSendRequest request) {
        try {
            return objectMapper.writeValueAsBytes(Map.of(
                    "chat_id", request.chatId(),
                    "text", request.text(),
                    "protect_content", true,
                    "disable_notification", false,
                    "allow_paid_broadcast", false,
                    "link_preview_options", Map.of("is_disabled", true)
            ));
        } catch (RuntimeException exception) {
            throw failure(
                    TelegramSendFailureKind.INVALID_REQUEST,
                    "Telegram request could not be serialized",
                    null,
                    null,
                    exception
            );
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        int limit = properties.maxResponseBytes();
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(limit, 8_192)
        );
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new ResponseTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Instant retryAfterAt(JsonNode root) {
        JsonNode secondsNode = root.path("parameters").path("retry_after");
        if (!secondsNode.isIntegralNumber()) {
            return null;
        }
        long seconds = secondsNode.asLong();
        if (seconds <= 0 || seconds > Duration.ofDays(1).toSeconds()) {
            return null;
        }
        return clock.instant().plusSeconds(seconds);
    }

    private void validateReady(TelegramSendRequest request) {
        if (!properties.enabled() || !properties.deliveryEnabled()
                || !properties.isDeliveryConfigured()) {
            throw failure(
                    TelegramSendFailureKind.AUTHENTICATION,
                    "Telegram delivery configuration is incomplete",
                    null,
                    null,
                    null
            );
        }
        if (!request.deadline().isAfter(clock.instant())) {
            throw failure(
                    TelegramSendFailureKind.INVALID_REQUEST,
                    "Telegram delivery deadline has expired",
                    null,
                    null,
                    null
            );
        }
    }

    private Duration timeout(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.compareTo(properties.readTimeout()) < 0
                ? remaining : properties.readTimeout();
    }

    private long elapsedMillis(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
    }

    private TelegramSendException failure(
            TelegramSendFailureKind kind,
            String safeMessage,
            Integer httpStatus,
            Instant retryAfterAt,
            Throwable cause
    ) {
        return new TelegramSendException(
                kind,
                safeMessage,
                httpStatus,
                retryAfterAt,
                cause
        );
    }

    private static URI endpoint(String apiBaseUrl, String botToken) {
        String base = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        return URI.create(base + "/bot" + botToken + "/sendMessage");
    }

    private static final class ResponseTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;
    }
}
