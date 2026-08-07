package com.storeanalytics.interpretation.generation;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class LlmAnalysisJobStateMetrics implements MeterBinder {

    static final String JOBS_METRIC = "storeanalytics.interpretation.llm.jobs";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LlmAnalysisJobStateMetrics.class
    );

    private final LlmAnalysisJobLifecycleStore lifecycleStore;
    private final Clock clock;
    private final AtomicReference<JobCounts> counts = new AtomicReference<>(
            JobCounts.unknown()
    );

    public LlmAnalysisJobStateMetrics(
            LlmAnalysisJobLifecycleStore lifecycleStore,
            Clock clock
    ) {
        this.lifecycleStore = lifecycleStore;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "pending", value -> value.pending());
        gauge(registry, "running", value -> value.running());
        gauge(registry, "retrying", value -> value.retrying());
        gauge(registry, "success", value -> value.success());
        gauge(registry, "failed", value -> value.failed());
        gauge(registry, "validation_failed", value -> value.validationFailed());
        gauge(registry, "skipped", value -> value.skipped());
        gauge(registry, "deadline_exceeded", value -> value.deadlineExceeded());
        gauge(registry, "expired_lease", value -> value.expiredLease());
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    public void refresh() {
        try {
            counts.set(new JobCounts(
                    lifecycleStore.countByStatus(LlmAnalysisJobStatus.PENDING),
                    lifecycleStore.countByStatus(LlmAnalysisJobStatus.RUNNING),
                    lifecycleStore.countByStatus(LlmAnalysisJobStatus.WAITING_RETRY),
                    lifecycleStore.countByStatus(LlmAnalysisJobStatus.SUCCESS),
                    lifecycleStore.countByStatus(LlmAnalysisJobStatus.FAILED),
                    lifecycleStore.countByStatus(LlmAnalysisJobStatus.VALIDATION_FAILED),
                    lifecycleStore.countByStatus(LlmAnalysisJobStatus.SKIPPED),
                    lifecycleStore.countDeadlineExceeded(),
                    lifecycleStore.countExpiredLeases(clock.instant())
            ));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh LLM analysis job metrics", exception);
        }
    }

    private void gauge(
            MeterRegistry registry,
            String status,
            java.util.function.ToDoubleFunction<JobCounts> value
    ) {
        Gauge.builder(JOBS_METRIC, counts, state -> value.applyAsDouble(state.get()))
                .description("Current LLM analysis jobs by operational status")
                .tag("status", status)
                .register(registry);
    }

    private record JobCounts(
            double pending,
            double running,
            double retrying,
            double success,
            double failed,
            double validationFailed,
            double skipped,
            double deadlineExceeded,
            double expiredLease
    ) {

        private static JobCounts unknown() {
            return new JobCounts(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN
            );
        }
    }
}
