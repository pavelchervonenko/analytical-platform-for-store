package com.storeanalytics.interpretation.publication;

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
public class LlmPublicationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LlmPublicationWorker.class
    );

    private final String workerId = UUID.randomUUID().toString();
    private final LlmPublicationJobRunner runner;
    private final LlmAnalysisWorkerProperties properties;

    public LlmPublicationWorker(
            LlmPublicationJobRunner runner,
            LlmAnalysisWorkerProperties properties
    ) {
        this.runner = runner;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.generation-worker.worker-delay:5s}",
            scheduler = LlmAnalysisWorkerSchedulingConfiguration.LLM_PUBLICATION_SCHEDULER
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
                    "LLM publication worker step failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.generation-worker.heartbeat-interval:15s}",
            scheduler = LlmAnalysisWorkerSchedulingConfiguration
                    .LLM_PUBLICATION_HEARTBEAT_SCHEDULER
    )
    public void heartbeat() {
        try {
            runner.heartbeatCurrent(properties.leaseDuration());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "LLM publication heartbeat failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
