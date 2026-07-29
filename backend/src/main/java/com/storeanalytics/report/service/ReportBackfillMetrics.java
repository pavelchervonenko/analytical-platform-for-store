package com.storeanalytics.report.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.report.model.ReportBackfillJobPhase;
import com.storeanalytics.report.model.ReportBackfillJobStatus;
import com.storeanalytics.report.repository.ReportBackfillJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class ReportBackfillMetrics implements MeterBinder {

    static final String DURATION_METRIC =
            "storeanalytics.report.backfill.step.duration";
    static final String JOBS_METRIC = "storeanalytics.report.backfill.jobs";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ReportBackfillMetrics.class
    );

    private final MeterRegistry meterRegistry;
    private final ReportBackfillJobRepository repository;
    private final Clock clock;
    private final AtomicReference<JobCounts> counts = new AtomicReference<>(
            JobCounts.unknown()
    );

    public ReportBackfillMetrics(
            MeterRegistry meterRegistry,
            ReportBackfillJobRepository repository,
            Clock clock
    ) {
        this.meterRegistry = meterRegistry;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "failed", value -> value.failed());
        gauge(registry, "retrying", value -> value.retrying());
        gauge(registry, "expired_lease", value -> value.expiredLease());
    }

    public void recordStep(ReportBackfillJobPhase phase, Runnable action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            action.run();
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(Timer.builder(DURATION_METRIC)
                    .description("Duration of one report backfill step")
                    .tag("phase", phase.name().toLowerCase(Locale.ROOT))
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    public void refresh() {
        try {
            counts.set(new JobCounts(
                    repository.countByStatus(ReportBackfillJobStatus.FAILED),
                    repository.countByStatus(
                            ReportBackfillJobStatus.WAITING_RETRY
                    ),
                    repository.countExpiredLeases(
                            ReportBackfillJobStatus.RUNNING,
                            clock.instant()
                    )
            ));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh report backfill metrics", exception);
        }
    }

    private void gauge(
            MeterRegistry registry,
            String status,
            java.util.function.ToDoubleFunction<JobCounts> value
    ) {
        Gauge.builder(JOBS_METRIC, counts, state -> value.applyAsDouble(
                state.get()
        )).description("Current report backfill jobs by operational status")
                .tag("status", status)
                .register(registry);
    }

    private record JobCounts(
            double failed,
            double retrying,
            double expiredLease
    ) {

        private static JobCounts unknown() {
            return new JobCounts(Double.NaN, Double.NaN, Double.NaN);
        }
    }
}
