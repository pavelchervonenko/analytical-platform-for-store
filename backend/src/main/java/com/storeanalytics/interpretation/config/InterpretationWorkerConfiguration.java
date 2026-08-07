package com.storeanalytics.interpretation.config;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.generation.LlmProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnApplicationRole({
        ApplicationRole.WORKER,
        ApplicationRole.COMBINED
})
public class InterpretationWorkerConfiguration {

    @Bean
    InterpretationConfigurationReadiness interpretationConfigurationReadiness(
            InterpretationFeatureProperties featureProperties,
            LlmGenerationProperties generationProperties,
            YandexLlmProperties yandexProperties,
            WeeklySnapshotWorkerProperties workerProperties,
            WeeklySnapshotPlannerProperties plannerProperties,
            LlmAnalysisPlannerProperties generationPlannerProperties,
            LlmAnalysisWorkerProperties generationWorkerProperties
    ) {
        return new InterpretationConfigurationReadiness(
                featureProperties,
                generationProperties,
                yandexProperties,
                workerProperties,
                plannerProperties,
                generationPlannerProperties,
                generationWorkerProperties
        );
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "app.interpretation.generation-worker",
            name = "enabled",
            havingValue = "true"
    )
    LlmProviderWorkerReadiness llmProviderWorkerReadiness(
            LlmProviderRegistry providerRegistry
    ) {
        return new LlmProviderWorkerReadiness(providerRegistry);
    }
}
