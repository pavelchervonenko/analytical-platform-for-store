package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class LlmAnalysisPlanningSchedulingConfigurationTest {

    @Test
    void createsDedicatedSingleThreadPlannerScheduler() {
        LlmAnalysisPlanningSchedulingConfiguration configuration =
                new LlmAnalysisPlanningSchedulingConfiguration();
        ThreadPoolTaskScheduler scheduler =
                configuration.llmAnalysisPlanningScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isOne();
            assertThat(scheduler.getThreadNamePrefix())
                    .isEqualTo("llm-analysis-planning-");
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                    .isTrue();
        } finally {
            scheduler.shutdown();
        }
    }
}
