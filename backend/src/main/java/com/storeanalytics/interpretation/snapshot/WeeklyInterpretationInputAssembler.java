package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Snapshot;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class WeeklyInterpretationInputAssembler {

    public WeeklyInterpretationInput assemble(
            WeeklySnapshotDraft draft,
            UUID snapshotId,
            int revision,
            String storeRef
    ) {
        WeeklySnapshotDraft value = requireNonNull(draft, "draft");
        WeeklyAnalyticsFactsQuery query = value.query();
        Snapshot snapshot = new Snapshot(
                requireNonNull(snapshotId, "snapshotId"),
                revision,
                value.factsHash(),
                requireText(storeRef, "storeRef"),
                value.timezone(),
                new Period(query.period().start(), query.period().end()),
                new Period(query.comparisonPeriod().start(), query.comparisonPeriod().end()),
                value.qualityStatus(),
                value.versions()
        );
        return new WeeklyInterpretationInput(
                value.payload().contractVersion(),
                snapshot,
                value.payload().manifest(),
                value.payload().facts()
        );
    }
}
