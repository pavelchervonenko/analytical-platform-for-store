package com.storeanalytics.interpretation.publication;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class LlmPublicationMetrics {

    static final String PUBLICATIONS_METRIC =
            "storeanalytics.interpretation.llm.publications";
    static final String DURATION_METRIC =
            "storeanalytics.interpretation.llm.publication.duration";

    private final Counter published;
    private final Timer duration;

    public LlmPublicationMetrics(MeterRegistry registry) {
        MeterRegistry meterRegistry = requireNonNull(registry, "registry");
        published = Counter.builder(PUBLICATIONS_METRIC)
                .description("Published immutable weekly LLM interpretations")
                .register(meterRegistry);
        duration = Timer.builder(DURATION_METRIC)
                .description("LLM interpretation publication duration")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void published(Duration elapsed) {
        published.increment();
        duration.record(requireNonNull(elapsed, "elapsed"));
    }
}
