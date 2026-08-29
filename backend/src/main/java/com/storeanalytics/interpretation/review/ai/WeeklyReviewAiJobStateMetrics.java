package com.storeanalytics.interpretation.review.ai;

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
public class WeeklyReviewAiJobStateMetrics implements MeterBinder {

    static final String JOBS_METRIC =
            "storeanalytics.interpretation.weekly.review.ai.jobs";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklyReviewAiJobStateMetrics.class
    );

    private final WeeklyReviewAiJobStore jobStore;
    private final WeeklyReviewAiGenerationProperties properties;
    private final Clock clock;
    private final AtomicReference<JobCounts> counts = new AtomicReference<>(
            JobCounts.unknown()
    );

    public WeeklyReviewAiJobStateMetrics(
            WeeklyReviewAiJobStore jobStore,
            WeeklyReviewAiGenerationProperties properties,
            Clock clock
    ) {
        this.jobStore = jobStore;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "pending", JobCounts::pending);
        gauge(registry, "running", JobCounts::running);
        gauge(registry, "retry_wait", JobCounts::retryWait);
        gauge(registry, "succeeded", JobCounts::succeeded);
        gauge(registry, "failed", JobCounts::failed);
        gauge(registry, "delayed", JobCounts::delayed);
        gauge(registry, "expired_lease", JobCounts::expiredLease);
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    public void refresh() {
        try {
            java.time.Instant now = clock.instant();
            counts.set(new JobCounts(
                    jobStore.countByStatus(WeeklyReviewAiJobStatus.PENDING),
                    jobStore.countByStatus(WeeklyReviewAiJobStatus.RUNNING),
                    jobStore.countByStatus(WeeklyReviewAiJobStatus.RETRY_WAIT),
                    jobStore.countByStatus(WeeklyReviewAiJobStatus.SUCCEEDED),
                    jobStore.countByStatus(WeeklyReviewAiJobStatus.FAILED),
                    jobStore.countDelayed(
                            now.minus(properties.preparationSla())
                    ),
                    jobStore.countExpiredLeases(now)
            ));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh weekly review AI job metrics", exception);
        }
    }

    private void gauge(
            MeterRegistry registry,
            String status,
            java.util.function.ToDoubleFunction<JobCounts> value
    ) {
        Gauge.builder(
                JOBS_METRIC,
                counts,
                state -> value.applyAsDouble(state.get())
        ).description("Current v22 weekly review AI jobs by operational state")
                .tag("status", status)
                .register(registry);
    }

    private record JobCounts(
            double pending,
            double running,
            double retryWait,
            double succeeded,
            double failed,
            double delayed,
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
                    Double.NaN
            );
        }
    }
}
