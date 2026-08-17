package com.storeanalytics.interpretation.query;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record WeeklyInsightContentView(
        JsonNode store,
        JsonNode teamInsights,
        List<WeeklyInsightEmployeeView> employees,
        JsonNode dataLimitations,
        List<WeeklyInsightEvidenceView> evidence
) {

    public WeeklyInsightContentView(
            JsonNode store,
            JsonNode teamInsights,
            List<WeeklyInsightEmployeeView> employees,
            JsonNode dataLimitations
    ) {
        this(store, teamInsights, employees, dataLimitations, List.of());
    }

    public WeeklyInsightContentView {
        store = store.deepCopy();
        teamInsights = teamInsights.deepCopy();
        employees = List.copyOf(employees);
        dataLimitations = dataLimitations.deepCopy();
        evidence = List.copyOf(evidence);
    }
}
