package com.storeanalytics.interpretation.config;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.generation-worker",
        name = "enabled",
        havingValue = "true"
)
public class LlmAnalysisWorkerSchedulingConfiguration {

    public static final String LLM_PROVIDER_CALL_SCHEDULER =
            "llmProviderCallScheduler";
    public static final String LLM_PROVIDER_HEARTBEAT_SCHEDULER =
            "llmProviderHeartbeatScheduler";
    public static final String LLM_VALIDATION_SCHEDULER =
            "llmValidationScheduler";
    public static final String LLM_VALIDATION_HEARTBEAT_SCHEDULER =
            "llmValidationHeartbeatScheduler";
    public static final String LLM_PUBLICATION_SCHEDULER =
            "llmPublicationScheduler";
    public static final String LLM_PUBLICATION_HEARTBEAT_SCHEDULER =
            "llmPublicationHeartbeatScheduler";

    @Bean(name = LLM_PROVIDER_CALL_SCHEDULER)
    public ThreadPoolTaskScheduler llmProviderCallScheduler() {
        return scheduler("llm-provider-call-");
    }

    @Bean(name = LLM_PROVIDER_HEARTBEAT_SCHEDULER)
    public ThreadPoolTaskScheduler llmProviderHeartbeatScheduler() {
        return scheduler("llm-provider-heartbeat-");
    }

    @Bean(name = LLM_VALIDATION_SCHEDULER)
    public ThreadPoolTaskScheduler llmValidationScheduler() {
        return scheduler("llm-validation-");
    }

    @Bean(name = LLM_VALIDATION_HEARTBEAT_SCHEDULER)
    public ThreadPoolTaskScheduler llmValidationHeartbeatScheduler() {
        return scheduler("llm-validation-heartbeat-");
    }

    @Bean(name = LLM_PUBLICATION_SCHEDULER)
    public ThreadPoolTaskScheduler llmPublicationScheduler() {
        return scheduler("llm-publication-");
    }

    @Bean(name = LLM_PUBLICATION_HEARTBEAT_SCHEDULER)
    public ThreadPoolTaskScheduler llmPublicationHeartbeatScheduler() {
        return scheduler("llm-publication-heartbeat-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
