package com.storeanalytics.salary.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class PayrollRunMetrics implements MeterBinder {

    static final String RUNS_METRIC = "storeanalytics.payroll.runs";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PayrollRunMetrics.class
    );

    private final PayrollRunRepository runRepository;
    private final PayrollFreshnessService freshnessService;
    private final AtomicReference<PayrollCounts> counts = new AtomicReference<>(
            PayrollCounts.unknown()
    );

    public PayrollRunMetrics(
            PayrollRunRepository runRepository,
            PayrollFreshnessService freshnessService
    ) {
        this.runRepository = runRepository;
        this.freshnessService = freshnessService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "incomplete", PayrollCounts::incomplete);
        gauge(registry, "stale", PayrollCounts::stale);
        gauge(registry, "unknown", PayrollCounts::unknownFreshness);
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.payroll-refresh-delay:5m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            counts.set(calculate(runRepository.findLatestByStatusIn(
                    EnumSet.of(
                            PayrollRunStatus.CALCULATED,
                            PayrollRunStatus.APPROVED
                    )
            )));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh payroll metrics", exception);
        }
    }

    private PayrollCounts calculate(List<PayrollRun> runs) {
        long incomplete = 0;
        long stale = 0;
        long unknown = 0;
        for (PayrollRun run : runs) {
            if (!run.isCalculationComplete()) {
                incomplete++;
            }
            PayrollFreshnessStatus status;
            try {
                status = freshnessService.evaluate(run).status();
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Failed to evaluate payroll freshness runId={}",
                        run.getId(),
                        exception
                );
                status = PayrollFreshnessStatus.UNKNOWN;
            }
            if (status != PayrollFreshnessStatus.CURRENT) {
                stale++;
            }
            if (status == PayrollFreshnessStatus.UNKNOWN) {
                unknown++;
            }
        }
        return new PayrollCounts(incomplete, stale, unknown);
    }

    private void gauge(
            MeterRegistry registry,
            String state,
            ToDoubleFunction<PayrollCounts> value
    ) {
        Gauge.builder(
                        RUNS_METRIC,
                        counts,
                        current -> value.applyAsDouble(current.get())
                )
                .description("Latest actionable payroll runs by operational state")
                .tag("state", state)
                .register(registry);
    }

    private record PayrollCounts(
            double incomplete,
            double stale,
            double unknownFreshness
    ) {

        private static PayrollCounts unknown() {
            return new PayrollCounts(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN
            );
        }
    }
}
