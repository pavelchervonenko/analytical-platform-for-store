package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Snapshot;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import org.springframework.stereotype.Component;

@Component
public final class PersistedWeeklyInterpretationInputFactory {

    private static final String STORE_REF = "S01";

    public WeeklyInterpretationInput create(PersistedWeeklySnapshot snapshot) {
        PersistedWeeklySnapshot value = requireNonNull(snapshot, "snapshot");
        Snapshot header = new Snapshot(
                value.id(),
                value.revision(),
                value.factsHash(),
                STORE_REF,
                value.timezone(),
                new Period(
                        value.query().period().start(),
                        value.query().period().end()
                ),
                new Period(
                        value.query().comparisonPeriod().start(),
                        value.query().comparisonPeriod().end()
                ),
                value.qualityStatus(),
                value.versions()
        );
        return new WeeklyInterpretationInput(
                value.payload().contractVersion(),
                header,
                value.payload().manifest(),
                value.payload().facts()
        );
    }
}
