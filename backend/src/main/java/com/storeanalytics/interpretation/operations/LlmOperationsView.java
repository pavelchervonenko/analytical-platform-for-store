package com.storeanalytics.interpretation.operations;

import java.time.Instant;
import java.util.List;

public record LlmOperationsView(
        Instant generatedAt,
        LlmOperationsConfigurationView configuration,
        LlmOperationsSummaryView summary,
        List<LlmJobIncidentView> incidents
) {
    public LlmOperationsView {
        incidents = List.copyOf(incidents);
    }
}
