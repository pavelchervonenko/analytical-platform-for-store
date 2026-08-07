package com.storeanalytics.interpretation.validation;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.interpretation.config.LlmAnalysisWorkerProperties;
import com.storeanalytics.interpretation.config.LlmAnalysisWorkerSchedulingConfiguration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.generation-worker",
        name = "enabled",
        havingValue = "true"
)
public class LlmResponseValidationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LlmResponseValidationWorker.class
    );

    private final String workerId = UUID.randomUUID().toString();
    private final LlmResponseValidationJobRunner runner;
    private final LlmAnalysisWorkerProperties properties;

    public LlmResponseValidationWorker(
            LlmResponseValidationJobRunner runner,
            LlmAnalysisWorkerProperties properties
    ) {
        this.runner = runner;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.generation-worker.worker-delay:5s}",
            scheduler = LlmAnalysisWorkerSchedulingConfiguration.LLM_VALIDATION_SCHEDULER
    )
    public void processNext() {
        try {
            runner.runNext(
                    workerId,
                    properties.leaseDuration(),
                    properties.recoveryDelay()
            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "LLM response validation worker step failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.generation-worker.heartbeat-interval:15s}",
            scheduler = LlmAnalysisWorkerSchedulingConfiguration
                    .LLM_VALIDATION_HEARTBEAT_SCHEDULER
    )
    public void heartbeat() {
        try {
            runner.heartbeatCurrent(properties.leaseDuration());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "LLM response validation heartbeat failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
