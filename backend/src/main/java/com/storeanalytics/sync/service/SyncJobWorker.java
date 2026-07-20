package com.storeanalytics.sync.service;

import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import com.storeanalytics.sync.exception.ReturnSyncCapacityException;
import com.storeanalytics.sync.exception.SalesSyncCapacityException;
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

    @Scheduled(fixedDelayString = "${app.sync.worker-delay:5s}")
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
        boolean retryable = contains(exception, LiveSkladException.class)
                || contains(exception, TransientDataAccessException.class);
        Duration delay = retryDelay(claim.attemptCount(), exception);
        String summary = "Synchronization phase " + claim.phase()
                + " failed: " + exception.getClass().getSimpleName();
        coordinator.retryOrFail(
                claim.jobId(),
                workerId,
                summary,
                retryable,
                delay
        );
        LOGGER.warn(
                "Synchronization job {} phase {} failed with {}; retryable={}",
                claim.jobId(),
                claim.phase(),
                exception.getClass().getSimpleName(),
                retryable
        );
    }

    private Duration retryDelay(int attemptCount, Throwable exception) {
        Duration delay = properties.retryInitialDelay().multipliedBy(
                1L << Math.min(attemptCount, 20)
        );
        if (delay.compareTo(properties.retryMaxDelay()) > 0) {
            delay = properties.retryMaxDelay();
        }
        LiveSkladRateLimitException rateLimit = find(
                exception,
                LiveSkladRateLimitException.class
        );
        if (rateLimit != null && rateLimit.getRetryAfter().compareTo(delay) > 0) {
            return rateLimit.getRetryAfter();
        }
        return delay;
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
