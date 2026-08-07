package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import java.util.List;

public record WeeklyInsightFallbackView(
        String title,
        String summary,
        QualityStatus qualityStatus,
        List<String> dataLimitationCodes
) {

    public WeeklyInsightFallbackView {
        dataLimitationCodes = List.copyOf(dataLimitationCodes);
    }
}
