package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;

final class SnapshotFactFactory {

    private SnapshotFactFactory() {
    }

    static Fact numeric(
            String evidenceRef,
            String metricCode,
            BigDecimal current,
            BigDecimal previous,
            FactOptions options
    ) {
        if (current == null) {
            return null;
        }
        Comparison comparison = previous == null
                ? null
                : comparison(current, previous, options.relativeDeltaAllowed());
        return new Fact(
                evidenceRef,
                metricCode,
                options.categoryCode(),
                options.unit(),
                current,
                comparison,
                options.sufficiency(),
                options.materiality()
        );
    }

    static Fact count(
            String evidenceRef,
            String metricCode,
            long current,
            Long previous,
            Sufficiency sufficiency,
            Materiality materiality
    ) {
        BigDecimal previousValue = previous == null ? null : BigDecimal.valueOf(previous);
        return numeric(
                evidenceRef,
                metricCode,
                BigDecimal.valueOf(current),
                previousValue,
                new FactOptions(null, Unit.COUNT, sufficiency, materiality, true)
        );
    }

    static Fact status(
            String evidenceRef,
            String metricCode,
            String value,
            Sufficiency sufficiency
    ) {
        return new Fact(
                evidenceRef,
                metricCode,
                null,
                Unit.STATUS,
                value,
                null,
                sufficiency,
                Materiality.CONTEXT
        );
    }

    private static Comparison comparison(
            BigDecimal current,
            BigDecimal previous,
            boolean relativeDeltaAllowed
    ) {
        BigDecimal delta = current.subtract(previous);
        BigDecimal relative = relativeDeltaAllowed && previous.signum() > 0
                ? delta.multiply(BigDecimal.valueOf(100))
                        .divide(previous.abs(), 4, RoundingMode.HALF_UP)
                : null;
        return new Comparison(previous, delta, relative);
    }

    record FactOptions(
            String categoryCode,
            Unit unit,
            Sufficiency sufficiency,
            Materiality materiality,
            boolean relativeDeltaAllowed
    ) {
    }
}
