package com.storeanalytics.interpretation.config;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.contract.LlmContractResources;

public final class InterpretationConfigurationReadiness {

    public InterpretationConfigurationReadiness(
            InterpretationFeatureProperties featureProperties,
            LlmGenerationProperties generationProperties,
            YandexLlmProperties yandexProperties,
            WeeklySnapshotWorkerProperties workerProperties,
            WeeklySnapshotPlannerProperties plannerProperties,
            LlmAnalysisPlannerProperties generationPlannerProperties,
            LlmAnalysisWorkerProperties generationWorkerProperties
    ) {
        if (workerProperties.enabled() && !featureProperties.snapshotEnabled()) {
            throw new IllegalStateException(
                    "Snapshot worker is enabled, but snapshot feature is disabled"
            );
        }
        if (plannerProperties.enabled() && !featureProperties.snapshotEnabled()) {
            throw new IllegalStateException(
                    "Snapshot planner is enabled, but snapshot feature is disabled"
            );
        }
        if (generationPlannerProperties.enabled()
                && !featureProperties.generationEnabled()) {
            throw new IllegalStateException(
                    "LLM analysis planner is enabled, but generation is disabled"
            );
        }
        if (generationWorkerProperties.enabled()
                && !featureProperties.generationEnabled()) {
            throw new IllegalStateException(
                    "LLM analysis worker is enabled, but generation is disabled"
            );
        }
        if (!featureProperties.generationEnabled()) {
            return;
        }
        if (!yandexProperties.isReadyForGeneration()) {
            throw new IllegalStateException(
                    "LLM generation is enabled, but Yandex LLM credentials/model are incomplete"
            );
        }
        if (!LlmContractResources.isSupportedPair(
                generationProperties.promptVersion(),
                generationProperties.contentSchemaVersion()
        )) {
            throw new IllegalStateException(
                    "Configured LLM prompt/schema pair is not packaged"
            );
        }
    }
}
