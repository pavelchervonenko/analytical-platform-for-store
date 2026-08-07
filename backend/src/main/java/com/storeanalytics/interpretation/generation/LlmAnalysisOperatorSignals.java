package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LlmAnalysisOperatorSignals {

    static final String EVENTS_METRIC =
            "storeanalytics.interpretation.llm.job.events";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LlmAnalysisOperatorSignals.class
    );

    private final Counter leaseRecoveries;
    private final Counter deadlinesExceeded;

    public LlmAnalysisOperatorSignals(MeterRegistry registry) {
        MeterRegistry meterRegistry = requireNonNull(registry, "registry");
        leaseRecoveries = counter(meterRegistry, "expired_lease_recovered");
        deadlinesExceeded = counter(meterRegistry, "deadline_exceeded");
    }

    public void recoveredLease(LlmAnalysisJob job) {
        LlmAnalysisJob recovered = requireNonNull(job, "job");
        leaseRecoveries.increment();
        LOGGER.atWarn()
                .addKeyValue("event_code", "llm_analysis_lease_recovered")
                .addKeyValue("job_id", recovered.id())
                .addKeyValue("snapshot_id", recovered.snapshotId())
                .addKeyValue("status", recovered.status())
                .addKeyValue("phase", recovered.phase())
                .addKeyValue("attempt_count", recovered.attemptCount())
                .log("Recovered expired LLM analysis worker lease");
    }

    public void deadlineExceeded(LlmAnalysisJob job) {
        LlmAnalysisJob expired = requireNonNull(job, "job");
        deadlinesExceeded.increment();
        LOGGER.atError()
                .addKeyValue("event_code", "llm_analysis_deadline_exceeded")
                .addKeyValue("job_id", expired.id())
                .addKeyValue("snapshot_id", expired.snapshotId())
                .addKeyValue("status", expired.status())
                .addKeyValue("phase", expired.phase())
                .addKeyValue("attempt_count", expired.attemptCount())
                .log("LLM analysis job exceeded generation deadline");
    }

    private Counter counter(MeterRegistry registry, String event) {
        return Counter.builder(EVENTS_METRIC)
                .description("LLM analysis operational transition events")
                .tag("event", event)
                .register(registry);
    }
}
