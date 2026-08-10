package com.storeanalytics.sync.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladHttpException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladTransportException;
import com.storeanalytics.sync.config.SyncWorkerSchedulingConfiguration;
import com.storeanalytics.sync.exception.ReturnSyncCapacityException;
import com.storeanalytics.sync.exception.SalesSyncCapacityException;
import com.storeanalytics.sync.model.SyncJobPhase;
import java.time.Duration;
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
        prefix = "app.sync",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SyncJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncJobWorker.class);

    private final String workerId = UUID.randomUUID().toString();
    private final SyncJobCoordinator coordinator;
    private final SyncJobExecutionService executionService;
    private final SyncProperties properties;

    public SyncJobWorker(
            SyncJobCoordinator coordinator,
            SyncJobExecutionService executionService,
            SyncProperties properties
    ) {
        this.coordinator = coordinator;
        this.executionService = executionService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.sync.worker-delay:5s}",
            scheduler = SyncWorkerSchedulingConfiguration.SYNC_WORKER_SCHEDULER
    )
    public void processNextStep() {
        Optional<SyncJobClaim> candidate = coordinator.claimNext(workerId);
        if (candidate.isEmpty()) {
            return;
        }
        SyncJobClaim claim = candidate.get();
        try {
            executionService.execute(claim);
            coordinator.completeStep(claim.jobId(), workerId);
        } catch (SalesSyncCapacityException | ReturnSyncCapacityException exception) {
            handleCapacity(claim);
        } catch (RuntimeException exception) {
            handleFailure(claim, exception);
        }
    }

    private void handleCapacity(SyncJobClaim claim) {
        if (coordinator.shrinkWindow(claim.jobId(), workerId)) {
            LOGGER.info(
                    "Reduced synchronization window for job {} in phase {}",
                    claim.jobId(),
                    claim.phase()
            );
            return;
        }
        coordinator.retryOrFail(
                claim.jobId(),
                workerId,
                "Minimum synchronization window still exceeds source capacity",
                false,
                Duration.ZERO
        );
    }

    private void handleFailure(SyncJobClaim claim, RuntimeException exception) {
        boolean retryable = isRetryableFailure(exception);
        Duration delay = retryDelay(claim, exception);
        String errorCode = failureCode(exception);
        String summary = "Synchronization phase " + claim.phase()
                + " failed: " + errorCode;
        if (isRateLimitedWindowPhase(claim, exception)
                && coordinator.shrinkWindowForRetry(
                        claim.jobId(), workerId, summary, delay
                )) {
            LOGGER.info(
                    "Reduced rate-limited synchronization window for job {} "
                            + "in phase {}; retry delayed by {}",
                    claim.jobId(),
                    claim.phase(),
                    delay
            );
            return;
        }
        coordinator.retryOrFail(
                claim.jobId(),
                workerId,
                summary,
                retryable,
                delay
        );

        LiveSkladHttpException httpFailure = find(
                exception,
                LiveSkladHttpException.class
        );
        LiveSkladRateLimitException rateLimit = find(
                exception,
                LiveSkladRateLimitException.class
        );
        String operation = httpFailure != null
                ? httpFailure.getOperation()
                : rateLimit == null ? null : rateLimit.getOperation();
        Integer status = httpFailure != null
                ? Integer.valueOf(httpFailure.getStatusCode())
                : rateLimit == null
                        ? null
                        : Integer.valueOf(rateLimit.getStatusCode());
        LOGGER.warn(
                "Synchronization job {} phase {} failed with {}; "
                        + "upstreamOperation={}; upstreamStatus={}; retryable={}",
                claim.jobId(),
                claim.phase(),
                errorCode,
                operation,
                status,
                retryable
        );
    }

    private boolean isRateLimitedWindowPhase(
            SyncJobClaim claim,
            Throwable exception
    ) {
        return (claim.phase() == SyncJobPhase.SALES
                || claim.phase() == SyncJobPhase.RETURNS)
                && contains(exception, LiveSkladRateLimitException.class);
    }

    private Duration retryDelay(SyncJobClaim claim, Throwable exception) {
        Duration localDelay = properties.retryInitialDelay().multipliedBy(
                1L << Math.min(claim.attemptCount(), 20)
        );
        if (localDelay.compareTo(properties.retryMaxDelay()) > 0) {
            localDelay = properties.retryMaxDelay();
        }
        LiveSkladRateLimitException rateLimit = find(
                exception,
                LiveSkladRateLimitException.class
        );
        Duration minimumDelay = rateLimit != null
                && rateLimit.getRetryAfter().compareTo(localDelay) > 0
                ? rateLimit.getRetryAfter() : localDelay;
        Duration cappedDelay = minimumDelay.compareTo(
                properties.retryAbsoluteMaxDelay()
        ) > 0 ? properties.retryAbsoluteMaxDelay() : minimumDelay;
        return addDeterministicJitter(claim, cappedDelay);
    }

    private Duration addDeterministicJitter(
            SyncJobClaim claim,
            Duration delay
    ) {
        long remainingMillis = properties.retryAbsoluteMaxDelay()
                .minus(delay)
                .toMillis();
        long maximumJitterMillis = Math.min(
                delay.toMillis() / 5,
                remainingMillis
        );
        if (maximumJitterMillis <= 0) {
            return delay;
        }
        long seed = claim.jobId().getMostSignificantBits()
                ^ claim.jobId().getLeastSignificantBits()
                ^ Integer.toUnsignedLong(claim.attemptCount() + 1);
        long jitterMillis = Math.floorMod(seed, maximumJitterMillis + 1);
        return delay.plusMillis(jitterMillis);
    }

    private boolean isRetryableFailure(Throwable exception) {
        if (contains(exception, LiveSkladPayloadRejectedException.class)) {
            return false;
        }
        if (contains(exception, LiveSkladRateLimitException.class)
                || contains(exception, LiveSkladTransportException.class)) {
            return true;
        }
        LiveSkladHttpException httpFailure = find(
                exception,
                LiveSkladHttpException.class
        );
        if (httpFailure != null) {
            return httpFailure.isRetryable();
        }
        return contains(exception, TransientDataAccessException.class);
    }

    private String failureCode(Throwable exception) {
        LiveSkladPayloadRejectedException rejected = find(
                exception,
                LiveSkladPayloadRejectedException.class
        );
        if (rejected != null) {
            return "LIVESKLAD_PAYLOAD_" + rejected.getReason();
        }
        if (contains(exception, LiveSkladRateLimitException.class)) {
            return "LIVESKLAD_RATE_LIMIT";
        }
        LiveSkladHttpException httpFailure = find(
                exception,
                LiveSkladHttpException.class
        );
        if (httpFailure != null) {
            return "LIVESKLAD_HTTP_" + httpFailure.getStatusCode();
        }
        if (contains(exception, LiveSkladTransportException.class)) {
            return "LIVESKLAD_TRANSPORT";
        }
        if (contains(exception, LiveSkladException.class)) {
            return "LIVESKLAD_PERMANENT";
        }
        if (contains(exception, TransientDataAccessException.class)) {
            return "TRANSIENT_DATABASE";
        }
        return exception.getClass().getSimpleName();
    }
    private boolean contains(Throwable failure, Class<? extends Throwable> type) {
        return find(failure, type) != null;
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
}
