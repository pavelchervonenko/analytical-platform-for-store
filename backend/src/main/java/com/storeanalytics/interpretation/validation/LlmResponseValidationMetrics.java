package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LlmResponseValidationMetrics {

    static final String RESULTS_METRIC =
            "storeanalytics.interpretation.llm.validation.results";
    static final String DURATION_METRIC =
            "storeanalytics.interpretation.llm.validation.duration";

    private final Map<LlmValidationOutcome, Counter> outcomes;
    private final Timer duration;

    public LlmResponseValidationMetrics(MeterRegistry registry) {
        MeterRegistry meterRegistry = requireNonNull(registry, "registry");
        outcomes = new EnumMap<>(LlmValidationOutcome.class);
        for (LlmValidationOutcome outcome : LlmValidationOutcome.values()) {
            outcomes.put(outcome, Counter.builder(RESULTS_METRIC)
                    .description("LLM response validation results")
                    .tag("result", outcome.name().toLowerCase())
                    .register(meterRegistry));
        }
        duration = Timer.builder(DURATION_METRIC)
                .description("LLM response validation duration")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void record(LlmValidationOutcome outcome, Duration elapsed) {
        outcomes.get(requireNonNull(outcome, "outcome")).increment();
        duration.record(requireNonNull(elapsed, "elapsed"));
    }
}
