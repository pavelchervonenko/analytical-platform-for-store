package com.storeanalytics.integration.livesklad.webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladHttpException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladReturnChangedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladTransportException;
import com.storeanalytics.sync.service.ReturnSyncService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.livesklad.webhook.worker",
        name = "enabled",
        havingValue = "true"
)
class LiveSkladSaleReturnWebhookWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LiveSkladSaleReturnWebhookWorker.class
    );
    private static final int MAX_SOURCE_DOCUMENT_ID_LENGTH = 256;

    private final String workerId = UUID.randomUUID().toString();
    private final LiveSkladWebhookStore store;
    private final ReturnSyncService returnSyncService;
    private final LiveSkladWebhookWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LiveSkladSaleReturnWebhookWorker(
            LiveSkladWebhookStore store,
            ReturnSyncService returnSyncService,
            LiveSkladWebhookWorkerProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.store = store;
        this.returnSyncService = returnSyncService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.livesklad.webhook.worker.delay:5s}",
            scheduler = LiveSkladWebhookSchedulingConfiguration.SCHEDULER
    )
    void processNext() {
        Instant now = clock.instant();
        Optional<LiveSkladWebhookClaim> candidate = store.claimNextSaleReturn(
                workerId,
                now,
                properties.leaseDuration(),
                properties.maxAttempts()
        );
        if (candidate.isEmpty()) {
            return;
        }
        LiveSkladWebhookClaim claim = candidate.orElseThrow();
        if (claim.payloadMismatch()) {
            store.failPermanently(
                    claim.id(), workerId, now, "PAYLOAD_MISMATCH",
                    "Webhook event was redelivered with a different payload"
            );
            return;
        }

        String sourceDocumentId;
        try {
            sourceDocumentId = claim.recovery()
                    ? claim.sourceDocumentId()
                    : sourceDocumentId(claim.payload());
        } catch (InvalidWebhookPayloadException exception) {
            store.failPermanently(
                    claim.id(), workerId, now, exception.code(),
                    exception.getMessage()
            );
            return;
        }

        try {
            store.recordSourceDocument(claim.id(), workerId, sourceDocumentId);
            if (claim.recovery()) {
                returnSyncService.recoverReturn(
                        sourceDocumentId,
                        claim.recoveryExpectedDocumentNumber(),
                        claim.recoveryExpectedNetAmount(),
                        claim.recoveryExpectedPositionCount()
                );
            } else {
                returnSyncService.synchronizeWebhookReturn(sourceDocumentId);
            }
            store.complete(claim.id(), workerId, clock.instant());
        } catch (RuntimeException exception) {
            handleFailure(claim, exception);
        }
    }

    private String sourceDocumentId(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException exception) {
            throw new InvalidWebhookPayloadException(
                    "INVALID_JSON", "Stored webhook payload is not valid JSON"
            );
        }
        JsonNode id = root == null ? null : root.path("data").get("id");
        if (id == null || id.isNull() || !id.isValueNode()) {
            throw new InvalidWebhookPayloadException(
                    "SOURCE_DOCUMENT_ID_MISSING",
                    "Webhook payload does not contain scalar data.id"
            );
        }
        String value = id.asText().trim();
        if (value.isEmpty() || value.length() > MAX_SOURCE_DOCUMENT_ID_LENGTH) {
            throw new InvalidWebhookPayloadException(
                    "SOURCE_DOCUMENT_ID_INVALID",
                    "Webhook payload data.id is empty or too long"
            );
        }
        return value;
    }

    private void handleFailure(
            LiveSkladWebhookClaim claim,
            RuntimeException exception
    ) {
        boolean retryable = isRetryable(exception);
        boolean exhausted = claim.attemptCount() >= properties.maxAttempts();
        String code = claim.recovery()
                && find(exception, InvalidRequestException.class) != null
                ? "RETURN_RECOVERY_EXPECTATION_MISMATCH"
                : failureCode(exception);
        String summary = "Sale-return webhook processing failed: " + code;
        if (retryable && !exhausted) {
            store.retry(
                    claim.id(),
                    workerId,
                    clock.instant().plus(retryDelay(claim.attemptCount())),
                    code,
                    summary
            );
        } else {
            store.failPermanently(
                    claim.id(),
                    workerId,
                    clock.instant(),
                    exhausted ? "RETRY_EXHAUSTED_" + code : code,
                    summary
            );
        }
        LOGGER.warn(
                "LiveSklad sale-return webhook event {} failed with {}; "
                        + "attempt={}; retryable={}; exhausted={}",
                claim.eventId(), code, claim.attemptCount(), retryable, exhausted
        );
    }

    private Duration retryDelay(int attemptCount) {
        long multiplier = 1L << Math.min(Math.max(attemptCount - 1, 0), 20);
        Duration delay = properties.retryInitialDelay().multipliedBy(multiplier);
        return delay.compareTo(properties.retryMaxDelay()) > 0
                ? properties.retryMaxDelay()
                : delay;
    }

    private boolean isRetryable(Throwable failure) {
        if (find(failure, LiveSkladPayloadRejectedException.class) != null) {
            return false;
        }
        if (find(failure, LiveSkladRateLimitException.class) != null
                || find(failure, LiveSkladTransportException.class) != null
                || find(failure, LiveSkladReturnChangedException.class) != null
                || find(failure, TransientDataAccessException.class) != null) {
            return true;
        }
        LiveSkladHttpException http = find(
                failure, LiveSkladHttpException.class
        );
        return http != null && (
                http.isRetryable()
                || http.getStatusCode() == 404
                || http.getStatusCode() == 409
        );
    }

    private String failureCode(Throwable failure) {
        LiveSkladPayloadRejectedException rejected = find(
                failure, LiveSkladPayloadRejectedException.class
        );
        if (rejected != null) {
            return "LIVESKLAD_PAYLOAD_" + rejected.getReason();
        }
        if (find(failure, LiveSkladRateLimitException.class) != null) {
            return "LIVESKLAD_RATE_LIMIT";
        }
        LiveSkladHttpException http = find(
                failure, LiveSkladHttpException.class
        );
        if (http != null) {
            return "LIVESKLAD_HTTP_" + http.getStatusCode();
        }
        if (find(failure, LiveSkladTransportException.class) != null) {
            return "LIVESKLAD_TRANSPORT";
        }
        if (find(failure, LiveSkladReturnChangedException.class) != null) {
            return "LIVESKLAD_RETURN_CHANGED";
        }
        if (find(failure, TransientDataAccessException.class) != null) {
            return "TRANSIENT_DATABASE";
        }
        if (find(failure, LiveSkladException.class) != null) {
            return "LIVESKLAD_PERMANENT";
        }
        return failure.getClass().getSimpleName();
    }

    private <T extends Throwable> T find(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class InvalidWebhookPayloadException
            extends RuntimeException {

        private final String code;

        private InvalidWebhookPayloadException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
