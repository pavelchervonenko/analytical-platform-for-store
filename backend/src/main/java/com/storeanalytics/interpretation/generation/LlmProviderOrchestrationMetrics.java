package com.storeanalytics.interpretation.generation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderOrchestrationMetrics {

    static final String PREFLIGHT_REJECTIONS =
            "storeanalytics.interpretation.llm.preflight.rejections";

    private final MeterRegistry registry;
    private final Map<String, Counter> rejections = new ConcurrentHashMap<>();

    public LlmProviderOrchestrationMetrics(MeterRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
    }

    public void preflightRejected(String reason) {
        String safeReason = java.util.Objects.requireNonNull(reason, "reason");
        rejections.computeIfAbsent(safeReason, value -> Counter.builder(
                PREFLIGHT_REJECTIONS
        )
                .description("LLM provider calls rejected before network execution")
                .tag("reason", value)
                .register(registry))
                .increment();
    }
}
