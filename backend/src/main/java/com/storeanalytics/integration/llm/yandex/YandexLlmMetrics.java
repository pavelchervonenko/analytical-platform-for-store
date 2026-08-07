package com.storeanalytics.integration.llm.yandex;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class YandexLlmMetrics {

    static final String CALLS = "storeanalytics.interpretation.llm.provider.calls";
    static final String TOKENS = "storeanalytics.interpretation.llm.provider.tokens";
    static final String COST = "storeanalytics.interpretation.llm.provider.cost.rub";
    static final String LATENCY = "storeanalytics.interpretation.llm.provider.latency";
    static final String PREFLIGHTS =
            "storeanalytics.interpretation.llm.provider.preflights";

    private final MeterRegistry registry;
    private final Map<String, Counter> calls = new ConcurrentHashMap<>();
    private final Map<String, Counter> tokens = new ConcurrentHashMap<>();
    private final Counter cost;
    private final Timer latency;
    private final Counter preflights;

    public YandexLlmMetrics(MeterRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        cost = Counter.builder(COST)
                .description("LLM provider cost reported in RUB")
                .tag("provider", "YANDEX")
                .register(registry);
        latency = Timer.builder(LATENCY)
                .description("LLM provider response latency")
                .tag("provider", "YANDEX")
                .register(registry);
        preflights = Counter.builder(PREFLIGHTS)
                .description("LLM provider local preflight evaluations")
                .tag("provider", "YANDEX")
                .register(registry);
    }

    public void preflight() {
        preflights.increment();
    }

    public void success(
            int inputTokens,
            int outputTokens,
            int cachedTokens,
            BigDecimal costAmount,
            Duration duration
    ) {
        call("success").increment();
        token("input").increment(inputTokens);
        token("output").increment(outputTokens);
        token("cached_input").increment(cachedTokens);
        cost.increment(costAmount.doubleValue());
        latency.record(duration);
    }

    public void failure(LlmProviderFailureKind kind, Duration duration) {
        call("failure_" + kind.name().toLowerCase(java.util.Locale.ROOT)).increment();
        latency.record(duration);
    }

    private Counter call(String outcome) {
        return calls.computeIfAbsent(outcome, value -> Counter.builder(CALLS)
                .description("LLM provider calls by safe outcome")
                .tag("provider", "YANDEX")
                .tag("outcome", value)
                .register(registry));
    }

    private Counter token(String type) {
        return tokens.computeIfAbsent(type, value -> Counter.builder(TOKENS)
                .description("LLM provider tokens by type")
                .tag("provider", "YANDEX")
                .tag("type", value)
                .register(registry));
    }
}
