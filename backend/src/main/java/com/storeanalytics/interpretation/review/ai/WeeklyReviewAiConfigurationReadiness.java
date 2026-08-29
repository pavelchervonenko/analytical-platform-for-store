package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.generation.LlmProviderRegistry;
import org.springframework.stereotype.Component;

/** Fails worker startup on unsafe or incomplete flag combinations. */
@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public final class WeeklyReviewAiConfigurationReadiness {

    public WeeklyReviewAiConfigurationReadiness(
            WeeklyReviewAiGenerationProperties properties,
            YandexLlmProperties yandexProperties,
            LlmProviderRegistry providerRegistry
    ) {
        if ((properties.plannerEnabled() || properties.workerEnabled())
                && !properties.enabled()) {
            throw new IllegalStateException(
                    "Weekly review AI planner/worker requires the parent feature"
            );
        }
        if (!properties.enabled()) {
            return;
        }
        if (!"YANDEX".equals(properties.providerCode())) {
            throw new IllegalStateException(
                    "Weekly review AI provider is not approved: "
                            + properties.providerCode()
            );
        }
        if (!yandexProperties.isReadyForGeneration()) {
            throw new IllegalStateException(
                    "Weekly review AI is enabled, but Yandex credentials/model are incomplete"
            );
        }
        if (properties.workerEnabled()) {
            providerRegistry.requireProvider(properties.providerCode());
        }
    }
}
