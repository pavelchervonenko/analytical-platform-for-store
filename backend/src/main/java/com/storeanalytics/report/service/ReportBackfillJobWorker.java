package com.storeanalytics.report.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.common.config.ReportBackfillProperties;
import com.storeanalytics.report.config.ReportBackfillSchedulingConfiguration;
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
        prefix = "app.reports.backfill",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReportBackfillJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ReportBackfillJobWorker.class
    );

    private final String workerId = UUID.randomUUID().toString();
    private final ReportBackfillJobCoordinator coordinator;
    private final ReportBackfillJobExecutionService executionService;
    private final ReportBackfillProperties properties;
    private final ReportBackfillMetrics metrics;

    public ReportBackfillJobWorker(
            ReportBackfillJobCoordinator coordinator,
            ReportBackfillJobExecutionService executionService,
            ReportBackfillProperties properties,
            ReportBackfillMetrics metrics
    ) {
        this.coordinator = coordinator;
        this.executionService = executionService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${app.reports.backfill.worker-delay:5s}",
            scheduler = ReportBackfillSchedulingConfiguration
                    .REPORT_BACKFILL_SCHEDULER
    )
    public void processNextStep() {
        Optional<ReportBackfillJobClaim> candidate = coordinator.claimNext(
                workerId
        );
        if (candidate.isEmpty()) {
            return;
        }
        ReportBackfillJobClaim claim = candidate.get();
        try {
            metrics.recordStep(
                    claim.phase(),
                    () -> executionService.execute(claim, workerId)
            );
        } catch (RuntimeException exception) {
            handleFailure(claim, exception);
        }
    }

    private void handleFailure(
            ReportBackfillJobClaim claim,
            RuntimeException exception
    ) {
        boolean retryable = contains(
                exception,
                TransientDataAccessException.class
        );
        String summary = "Report backfill phase " + claim.phase()
                + " failed: " + exception.getClass().getSimpleName();
        coordinator.retryOrFail(
                claim.jobId(),
                workerId,
                summary,
                retryable,
                retryDelay(claim.attemptCount())
        );
        LOGGER.warn(
                "Report backfill job {} phase {} failed with {}; retryable={}",
                claim.jobId(),
                claim.phase(),
                exception.getClass().getSimpleName(),
                retryable
        );
    }

    private Duration retryDelay(int attemptCount) {
        Duration delay = properties.retryInitialDelay().multipliedBy(
                1L << Math.min(attemptCount, 20)
        );
        return delay.compareTo(properties.retryMaxDelay()) > 0
                ? properties.retryMaxDelay() : delay;
    }

    private boolean contains(
            Throwable failure,
            Class<? extends Throwable> type
    ) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
