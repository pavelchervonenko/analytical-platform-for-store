package com.storeanalytics.interpretation.operations;

public record LlmOperationsConfigurationView(
        boolean snapshotsEnabled,
        boolean generationEnabled,
        boolean publicationEnabled,
        boolean providerConfigured,
        String model
) {
}
