package com.storeanalytics.interpretation.query;

import java.util.UUID;

public record WeeklyInterpretationEmployeeView(
        String employeeRef,
        UUID employeeId,
        String displayName
) {
}
