package com.storeanalytics.interpretation.query;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record WeeklyInterpretationDetailView(
        WeeklyInterpretationSummaryView interpretation,
        JsonNode content,
        List<WeeklyInterpretationEmployeeView> employees
) {

    public WeeklyInterpretationDetailView {
        content = content.deepCopy();
        employees = List.copyOf(employees);
    }
}
