package com.storeanalytics.interpretation.query;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import java.util.UUID;

/** Safe, backend-formatted evidence exposed to dashboard consumers. */
public record WeeklyInsightEvidenceView(
        String evidenceCode,
        String label,
        String formattedValue,
        String previousFormattedValue,
        String absoluteDeltaFormatted,
        String relativeDeltaFormatted,
        String comparisonText,
        Unit unit,
        Sufficiency sufficiency,
        Scope scope,
        UUID employeeId,
        String displayName,
        String categoryLabel,
        boolean available
) {

    public WeeklyInsightEvidenceView {
        requireText(evidenceCode, "evidenceCode");
        requireText(label, "label");
        requireNonNull(scope, "scope");
    }
}
