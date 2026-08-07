package com.storeanalytics.interpretation.config;

import com.storeanalytics.interpretation.generation.LlmAnalysisRequestFactory;
import com.storeanalytics.interpretation.generation.LlmProviderRegistry;

public final class LlmProviderWorkerReadiness {

    public LlmProviderWorkerReadiness(LlmProviderRegistry providerRegistry) {
        providerRegistry.requireProvider(LlmAnalysisRequestFactory.PROVIDER_CODE);
    }
}
