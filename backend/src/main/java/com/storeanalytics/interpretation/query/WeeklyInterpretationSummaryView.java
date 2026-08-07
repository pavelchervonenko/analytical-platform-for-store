package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisTriggerType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WeeklyInterpretationSummaryView(
        UUID id,
        UUID storeId,
        UUID snapshotId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String timezone,
        int snapshotRevision,
        int interpretationRevision,
        boolean currentRevision,
        UUID supersedesInterpretationId,
        LlmAnalysisTriggerType publicationReason,
        String contentHash,
        int contentSchemaVersion,
        QualityStatus qualityStatus,
        int employeeCount,
        Instant validatedAt,
        Instant publishedAt
) {
}
