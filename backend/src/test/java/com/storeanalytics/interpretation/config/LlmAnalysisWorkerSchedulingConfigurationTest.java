package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class LlmAnalysisWorkerSchedulingConfigurationTest {

    @Test
    void createsSeparateSingleThreadExecutionAndHeartbeatSchedulers() {
        LlmAnalysisWorkerSchedulingConfiguration configuration =
                new LlmAnalysisWorkerSchedulingConfiguration();
        ThreadPoolTaskScheduler execution = configuration.llmProviderCallScheduler();
        ThreadPoolTaskScheduler heartbeat = configuration.llmProviderHeartbeatScheduler();
        execution.initialize();
        heartbeat.initialize();
        try {
            assertThat(execution.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isOne();
            assertThat(heartbeat.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isOne();
            assertThat(execution.getThreadNamePrefix())
                    .isEqualTo("llm-provider-call-");
            assertThat(heartbeat.getThreadNamePrefix())
                    .isEqualTo("llm-provider-heartbeat-");
        } finally {
            execution.shutdown();
            heartbeat.shutdown();
        }
    }
}
