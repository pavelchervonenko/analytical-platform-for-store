package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.config.LlmAnalysisWorkerSchedulingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;

class LlmResponseValidationWorkerSchedulingTest {

    @Test
    void isolatesExecutionAndHeartbeatOnDedicatedSchedulers()
            throws NoSuchMethodException {
        Scheduled execution = AnnotatedElementUtils.findMergedAnnotation(
                LlmResponseValidationWorker.class.getDeclaredMethod("processNext"),
                Scheduled.class
        );
        Scheduled heartbeat = AnnotatedElementUtils.findMergedAnnotation(
                LlmResponseValidationWorker.class.getDeclaredMethod("heartbeat"),
                Scheduled.class
        );

        assertThat(execution).isNotNull();
        assertThat(execution.scheduler()).isEqualTo(
                LlmAnalysisWorkerSchedulingConfiguration.LLM_VALIDATION_SCHEDULER
        );
        assertThat(heartbeat).isNotNull();
        assertThat(heartbeat.scheduler()).isEqualTo(
                LlmAnalysisWorkerSchedulingConfiguration
                        .LLM_VALIDATION_HEARTBEAT_SCHEDULER
        );
    }
}
