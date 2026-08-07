package com.storeanalytics.interpretation.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.config.LlmAnalysisWorkerSchedulingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;

class LlmPublicationWorkerSchedulingTest {

    @Test
    void isolatesExecutionAndHeartbeatOnDedicatedSchedulers()
            throws NoSuchMethodException {
        Scheduled execution = AnnotatedElementUtils.findMergedAnnotation(
                LlmPublicationWorker.class.getDeclaredMethod("processNext"),
                Scheduled.class
        );
        Scheduled heartbeat = AnnotatedElementUtils.findMergedAnnotation(
                LlmPublicationWorker.class.getDeclaredMethod("heartbeat"),
                Scheduled.class
        );

        assertThat(execution).isNotNull();
        assertThat(execution.scheduler()).isEqualTo(
                LlmAnalysisWorkerSchedulingConfiguration.LLM_PUBLICATION_SCHEDULER
        );
        assertThat(heartbeat).isNotNull();
        assertThat(heartbeat.scheduler()).isEqualTo(
                LlmAnalysisWorkerSchedulingConfiguration
                        .LLM_PUBLICATION_HEARTBEAT_SCHEDULER
        );
    }
}
