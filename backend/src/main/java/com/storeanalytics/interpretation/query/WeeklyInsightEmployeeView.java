package com.storeanalytics.interpretation.query;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record WeeklyInsightEmployeeView(
        UUID employeeId,
        String displayName,
        String analysisStatus,
        JsonNode insight
) {

    public WeeklyInsightEmployeeView {
        insight = insight.deepCopy();
    }
}
